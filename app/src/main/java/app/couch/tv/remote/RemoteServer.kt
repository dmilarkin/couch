package app.couch.tv.remote

import app.couch.tv.CouchApp
import app.couch.tv.data.Card
import app.couch.tv.data.Category
import app.couch.tv.data.Creds
import app.couch.tv.data.Filter
import app.couch.tv.data.Item
import app.couch.tv.data.Sort
import app.couch.tv.data.Xtream
import fi.iki.elonen.NanoHTTPD
import fi.iki.elonen.NanoHTTPD.IHTTPSession
import fi.iki.elonen.NanoHTTPD.Method
import fi.iki.elonen.NanoHTTPD.Response
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject

/**
 * Tiny web server running on the TV. Your iPhone opens it (via the QR code) for setup,
 * browsing, "Play on TV" and remote control. Everything stays on your home network.
 */
class RemoteServer(private val app: CouchApp, port: Int) : NanoHTTPD(port) {

    override fun serve(session: IHTTPSession): Response = try {
        route(session)
    } catch (e: Throwable) {
        json(JSONObject().put("error", e.message ?: e.javaClass.simpleName), Response.Status.INTERNAL_ERROR)
    }

    private fun route(s: IHTTPSession): Response {
        val uri = s.uri ?: "/"
        if (!uri.startsWith("/api/")) return page()

        val token = s.headers["x-couch-token"] ?: s.parameters["t"]?.firstOrNull()
        if (token != app.prefs.remoteToken) {
            return json(JSONObject().put("error", "This phone isn't paired. Scan the QR code on the TV again."), Response.Status.UNAUTHORIZED)
        }
        val body: JSONObject = if (s.method == Method.POST) {
            val text = readBody(s)
            if (text.isBlank()) JSONObject() else JSONObject(text)
        } else {
            JSONObject()
        }
        fun q(name: String): String? = s.parameters[name]?.firstOrNull()?.takeIf { it.isNotBlank() }

        return when (uri) {
            "/api/state" -> json(state())
            "/api/setup" -> setup(body)
            "/api/settings" -> settings(body)
            "/api/home" -> json(home())
            "/api/browse" -> json(browse(q("kind") ?: "movie", q("cat"), q("lang"), q("decade")?.toIntOrNull(), q("genre"), q("sort"), q("offset")?.toIntOrNull() ?: 0))
            "/api/options" -> json(options(q("kind") ?: "movie"))
            "/api/search" -> json(search(q("q") ?: ""))
            "/api/item" -> json(item(q("id") ?: ""))
            "/api/play" -> play(body)
            "/api/control" -> control(body)
            "/api/favorite" -> json(JSONObject().put("favorite", runBlocking { app.repo.toggleFavorite(body.optString("id")) }))
            "/api/sync" -> {
                app.sync.start()
                json(JSONObject().put("ok", true))
            }
            "/api/filter" -> if (s.method == Method.POST) setFilter(body) else json(filter())
            else -> json(JSONObject().put("error", "Not found"), Response.Status.NOT_FOUND)
        }
    }

    // ------------------------------------------------------------------ plumbing

    private fun readBody(s: IHTTPSession): String {
        val len = s.headers["content-length"]?.toIntOrNull() ?: 0
        if (len <= 0) return ""
        val buf = ByteArray(len)
        var read = 0
        val input = s.inputStream
        while (read < len) {
            val r = input.read(buf, read, len - read)
            if (r < 0) break
            read += r
        }
        return String(buf, 0, read, Charsets.UTF_8)
    }

    private fun json(o: Any, status: Response.Status = Response.Status.OK): Response {
        val r = NanoHTTPD.newFixedLengthResponse(status, "application/json; charset=utf-8", o.toString())
        r.addHeader("Cache-Control", "no-store")
        return r
    }

    private fun page(): Response {
        val html = app.assets.open("web/index.html").use { it.readBytes().toString(Charsets.UTF_8) }
        val r = NanoHTTPD.newFixedLengthResponse(Response.Status.OK, "text/html; charset=utf-8", html)
        r.addHeader("Cache-Control", "no-store")
        return r
    }

    private fun err(msg: String) = JSONObject().put("error", msg)

