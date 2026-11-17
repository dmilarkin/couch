package app.couch.tv.data

import android.util.JsonReader
import android.util.JsonToken
import app.couch.tv.CouchApp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.BufferedInputStream
import java.io.File
import java.io.FileInputStream
import java.io.InputStreamReader
import java.util.concurrent.TimeUnit

/**
 * Downloads the provider's whole catalog into the local database. The JSON for a big
 * provider can be 30+ MB, so it's saved to a file, then parsed as a stream and written in batches.
 */
class CatalogSync(private val app: CouchApp) {

    data class State(
        val running: Boolean = false,
        val stage: String = "",
        val movies: Int = 0,
        val series: Int = 0,
        val live: Int = 0,
        val error: String? = null,
        val warning: String? = null,
        val finishedAt: Long = 0L,
    )

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state
    private var job: Job? = null

    fun start() {
        if (job?.isActive == true) return
        job = app.scope.launch(Dispatchers.IO) {
            try {
                run()
            } catch (e: Exception) {
                _state.update { it.copy(running = false, error = e.message ?: e.javaClass.simpleName) }
            }
        }
    }

    private suspend fun run() {
        val creds = app.prefs.config.value.creds() ?: return
        // Separate client with patient timeouts: the catalog can be tens of megabytes.
        val client = app.http.newBuilder()
            .readTimeout(3, TimeUnit.MINUTES)
            .retryOnConnectionFailure(true)
            .build()
        val x = Xtream(client, creds)
        _state.value = State(running = true, stage = "Signing in")
        app.prefs.account = x.login()
        val syncId = System.currentTimeMillis()
        val plan = listOf(
            Triple("movie", "get_vod_categories", "get_vod_streams"),
            Triple("series", "get_series_categories", "get_series"),
            Triple("live", "get_live_categories", "get_live_streams"),
        )
        val incomplete = mutableListOf<String>()
        for ((kind, catAction, listAction) in plan) {
            _state.update { it.copy(stage = "Loading ${label(kind)}") }
            val cfg = app.prefs.config.value
            val cats = x.categories(catAction).mapIndexed { i, (id, name) ->
                Category(kind, id, name, TitleParser.categoryLang(name), i, TitleParser.isAdult(name))
            }
            app.db.categories().clear(kind)
            app.db.categories().upsert(cats)
            if (loadKind(x, kind, listAction, cats, syncId, cfg)) {
                // Only forget titles the provider removed when we know we got the full list.
                app.db.items().deleteStale(kind, syncId)
            } else {
                incomplete += label(kind)
            }
        }
        app.db.cache().prune(System.currentTimeMillis() - 30L * 24 * 3600_000)
        app.prefs.lastSync = System.currentTimeMillis()
        val warning = if (incomplete.isEmpty()) {
            null
        } else {
            "Some ${incomplete.joinToString(" and ")} didn't download because the provider kept dropping the connection. " +
                "Everything else works. Refresh the library later to fill the gaps."
        }
        _state.update {
            it.copy(running = false, stage = "Done", error = null, warning = warning, finishedAt = System.currentTimeMillis())
        }
    }

    /**
     * Gets one kind of content (movies, series or channels). Returns true if the full list arrived.
     *
     * Step 1 downloads the whole list to a file at full network speed and only then reads it, so a
     * slow TV database never stalls the transfer (stalled transfers are what make providers hang up).
     * Step 2, if the provider still cuts the big download, fetches one category at a time instead.
     */
    private suspend fun loadKind(
        x: Xtream,
        kind: String,
        action: String,
        cats: List<Category>,
        syncId: Long,
        cfg: Config,
    ): Boolean {
        val catMap = cats.associateBy { it.id }
        val file = File(app.cacheDir, "sync-$kind.json")
        try {
            for (attempt in 1..3) {
                try {
                    val again = if (attempt > 1) " (attempt $attempt of 3)" else ""
                    _state.update { it.copy(stage = "Downloading ${label(kind)}$again") }
                    x.downloadTo(file, action)
                    _state.update { it.copy(stage = "Saving ${label(kind)}") }
                    importFile(file, kind, catMap, syncId, cfg, 0)
                    return true
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    delay(2_000L * attempt)
                }
            }

            val wanted = cats.filter { cfg.showAdult || !it.adult }
            if (wanted.isEmpty()) return false
            var total = 0
            var failed = 0
            wanted.forEachIndexed { i, cat ->
                _state.update { it.copy(stage = "Loading ${label(kind)} one category at a time (${i + 1} of ${wanted.size})") }
                var ok = false
                for (attempt in 1..3) {
                    try {
                        x.downloadTo(file, action, mapOf("category_id" to cat.id))
                        total = importFile(file, kind, catMap, syncId, cfg, total)
                        ok = true
                        break
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        delay(1_500L * attempt)
                    }
                }
                if (!ok) failed++
            }
            return failed == 0
        } finally {
            file.delete()
        }
    }

