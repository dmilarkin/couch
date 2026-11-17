package app.couch.tv.data

import androidx.sqlite.db.SimpleSQLiteQuery
import app.couch.tv.CouchApp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.Calendar
import kotlin.random.Random

/** The one place the UI and the phone remote get data from. */
class Repo(private val app: CouchApp) {

    private val items get() = app.db.items()
    private val cfg get() = app.prefs.config.value

    fun xtream(): Xtream? = cfg.creds()?.let { Xtream(app.http, it) }

    // ------------------------------------------------------------------ cards

    fun subtitleOf(item: Item): String? =
        listOfNotNull(item.year.takeIf { it > 0 }?.toString(), item.lang).joinToString(" · ").ifBlank { null }

    fun cardOf(item: Item): Card = Card(
        id = item.id,
        title = item.title,
        subtitle = subtitleOf(item),
        image = item.poster,
        badge = if (item.quality == "4K") "4K" else null,
        item = item,
    )

    fun versionLabel(item: Item): String =
        listOfNotNull(item.quality ?: "Standard", item.lang).joinToString(" · ")

    private fun sortVersions(list: List<Item>): List<Item> {
        val pref = cfg.prefLang
        return list.sortedWith(
            compareByDescending<Item> { it.lang == pref }.thenByDescending { TitleParser.qualityRank(it.quality) },
        )
    }

    // ------------------------------------------------------------------ browse

    suspend fun browse(f: Filter, offset: Int = 0, limit: Int = 60): List<Item> = browseRaw(f, offset, limit, grouped = true)

    /**
     * A quick shelf for the home screen. Grouping the whole movie table is slow on TV hardware,
     * so fetch a few extra rows in order and drop duplicate versions here instead.
     */
    suspend fun shelf(f: Filter, n: Int = 30): List<Item> =
        if (f.kind != "movie") browseRaw(f, 0, n, grouped = false)
        else browseRaw(f, 0, n * 3, grouped = false).distinctBy { it.groupKey }.take(n)

    private suspend fun browseRaw(f: Filter, offset: Int, limit: Int, grouped: Boolean): List<Item> = withContext(Dispatchers.IO) {
        val where = mutableListOf("kind = ?", "hidden = 0")
        val args = mutableListOf<Any>(f.kind)
        f.categoryId?.let {
            where += "categoryId = ?"
            args += it
        }
        f.lang?.let {
            where += "lang = ?"
            args += it
        }
        f.decade?.let {
            where += "year BETWEEN ? AND ?"
            args += it
            args += it + 9
        }
        f.genre?.let {
            where += "genre LIKE ?"
            args += "%,$it,%"
        }
        val group = if (grouped && f.kind == "movie") " GROUP BY groupKey" else ""
        val order = when {
            f.kind == "live" -> "num ASC, title COLLATE NOCASE ASC"
            f.sort == Sort.Recent -> "added DESC"
            f.sort == Sort.Rating -> "rating DESC, added DESC"
            f.sort == Sort.Newest -> "year DESC, added DESC"
            f.sort == Sort.Oldest -> "CASE WHEN year = 0 THEN 9999 ELSE year END ASC, title COLLATE NOCASE ASC"
            else -> "title COLLATE NOCASE ASC"
        }
        // "id" as a final tie-breaker keeps paging stable (no item shows up on two pages).
        val sql = "SELECT * FROM items WHERE ${where.joinToString(" AND ")}$group ORDER BY $order, id LIMIT $limit OFFSET $offset"
        items.query(SimpleSQLiteQuery(sql, args.toTypedArray()))
    }

    suspend fun browseOptions(kind: String): BrowseOptions = withContext(Dispatchers.IO) {
        val visible = items.visibleCategoryIds(kind).toHashSet()
        BrowseOptions(
            categories = app.db.categories().list(kind).filter { it.id in visible },
            langs = items.langs(kind),
            decades = items.decades(kind),
            genres = TitleParser.GENRE_NAMES,
        )
    }