    private fun itemJson(i: Item): JSONObject = JSONObject()
        .put("id", i.id)
        .put("kind", i.kind)
        .put("title", i.title)
        .put("year", i.year)
        .put("poster", i.poster ?: JSONObject.NULL)
        .put("backdrop", i.backdrop ?: JSONObject.NULL)
        .put("rating", i.rating)
        .put("lang", i.lang ?: JSONObject.NULL)
        .put("quality", i.quality ?: JSONObject.NULL)
        .put("genre", i.genre?.trim(',')?.replace(",", ", ") ?: JSONObject.NULL)
        .put("plot", i.plot ?: JSONObject.NULL)

    private fun cardJson(c: Card): JSONObject = JSONObject()
        .put("id", c.id)
        .put("title", c.title)
        .put("subtitle", c.subtitle ?: JSONObject.NULL)
        .put("image", c.image ?: JSONObject.NULL)
        .put("wide", c.wide)
        .put("progress", c.progress?.toDouble() ?: JSONObject.NULL)
        .put("episodeId", c.resume?.episodeId ?: JSONObject.NULL)
        .put("resume", c.resume != null)

    private fun arr(list: List<JSONObject>) = JSONArray().also { a -> list.forEach { a.put(it) } }

    // ------------------------------------------------------------------ endpoints

    private fun state(): JSONObject {
        val cfg = app.prefs.config.value
        val sync = app.sync.state.value
        val acct = app.prefs.account
        val np = app.nowPlaying.value
        val counts = runBlocking {
            JSONObject()
                .put("movie", app.db.items().visibleCount("movie"))
                .put("series", app.db.items().visibleCount("series"))
                .put("live", app.db.items().visibleCount("live"))
        }
        return JSONObject()
            .put("configured", cfg.isConfigured)
            .put("server", cfg.server)
            .put("username", cfg.username)
            .put("account", acct?.toJson() ?: JSONObject.NULL)
            .put("tvActive", app.inForeground)
            .put(
                "sync",
                JSONObject()
                    .put("running", sync.running)
                    .put("stage", sync.stage)
                    .put("movies", sync.movies)
                    .put("series", sync.series)
                    .put("live", sync.live)
                    .put("error", sync.error ?: JSONObject.NULL)
                    .put("lastSync", app.prefs.lastSync),
            )
            .put("counts", counts)
            .put(
                "settings",
                JSONObject()
                    .put("tmdb", cfg.tmdbKey.isNotBlank())
                    .put("omdb", cfg.omdbKey.isNotBlank())
                    .put("os", cfg.osKey.isNotBlank())
                    .put("osUser", cfg.osUser)
                    .put("subLang", cfg.subLang)
                    .put("prefLang", cfg.prefLang)
                    .put("userAgent", cfg.userAgent)
                    .put("liveFormat", cfg.liveFormat)
                    .put("autoplayNext", cfg.autoplayNext)
                    .put("showAdult", cfg.showAdult)
                    .put("filterChosen", cfg.filterChosen),
            )
            .put(
                "nowPlaying",
                if (np == null) {
                    JSONObject.NULL
                } else {
                    JSONObject()
                        .put("title", np.title)
                        .put("subtitle", np.subtitle ?: JSONObject.NULL)
                        .put("image", np.image ?: JSONObject.NULL)
                        .put("kind", np.kind)
                        .put("positionMs", np.positionMs)
                        .put("durationMs", np.durationMs)
                        .put("playing", np.playing)
                        .put("speed", np.speed.toDouble())
                        .put("hasNext", np.hasNext)
                },
            )
    }

    private fun setup(b: JSONObject): Response {
        val creds = Creds.from(b.optString("server"), b.optString("username"), b.optString("password"), b.optString("m3u"))
            ?: return json(err("Enter the server, username and password, or paste your M3U link."))
        val account = try {
            Xtream(app.http, creds).login()
        } catch (e: Exception) {
            return json(err(e.message ?: "Couldn't reach the server"))
        }
        app.signIn(creds, account)
        return json(JSONObject().put("ok", true).put("account", account.toJson()))
    }

