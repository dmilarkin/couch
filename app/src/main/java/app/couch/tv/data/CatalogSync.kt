package app.couch.tv.data

import android.util.JsonReader
import android.util.JsonToken
import app.couch.tv.CouchApp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Downloads the provider's whole catalog into the local database. The JSON for a big
 * provider can be 30+ MB, so it's parsed as a stream and written in batches.
 */
class CatalogSync(private val app: CouchApp) {

    data class State(
        val running: Boolean = false,
        val stage: String = "",
        val movies: Int = 0,
        val series: Int = 0,
        val live: Int = 0,
        val error: String? = null,
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
        val x = Xtream(app.http, creds)
        _state.value = State(running = true, stage = "Signing in")
        app.prefs.account = x.login()
        val syncId = System.currentTimeMillis()
        val plan = listOf(
            Triple("movie", "get_vod_categories", "get_vod_streams"),
            Triple("series", "get_series_categories", "get_series"),
            Triple("live", "get_live_categories", "get_live_streams"),
        )
        for ((kind, catAction, listAction) in plan) {
            _state.update { it.copy(stage = "Loading ${label(kind)}") }
            val cfg = app.prefs.config.value
            val cats = x.categories(catAction).mapIndexed { i, (id, name) ->
                Category(kind, id, name, TitleParser.categoryLang(name), i, TitleParser.isAdult(name))
            }
            app.db.categories().clear(kind)
            app.db.categories().upsert(cats)
            val catMap = cats.associateBy { it.id }

            var count = 0
            val batch = ArrayList<Item>(500)
            x.open(listAction).use { resp ->
                val body = resp.body ?: return@use
                val reader = JsonReader(body.charStream())
                reader.isLenient = true
                if (reader.peek() != JsonToken.BEGIN_ARRAY) {
                    reader.skipValue()
                    return@use
                }
                reader.beginArray()
                while (reader.hasNext()) {
                    if (reader.peek() != JsonToken.BEGIN_OBJECT) {
                        reader.skipValue()
                        continue
                    }
                    val item = toItem(kind, reader.readFlatObject(), catMap, syncId, cfg) ?: continue
                    batch += item
                    if (batch.size >= 500) {
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
                batch.clear()
            }
            report(kind, count)
            app.db.items().deleteStale(kind, syncId)
        }
        app.db.cache().prune(System.currentTimeMillis() - 30L * 24 * 3600_000)
        app.prefs.lastSync = System.currentTimeMillis()
        _state.update { it.copy(running = false, stage = "Done", error = null, finishedAt = System.currentTimeMillis()) }
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