    suspend fun search(q: String, limit: Int = 40): SearchResult = withContext(Dispatchers.IO) {
        val norm = TitleParser.normalize(q)
        if (norm.length < 2) return@withContext SearchResult(emptyList(), emptyList(), emptyList())
        val toks = norm.split(" ").filter { it.isNotBlank() }.take(6)
        suspend fun findKind(kind: String): List<Item> {
            val args = mutableListOf<Any>(kind)
            toks.forEach { args += "%$it%" }
            args += "$norm%"
            val group = if (kind == "movie") " GROUP BY groupKey" else ""
            val sql = "SELECT * FROM items WHERE kind = ? AND hidden = 0 AND " +
                toks.joinToString(" AND ") { "search LIKE ?" } +
                "$group ORDER BY CASE WHEN search LIKE ? THEN 0 ELSE 1 END, rating DESC, added DESC LIMIT $limit"
            return items.query(SimpleSQLiteQuery(sql, args.toTypedArray()))
        }
        SearchResult(findKind("movie"), findKind("series"), findKind("live"))
    }

    // ------------------------------------------------------------------ home

    suspend fun homeRows(): List<Row> = withContext(Dispatchers.IO) {
        val rows = mutableListOf<Row>()
        val cw = continueWatching()
        if (cw.isNotEmpty()) rows += Row("cw", "Continue Watching", cw, wide = true)
        val favs = favoriteItems()
        if (favs.isNotEmpty()) rows += Row("fav", "My List", favs.map(::cardOf))

        fun add(key: String, title: String, list: List<Item>, min: Int = 1) {
            if (list.size >= min) rows += Row(key, title, list.map(::cardOf))
        }
        add("new-movies", "New Movies", shelf(Filter("movie", sort = Sort.Recent)))
        add("new-series", "New Series", shelf(Filter("series", sort = Sort.Recent)))
        add("top-movies", "Top Rated Movies", shelf(Filter("movie", sort = Sort.Rating)), 6)
        add("top-series", "Top Rated Series", shelf(Filter("series", sort = Sort.Rating)), 6)

        val day = Calendar.getInstance().get(Calendar.DAY_OF_YEAR)
        val rnd = Random(day)

        // Other languages you kept in the library get their own shelf.
        val langs = items.langs("movie")
        langs.filter { it != cfg.prefLang }.take(3).forEach { l ->
            add("lang-$l", "$l Movies", shelf(Filter("movie", lang = l, sort = Sort.Recent)), 6)
        }

        val decades = items.decades("movie").filter { it in 1930..2010 }.shuffled(rnd).take(3).sorted()
        decades.forEach { d ->
            val label = if (d < 2000) "The ${d % 100}s" else "The ${d}s"
            add("decade-$d", label, shelf(Filter("movie", decade = d, sort = Sort.Rating)), 8)
        }

        val month = Calendar.getInstance().get(Calendar.MONTH)
        val genres = TitleParser.GENRE_NAMES.filter { it != "Holiday" || month == Calendar.DECEMBER }.shuffled(rnd).take(5)
        genres.forEach { g ->
            add("genre-$g", g, shelf(Filter("movie", genre = g, sort = Sort.Recent)), 8)
        }
        rows
    }

    suspend fun continueWatching(limit: Int = 20): List<Card> = withContext(Dispatchers.IO) {
        val seen = HashSet<String>()
        val out = mutableListOf<Card>()
        for (p in app.db.progress().recent()) {
            if (!seen.add(p.itemId)) continue
            if (p.finished) continue
            if (!p.upNext && p.positionMs < 30_000) continue
            val pct = if (p.durationMs > 0) p.positionMs.toFloat() / p.durationMs else 0f
            out += Card(
                id = p.itemId,
                title = p.title,
                subtitle = if (p.upNext) "Up next · ${p.subtitle ?: ""}".trimEnd(' ', '·') else p.subtitle,
                image = p.image,
                wide = true,
                progress = pct,
                resume = p,
            )
            if (out.size >= limit) break
        }
        out
    }

    private val peekCache = HashMap<String, Peek>()