    private fun settings(b: JSONObject): Response {
        fun str(k: String): String? = if (b.has(k) && !b.isNull(k)) b.optString(k).trim() else null
        fun bool(k: String): Boolean? = if (b.has(k) && !b.isNull(k)) b.optBoolean(k) else null
        val adultBefore = app.prefs.config.value.showAdult
        app.prefs.update { c ->
            c.copy(
                tmdbKey = str("tmdbKey") ?: c.tmdbKey,
                omdbKey = str("omdbKey") ?: c.omdbKey,
                osKey = str("osKey") ?: c.osKey,
                osUser = str("osUser") ?: c.osUser,
                osPass = str("osPass") ?: c.osPass,
                subLang = str("subLang")?.takeIf { it.isNotEmpty() } ?: c.subLang,
                prefLang = str("prefLang")?.takeIf { it.isNotEmpty() } ?: c.prefLang,
                userAgent = str("userAgent")?.takeIf { it.isNotEmpty() } ?: c.userAgent,
                liveFormat = str("liveFormat")?.takeIf { it == "ts" || it == "m3u8" } ?: c.liveFormat,
                autoplayNext = bool("autoplayNext") ?: c.autoplayNext,
                showAdult = bool("showAdult") ?: c.showAdult,
            )
        }
        if (b.has("osUser") || b.has("osPass")) app.prefs.osToken = null
        if (adultBefore != app.prefs.config.value.showAdult) app.sync.start()
        return json(JSONObject().put("ok", true).put("state", state()))
    }

    private fun home(): JSONObject = runBlocking {
        val rows = app.repo.homeRows()
        JSONObject().put(
            "rows",
            arr(rows.map { r -> JSONObject().put("key", r.key).put("title", r.title).put("wide", r.wide).put("cards", arr(r.cards.map(::cardJson))) }),
        )
    }

    private fun browse(kind: String, cat: String?, lang: String?, decade: Int?, genre: String?, sort: String?, offset: Int): JSONObject = runBlocking {
        val f = Filter(
            kind = kind,
            categoryId = cat,
            lang = lang,
            decade = decade,
            genre = genre,
            sort = Sort.entries.firstOrNull { it.name == sort } ?: Sort.Recent,
        )
        val list = app.repo.browse(f, offset, 60)
        JSONObject().put("items", arr(list.map(::itemJson))).put("more", list.size == 60)
    }

    private fun options(kind: String): JSONObject = runBlocking {
        val o = app.repo.browseOptions(kind)
        JSONObject()
            .put("categories", arr(o.categories.map { JSONObject().put("id", it.id).put("name", it.name) }))
            .put("langs", JSONArray(o.langs))
            .put("decades", JSONArray(o.decades))
            .put("genres", JSONArray(o.genres))
            .put("sorts", arr(Sort.entries.map { JSONObject().put("id", it.name).put("label", it.label) }))
    }

    private fun search(q: String): JSONObject = runBlocking {
        val r = app.repo.search(q, 30)
        JSONObject()
            .put("movies", arr(r.movies.map(::itemJson)))
            .put("series", arr(r.series.map(::itemJson)))
            .put("live", arr(r.live.map(::itemJson)))
    }