    /** Reads a downloaded list and saves it in batches. Returns the running total for progress. */
    private suspend fun importFile(
        file: File,
        kind: String,
        catMap: Map<String, Category>,
        syncId: Long,
        cfg: Config,
        startCount: Int,
    ): Int {
        var count = startCount
        if (file.length() == 0L) return count
        val batch = ArrayList<Item>(1000)
        JsonReader(InputStreamReader(BufferedInputStream(FileInputStream(file), 64 * 1024), Charsets.UTF_8)).use { reader ->
            reader.isLenient = true
            if (reader.peek() != JsonToken.BEGIN_ARRAY) {
                reader.skipValue()
                return count
            }
            reader.beginArray()
            while (reader.hasNext()) {
                if (reader.peek() != JsonToken.BEGIN_OBJECT) {
                    reader.skipValue()
                    continue
                }
                val item = toItem(kind, reader.readFlatObject(), catMap, syncId, cfg) ?: continue
                batch += item
                if (batch.size >= 1000) {
                    app.db.items().upsert(ArrayList(batch))
                    count += batch.size
                    batch.clear()
                    report(kind, count)
                }
            }
            reader.endArray()
        }
        if (batch.isNotEmpty()) {
            app.db.items().upsert(ArrayList(batch))
            count += batch.size
        }
        report(kind, count)
        return count
    }

    private fun report(kind: String, count: Int) {
        _state.update {
            when (kind) {
                "movie" -> it.copy(movies = count)
                "series" -> it.copy(series = count)
                else -> it.copy(live = count)
            }
        }
    }

    private fun label(kind: String) = when (kind) {
        "movie" -> "movies"
        "series" -> "series"
        else -> "live channels"
    }

    private fun toItem(kind: String, m: Map<String, String?>, cats: Map<String, Category>, syncId: Long, cfg: Config): Item? {
        val idStr = (if (kind == "series") m["series_id"] else m["stream_id"]) ?: return null
        val sid = idStr.toLongOrNull() ?: return null
        val name = (m["name"] ?: m["title"])?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        val catId = m["category_id"] ?: ""
        val cat = cats[catId]
        if (!cfg.showAdult && (m["is_adult"] == "1" || cat?.adult == true)) return null

        val p = TitleParser.parse(name, cat?.lang)
        val year = p.year.takeIf { it > 0 }
            ?: TitleParser.yearFrom(m["year"])
            ?: TitleParser.yearFrom(m["releaseDate"])
            ?: TitleParser.yearFrom(m["release_date"])
            ?: TitleParser.yearFrom(m["releasedate"])
            ?: 0
        val genres = TitleParser.genres(m["genre"]).ifEmpty { TitleParser.genres(cat?.name) }
        val poster = (if (kind == "series") m["cover"] else m["stream_icon"])?.takeIf { it.startsWith("http") }
        val search = TitleParser.normalize(p.title)
        val lang = p.lang
        return Item(
            id = "$kind:$sid",
            kind = kind,
            streamId = sid,
            name = name,
            title = p.title,
            search = search,
            year = if (kind == "live") 0 else year,
            poster = poster,
            backdrop = m["backdrop_path"]?.takeIf { it.startsWith("http") },
            rating = TitleParser.rating(m["rating"], m["rating_5based"]),
            added = (m["added"] ?: m["last_modified"])?.toLongOrNull() ?: 0L,
            categoryId = catId,
            lang = lang,
            quality = p.quality ?: TitleParser.qualityFrom(cat?.name),
            genre = if (genres.isEmpty()) null else genres.joinToString(",", prefix = ",", postfix = ","),
            ext = m["container_extension"],
            plot = m["plot"]?.takeIf { it.isNotBlank() },
            groupKey = if (kind == "movie") "$search|$year" else "$kind:$sid",
            num = m["num"]?.toIntOrNull() ?: 0,
            epgId = m["epg_channel_id"],
            tmdbId = (m["tmdb"] ?: m["tmdb_id"])?.takeIf { it.isNotBlank() && it != "0" },
            syncId = syncId,
            hidden = cfg.isHidden(kind, catId, lang),
        )
    }

    /** Re-applies the library filter to everything already downloaded. Quick: a few UPDATE statements. */
    suspend fun applyFilter() = withContext(Dispatchers.IO) {
        val cfg = app.prefs.config.value
        val db = app.db.openHelper.writableDatabase
        db.beginTransaction()
        try {
            db.execSQL("UPDATE items SET hidden = 0")
            val langs = cfg.hiddenLangs.filter { it != "?" }
            if (langs.isNotEmpty()) {
                db.execSQL(
                    "UPDATE items SET hidden = 1 WHERE lang IN (${langs.joinToString(",") { "?" }})",
                    langs.toTypedArray(),
                )
            }
            if (cfg.hiddenLangs.contains("?")) db.execSQL("UPDATE items SET hidden = 1 WHERE lang IS NULL")
            cfg.hiddenCats.groupBy({ it.substringBefore(':') }, { it.substringAfter(':') }).forEach { (kind, ids) ->
                ids.chunked(400).forEach { chunk ->
                    val args: Array<Any?> = (listOf<Any?>(kind) + chunk).toTypedArray()
                    db.execSQL(
                        "UPDATE items SET hidden = 1 WHERE kind = ? AND categoryId IN (${chunk.joinToString(",") { "?" }})",
                        args,
                    )
                }
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
        _state.update { it.copy(finishedAt = System.currentTimeMillis()) }
    }
}