    /** Quick extra info for the home screen hero (backdrop + plot) of the focused poster. */
    suspend fun peek(card: Card): Peek? {
        synchronized(peekCache) { peekCache[card.id] }?.let { return it }
        val item = card.item ?: withContext(Dispatchers.IO) { items.get(card.id) } ?: return null
        val p: Peek = when (item.kind) {
            "movie" -> {
                val info = vodInfo(item.streamId)
                var backdrop = info?.backdrop ?: item.backdrop
                var plot = info?.plot ?: item.plot
                var rating = info?.rating?.takeIf { it > 0 } ?: item.rating
                var runtime = (info?.durationSecs ?: 0) / 60
                if (backdrop == null || plot == null) {
                    app.meta.lookup("movie", item.title, item.year, info?.tmdbId ?: item.tmdbId)?.let { m ->
                        backdrop = backdrop ?: m.backdrop
                        plot = plot ?: m.overview
                        if (rating <= 0) rating = m.tmdbRating
                        if (runtime == 0) runtime = m.runtimeMin
                    }
                }
                Peek(backdrop, plot, info?.genre ?: item.genre?.trim(',')?.replace(",", ", "), rating, runtime)
            }
            "series" -> {
                var backdrop = item.backdrop
                var plot = item.plot
                if (backdrop == null) {
                    app.meta.lookup("series", item.title, item.year, item.tmdbId)?.let { m ->
                        backdrop = m.backdrop
                        plot = plot ?: m.overview
                    }
                }
                Peek(backdrop, plot, item.genre?.trim(',')?.replace(",", ", "), item.rating, 0)
            }
            else -> Peek(null, null, null, 0.0, 0)
        }
        synchronized(peekCache) {
            if (peekCache.size > 300) peekCache.clear()
            peekCache[card.id] = p
        }
        return p
    }

    // ------------------------------------------------------------------ details

    private fun fresh(c: CacheEntry, maxAgeMs: Long) = System.currentTimeMillis() - c.savedAt < maxAgeMs

    suspend fun vodInfo(streamId: Long, network: Boolean = true): VodInfo? = withContext(Dispatchers.IO) {
        val id = "vod:$streamId"
        val c = app.db.cache().get(id)
        if (c != null && (fresh(c, 7L * 24 * 3600_000) || !network)) return@withContext Xtream.parseVodInfo(c.json)
        if (!network) return@withContext null
        val x = xtream() ?: return@withContext null
        try {
            val json = x.vodInfoJson(streamId)
            app.db.cache().put(CacheEntry(id, json, System.currentTimeMillis()))
            Xtream.parseVodInfo(json)
        } catch (e: Exception) {
            c?.let { Xtream.parseVodInfo(it.json) }
        }
    }

    suspend fun seriesInfo(streamId: Long): SeriesInfo = withContext(Dispatchers.IO) {
        val id = "series:$streamId"
        val c = app.db.cache().get(id)
        if (c != null && fresh(c, 6L * 3600_000)) {
            try {
                return@withContext Xtream.parseSeriesInfo(c.json)
            } catch (e: Exception) {
                // fall through and refetch
            }
        }
        val x = xtream() ?: throw XtreamException("Not signed in")
        try {
            val json = x.seriesInfoJson(streamId)
            val parsed = Xtream.parseSeriesInfo(json)
            app.db.cache().put(CacheEntry(id, json, System.currentTimeMillis()))
            parsed
        } catch (e: Exception) {
            if (c != null) Xtream.parseSeriesInfo(c.json) else throw e
        }
    }

    suspend fun movieDetails(id: String): MovieDetails? = withContext(Dispatchers.IO) {
        val item = items.get(id) ?: return@withContext null
        val versions = sortVersions(items.versions(item.groupKey)).ifEmpty { listOf(item) }
        val info = try {
            vodInfo(item.streamId)
        } catch (e: Exception) {
            null
        }
        val meta = try {
            app.meta.lookup("movie", item.title, item.year, info?.tmdbId ?: item.tmdbId)
        } catch (e: Exception) {
            null
        }
        val firstGenre = item.genre?.trim(',')?.split(',')?.firstOrNull()
        val similar = shelf(
            if (firstGenre != null) Filter("movie", genre = firstGenre, sort = Sort.Rating) else Filter("movie", categoryId = item.categoryId, sort = Sort.Rating),
        ).filter { it.groupKey != item.groupKey }.take(24)
        MovieDetails(
            item = item,
            versions = versions,
            info = info,
            meta = meta,
            progress = app.db.progress().get("mg:${item.groupKey}"),
            favorite = app.db.favorites().get(item.id) != null,
            similar = similar,
        )
    }