    private fun item(id: String): JSONObject = runBlocking {
        when {
            id.startsWith("movie:") -> {
                val d = app.repo.movieDetails(id) ?: return@runBlocking err("Not found")
                JSONObject()
                    .put("item", itemJson(d.item))
                    .put("versions", arr(d.versions.map { JSONObject().put("id", it.id).put("label", app.repo.versionLabel(it)) }))
                    .put("plot", d.meta?.overview ?: d.info?.plot ?: d.item.plot ?: JSONObject.NULL)
                    .put("backdrop", d.meta?.backdrop ?: d.info?.backdrop ?: d.item.backdrop ?: JSONObject.NULL)
                    .put("cast", d.info?.cast ?: d.meta?.cast ?: JSONObject.NULL)
                    .put("director", d.info?.director ?: JSONObject.NULL)
                    .put("genre", d.info?.genre ?: d.meta?.genres?.joinToString(", ") ?: JSONObject.NULL)
                    .put("runtimeMin", (d.info?.durationSecs ?: 0).let { if (it > 0) it / 60 else d.meta?.runtimeMin ?: 0 })
                    .put("meta", d.meta?.toJson() ?: JSONObject.NULL)
                    .put("favorite", d.favorite)
                    .put(
                        "progress",
                        d.progress?.let { p ->
                            JSONObject().put("positionMs", p.positionMs).put("durationMs", p.durationMs).put("finished", p.finished)
                        } ?: JSONObject.NULL,
                    )
            }
            id.startsWith("series:") -> {
                val d = app.repo.seriesDetails(id) ?: return@runBlocking err("Not found")
                JSONObject()
                    .put("item", itemJson(d.item))
                    .put("plot", d.meta?.overview ?: d.info.plot ?: d.item.plot ?: JSONObject.NULL)
                    .put("backdrop", d.meta?.backdrop ?: d.info.backdrop ?: d.item.backdrop ?: JSONObject.NULL)
                    .put("cast", d.info.cast ?: d.meta?.cast ?: JSONObject.NULL)
                    .put("genre", d.info.genre ?: JSONObject.NULL)
                    .put("meta", d.meta?.toJson() ?: JSONObject.NULL)
                    .put("favorite", d.favorite)
                    .put(
                        "resume",
                        d.resume?.let { JSONObject().put("episodeId", it.episode.id).put("label", it.label).put("positionMs", it.positionMs) }
                            ?: JSONObject.NULL,
                    )
                    .put(
                        "seasons",
                        arr(
                            d.info.seasons.map { s ->
                                JSONObject()
                                    .put("number", s.number)
                                    .put("name", s.name)
                                    .put(
                                        "episodes",
                                        arr(
                                            s.episodes.map { e ->
                                                val p = d.progress[e.id]
                                                JSONObject()
                                                    .put("id", e.id)
                                                    .put("number", e.number)
                                                    .put("title", e.title)
                                                    .put("plot", e.plot ?: JSONObject.NULL)
                                                    .put("image", e.image ?: JSONObject.NULL)
                                                    .put("durationSecs", e.durationSecs)
                                                    .put("positionMs", p?.positionMs ?: 0L)
                                                    .put("finished", p?.finished == true)
                                            },
                                        ),
                                    )
                            },
                        ),
                    )
            }
            id.startsWith("live:") -> {
                val i = app.db.items().get(id) ?: return@runBlocking err("Not found")
                JSONObject().put("item", itemJson(i))
            }
            else -> err("Not found")
        }
    }

    private fun play(b: JSONObject): Response {
        val id = b.optString("id")
        val ep = b.optString("episodeId").takeIf { it.isNotBlank() && it != "null" }
        val version = b.optString("versionId").takeIf { it.isNotBlank() && it != "null" }
        val fromStart = b.optBoolean("fromStart", false)
        val req = runBlocking { app.repo.playAny(id, ep, fromStart, version) }
            ?: return json(err("Couldn't find that title"))
        val delivered = app.requestPlay(req)
        return json(
            JSONObject().put("ok", true).put("delivered", delivered).put(
                "note",
                if (delivered) "Playing on your TV" else "Open Couch on the TV and it will start playing",
            ),
        )
    }

    private fun control(b: JSONObject): Response {
        if (app.nowPlaying.value == null) return json(err("Nothing is playing on the TV right now"))
        app.bus.send(RemoteCommand.Control(b.optString("cmd"), b.optDouble("value", 0.0)))
        return json(JSONObject().put("ok", true))
    }

    private fun filter(): JSONObject = runBlocking {
        val cfg = app.prefs.config.value
        val o = app.repo.filterOptions()
        fun catJson(kind: String, list: List<Pair<Category, Int>>) = arr(
            list.map { (c, n) ->
                JSONObject().put("id", c.id).put("name", c.name).put("count", n).put("lang", c.lang ?: JSONObject.NULL)
                    .put("hidden", cfg.hiddenCats.contains("$kind:${c.id}"))
            },
        )
        JSONObject()
            .put(
                "langs",
                arr(o.langs.map { (l, n) -> JSONObject().put("lang", l).put("count", n).put("hidden", cfg.hiddenLangs.contains(l)) }),
            )
            .put(
                "categories",
                JSONObject()
                    .put("movie", catJson("movie", o.categories["movie"].orEmpty()))
                    .put("series", catJson("series", o.categories["series"].orEmpty()))
                    .put("live", catJson("live", o.categories["live"].orEmpty())),
            )
            .put("syncing", app.sync.state.value.running)
            .put("filterChosen", cfg.filterChosen)
    }

    private fun setFilter(b: JSONObject): Response {
        fun set(k: String): Set<String> {
            val a = b.optJSONArray(k) ?: return emptySet()
            return (0 until a.length()).map { a.optString(it) }.filter { it.isNotBlank() }.toSet()
        }
        runBlocking { app.repo.setFilter(set("hiddenLangs"), set("hiddenCats")) }
        return json(JSONObject().put("ok", true))
    }
}