    suspend fun seriesDetails(id: String): SeriesDetails? = withContext(Dispatchers.IO) {
        val item = items.get(id) ?: return@withContext null
        val info = seriesInfo(item.streamId)
        val meta = try {
            app.meta.lookup("series", item.title, item.year.takeIf { it > 0 } ?: info.year, info.tmdbId ?: item.tmdbId)
        } catch (e: Exception) {
            null
        }
        val progress = app.db.progress().allFor(item.id).filter { it.episodeId != null }.associateBy { it.episodeId!! }
        SeriesDetails(item, info, meta, progress, resumeTarget(info, progress), app.db.favorites().get(item.id) != null)
    }

    private fun epLabel(e: EpisodeInfo) = "S${e.season} E${e.number}"

    private fun resumeTarget(info: SeriesInfo, progress: Map<String, Progress>): ResumeTarget? {
        val flat = info.seasons.filter { it.number > 0 }.flatMap { it.episodes }.ifEmpty { info.seasons.flatMap { it.episodes } }
        if (flat.isEmpty()) return null
        val latest = progress.values.maxByOrNull { it.updatedAt }
            ?: return ResumeTarget(flat.first(), 0, "Play ${epLabel(flat.first())}")
        val idx = flat.indexOfFirst { it.id == latest.episodeId }
        if (idx < 0) return ResumeTarget(flat.first(), 0, "Play ${epLabel(flat.first())}")
        val ep = flat[idx]
        return when {
            latest.upNext -> ResumeTarget(ep, 0, "Play ${epLabel(ep)}")
            !latest.finished && latest.positionMs > 30_000 -> ResumeTarget(ep, latest.positionMs, "Resume ${epLabel(ep)}")
            !latest.finished -> ResumeTarget(ep, 0, "Play ${epLabel(ep)}")
            idx + 1 < flat.size -> ResumeTarget(flat[idx + 1], 0, "Play ${epLabel(flat[idx + 1])}")
            else -> ResumeTarget(flat.first(), 0, "Watch again from ${epLabel(flat.first())}")
        }
    }

    // ------------------------------------------------------------------ play

    suspend fun moviePlay(itemId: String, fromStart: Boolean = false, versionId: String? = null): PlayRequest? =
        withContext(Dispatchers.IO) {
            val x = xtream() ?: return@withContext null
            val base = items.get(itemId) ?: return@withContext null
            val versions = sortVersions(items.versions(base.groupKey)).ifEmpty { listOf(base) }
            val chosen = versions.firstOrNull { it.id == versionId } ?: versions.first()
            val key = "mg:${base.groupKey}"
            val prog = app.db.progress().get(key)
            val start = if (fromStart || prog == null || prog.finished) 0L else prog.positionMs
            val info = vodInfo(chosen.streamId, network = false)
            PlayRequest(
                kind = "movie",
                itemId = chosen.id,
                progressKey = key,
                url = x.streamUrl("movie", chosen.streamId.toString(), chosen.ext),
                title = base.title,
                subtitle = listOfNotNull(base.year.takeIf { it > 0 }?.toString(), versionLabel(chosen)).joinToString(" · "),
                image = info?.backdrop ?: chosen.backdrop ?: chosen.poster,
                startMs = start,
                year = base.year,
                tmdbId = info?.tmdbId ?: chosen.tmdbId,
                sources = versions.map { Source(it.id, versionLabel(it), x.streamUrl("movie", it.streamId.toString(), it.ext)) },
                sourceId = chosen.id,
            )
        }

    suspend fun episodePlay(seriesItemId: String, episodeId: String, resume: Boolean = true): PlayRequest? =
        withContext(Dispatchers.IO) {
            val x = xtream() ?: return@withContext null
            val series = items.get(seriesItemId) ?: return@withContext null
            val info = seriesInfo(series.streamId)
            val flat = info.seasons.flatMap { it.episodes }
            val idx = flat.indexOfFirst { it.id == episodeId }
            if (idx < 0) return@withContext null
            val progress = app.db.progress().allFor(series.id).filter { it.episodeId != null }.associateBy { it.episodeId!! }
            fun req(ep: EpisodeInfo, start: Long) = PlayRequest(
                kind = "episode",
                itemId = series.id,
                progressKey = "ep:${ep.id}",
                url = x.streamUrl("episode", ep.id, ep.ext),
                title = series.title,
                subtitle = "${epLabel(ep)} · ${ep.title}",
                image = ep.image ?: info.backdrop ?: series.backdrop ?: series.poster,
                startMs = start,
                year = series.year.takeIf { it > 0 } ?: info.year,
                season = ep.season,
                episode = ep.number,
                episodeId = ep.id,
                tmdbId = info.tmdbId ?: series.tmdbId,
            )
            val p = progress[episodeId]
            val start = if (resume && p != null && !p.finished && !p.upNext) p.positionMs else 0L
            req(flat[idx], start).copy(queue = flat.drop(idx + 1).take(40).map { req(it, 0L) })
        }

    suspend fun livePlay(itemId: String, channelOrder: List<String>? = null): PlayRequest? = withContext(Dispatchers.IO) {
        val x = xtream() ?: return@withContext null
        val item = items.get(itemId) ?: return@withContext null
        val order = channelOrder ?: items.channels(item.categoryId).map { it.id }
        val cat = app.db.categories().get("live", item.categoryId)
        PlayRequest(
            kind = "live",
            itemId = item.id,
            progressKey = null,
            url = x.liveUrl(item.streamId, liveFormat()),
            title = item.title,
            subtitle = cat?.name,
            image = item.poster,
            channels = order,
        )
    }

    /** HLS unless the user picked MPEG-TS or the provider doesn't offer HLS for this account. */
    private fun liveFormat(): String {
        val formats = app.prefs.account?.formats.orEmpty()
        return if (cfg.liveFormat == "m3u8" && (formats.isEmpty() || formats.contains("m3u8"))) "m3u8" else "ts"
    }

    /** Opens whatever an id points at: movies and episodes resume, channels tune in. */
    suspend fun playAny(itemId: String, episodeId: String? = null, fromStart: Boolean = false, versionId: String? = null): PlayRequest? =
        when {
            itemId.startsWith("movie:") -> moviePlay(itemId, fromStart, versionId)
            itemId.startsWith("series:") -> {
                val epId = episodeId ?: seriesDetails(itemId)?.resume?.episode?.id
                if (epId == null) null else episodePlay(itemId, epId, !fromStart)
            }
            itemId.startsWith("live:") -> livePlay(itemId)
            else -> null
        }

    suspend fun resume(p: Progress): PlayRequest? =
        if (p.kind == "episode" && p.episodeId != null) episodePlay(p.itemId, p.episodeId, true)
        else moviePlay(p.itemId, false, p.itemId)

    suspend fun saveProgress(req: PlayRequest, positionMs: Long, durationMs: Long) = withContext(Dispatchers.IO) {
        val key = req.progressKey ?: return@withContext
        if (durationMs <= 0 && positionMs <= 0) return@withContext
        val finished = durationMs > 0 && positionMs >= durationMs * 0.92
        val now = System.currentTimeMillis()
        app.db.progress().upsert(
            Progress(
                id = key,
                kind = req.kind,
                itemId = req.itemId,
                episodeId = req.episodeId,
                title = req.title,
                subtitle = req.subtitle,
                image = req.image,
                positionMs = positionMs,
                durationMs = durationMs,
                updatedAt = now,
                season = req.season,
                episode = req.episode,
                finished = finished,
                upNext = false,
            ),
        )
        if (finished && req.kind == "episode") {
            val next = req.queue.firstOrNull() ?: return@withContext
            val nextKey = next.progressKey ?: return@withContext
            val existing = app.db.progress().get(nextKey)
            if (existing == null || existing.finished) {
                app.db.progress().clearUpNext(req.itemId)
                app.db.progress().upsert(
                    Progress(
                        id = nextKey, kind = "episode", itemId = next.itemId, episodeId = next.episodeId,
                        title = next.title, subtitle = next.subtitle, image = next.image,
                        positionMs = 0, durationMs = 0, updatedAt = now + 1, season = next.season,
                        episode = next.episode, finished = false, upNext = true,
                    ),
                )
            }
        }
    }

    suspend fun clearProgress(id: String) = withContext(Dispatchers.IO) { app.db.progress().delete(id) }

    // ------------------------------------------------------------------ live

    suspend fun liveCategories(): List<Category> = withContext(Dispatchers.IO) {
        val visible = items.visibleCategoryIds("live").toHashSet()
        val cats = app.db.categories().list("live").filter { it.id in visible }
        val favLive = app.db.favorites().all().any { it.itemId.startsWith("live:") }
        if (favLive) listOf(Category("live", FAV_CAT, "★ Favorites", null, -1, false)) + cats else cats
    }

    suspend fun channels(categoryId: String): List<Item> = withContext(Dispatchers.IO) {
        if (categoryId == FAV_CAT) favoriteItems().filter { it.kind == "live" } else items.channels(categoryId)
    }

    private val epgCache = HashMap<Long, Pair<Long, List<EpgEntry>>>()

    suspend fun epg(item: Item): List<EpgEntry> = withContext(Dispatchers.IO) {
        synchronized(epgCache) { epgCache[item.streamId] }?.let { (at, list) ->
            if (System.currentTimeMillis() - at < 5 * 60_000) return@withContext list
        }
        val list = try {
            xtream()?.shortEpg(item.streamId) ?: emptyList()
        } catch (e: Exception) {
            emptyList()
        }
        val now = System.currentTimeMillis() / 1000
        val upcoming = list.filter { it.end == 0L || it.end > now }
        synchronized(epgCache) { epgCache[item.streamId] = System.currentTimeMillis() to upcoming }
        upcoming
    }

    // ------------------------------------------------------------------ my list

    suspend fun favoriteItems(): List<Item> = withContext(Dispatchers.IO) {
        val favs = app.db.favorites().all()
        val byId = items.getAll(favs.map { it.itemId }).associateBy { it.id }
        favs.mapNotNull { byId[it.itemId] }.filter { !it.hidden }
    }

    suspend fun isFavorite(id: String): Boolean = withContext(Dispatchers.IO) { app.db.favorites().get(id) != null }

    suspend fun toggleFavorite(id: String): Boolean = withContext(Dispatchers.IO) {
        if (app.db.favorites().get(id) != null) {
            app.db.favorites().remove(id)
            false
        } else {
            app.db.favorites().add(Favorite(id, System.currentTimeMillis()))
            true
        }
    }

    // ------------------------------------------------------------------ library filter

    suspend fun filterOptions(): FilterOptions = withContext(Dispatchers.IO) {
        val langs = items.langCounts().map { (it.lang ?: "?") to it.n }
        val showAdult = cfg.showAdult
        val cats = listOf("movie", "series", "live").associateWith { kind ->
            val counts = items.categoryCounts(kind).associate { it.categoryId to it.n }
            app.db.categories().list(kind).filter { showAdult || !it.adult }.map { it to (counts[it.id] ?: 0) }
        }
        FilterOptions(langs, cats)
    }

    suspend fun setFilter(hiddenLangs: Set<String>, hiddenCats: Set<String>) {
        app.prefs.update { it.copy(hiddenLangs = hiddenLangs, hiddenCats = hiddenCats, filterChosen = true) }
        app.sync.applyFilter()
        synchronized(peekCache) { peekCache.clear() }
    }

    companion object {
        const val FAV_CAT = "__fav"
    }
}
