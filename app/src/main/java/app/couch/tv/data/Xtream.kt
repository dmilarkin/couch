package app.couch.tv.data

import android.util.Base64
import android.util.JsonReader
import android.util.JsonToken
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener

data class Creds(val server: String, val username: String, val password: String) {
    companion object {
        /** Accepts either server + username + password, or an M3U link like http://host:8080/get.php?username=..&password=.. */
        fun from(server: String, username: String, password: String, m3u: String): Creds? {
            if (m3u.isNotBlank()) {
                val raw = m3u.trim().let { if (it.startsWith("http://") || it.startsWith("https://")) it else "http://$it" }
                val u = raw.toHttpUrlOrNull() ?: return null
                val user = u.queryParameter("username") ?: return null
                val pass = u.queryParameter("password") ?: return null
                return Creds("${u.scheme}://${u.host}:${u.port}", user, pass)
            }
            if (server.isBlank() || username.isBlank()) return null
            return Creds(normalizeServer(server), username.trim(), password.trim())
        }

        fun normalizeServer(input: String): String {
            var s = input.trim()
            if (!s.startsWith("http://") && !s.startsWith("https://")) s = "http://$s"
            val u = s.toHttpUrlOrNull() ?: return s.trimEnd('/')
            return "${u.scheme}://${u.host}:${u.port}"
        }
    }
}

class XtreamException(message: String) : Exception(message)

data class EpgEntry(val title: String, val description: String, val start: Long, val end: Long)

data class VodInfo(
    val plot: String?,
    val cast: String?,
    val director: String?,
    val genre: String?,
    val durationSecs: Int,
    val rating: Double,
    val backdrop: String?,
    val poster: String?,
    val trailer: String?,
    val tmdbId: String?,
    val releaseDate: String?,
    val country: String?,
)

data class EpisodeInfo(
    val id: String,
    val season: Int,
    val number: Int,
    val title: String,
    val ext: String?,
    val plot: String?,
    val image: String?,
    val durationSecs: Int,
    val rating: Double,
)

data class SeasonInfo(val number: Int, val name: String, val cover: String?, val episodes: List<EpisodeInfo>)

data class SeriesInfo(
    val plot: String?,
    val cast: String?,
    val director: String?,
    val genre: String?,
    val rating: Double,
    val backdrop: String?,
    val poster: String?,
    val trailer: String?,
    val tmdbId: String?,
    val year: Int,
    val seasons: List<SeasonInfo>,
)

/** Client for the Xtream Codes "player_api.php" API that almost every IPTV provider exposes. */
class Xtream(private val http: OkHttpClient, val creds: Creds) {

    private val root = creds.server.trimEnd('/')
    private val base: HttpUrl = "$root/".toHttpUrlOrNull() ?: throw XtreamException("That server address doesn't look right")

    fun apiUrl(action: String?, extra: Map<String, String> = emptyMap()): HttpUrl {
        val b = base.newBuilder()
            .addPathSegment("player_api.php")
            .addQueryParameter("username", creds.username)
            .addQueryParameter("password", creds.password)
        if (action != null) b.addQueryParameter("action", action)
        extra.forEach { (k, v) -> b.addQueryParameter(k, v) }
        return b.build()
    }

    /** Opens a streaming response. Caller must close it. */
    fun open(action: String?, extra: Map<String, String> = emptyMap()): Response {
        val resp = http.newCall(Request.Builder().url(apiUrl(action, extra)).build()).execute()
        if (!resp.isSuccessful) {
            val code = resp.code
            resp.close()
            throw XtreamException(
                when (code) {
                    401, 403 -> "The server refused the login (HTTP $code). Check the username and password, or try another User-Agent in Settings."
                    404 -> "Server found, but it doesn't speak the Xtream API (HTTP 404). Check the address and port."
                    else -> "Server error (HTTP $code)"
                },
            )
        }
        return resp
    }

    fun rawJson(action: String?, extra: Map<String, String> = emptyMap()): String =
        open(action, extra).use { it.body?.string() ?: "" }

    private fun json(action: String?, extra: Map<String, String> = emptyMap()): Any? {
        val text = rawJson(action, extra).trim()
        if (text.isEmpty()) return null
        return try {
            JSONTokener(text).nextValue()
        } catch (e: Exception) {
            throw XtreamException("The server sent something that isn't Xtream data. Check the address.")
        }
    }

    fun login(): Account {
        val obj = json(null) as? JSONObject ?: throw XtreamException("Unexpected answer from the server. Is this an Xtream Codes address?")
        val ui = obj.optJSONObject("user_info") ?: throw XtreamException("Unexpected answer from the server. Is this an Xtream Codes address?")
        if (ui.optInt("auth", 0) != 1) throw XtreamException("Wrong username or password")
        val status = ui.str("status") ?: "Active"
        if (!status.equals("Active", ignoreCase = true)) throw XtreamException("Your subscription status is \"$status\"")
        val formats = ui.optJSONArray("allowed_output_formats")
        return Account(
            status = status,
            expires = ui.str("exp_date")?.toLongOrNull(),
            maxConnections = ui.str("max_connections")?.toIntOrNull() ?: 1,
            activeConnections = ui.str("active_cons")?.toIntOrNull() ?: 0,
            formats = if (formats == null) emptyList() else (0 until formats.length()).map { formats.optString(it) },
        )
    }

    fun categories(action: String): List<Pair<String, String>> {
        val arr = json(action) as? JSONArray ?: return emptyList()
        return (0 until arr.length()).mapNotNull { i ->
            val o = arr.optJSONObject(i) ?: return@mapNotNull null
            val id = o.str("category_id") ?: return@mapNotNull null
            id to (o.str("category_name") ?: "Category $id")
        }
    }

    fun vodInfoJson(vodId: Long): String = rawJson("get_vod_info", mapOf("vod_id" to vodId.toString()))

    fun seriesInfoJson(seriesId: Long): String = rawJson("get_series_info", mapOf("series_id" to seriesId.toString()))

    fun shortEpg(streamId: Long): List<EpgEntry> {
        val obj = json("get_short_epg", mapOf("stream_id" to streamId.toString(), "limit" to "6")) as? JSONObject ?: return emptyList()
        val arr = obj.optJSONArray("epg_listings") ?: return emptyList()
        return (0 until arr.length()).mapNotNull { i ->
            val e = arr.optJSONObject(i) ?: return@mapNotNull null
            EpgEntry(
                title = b64(e.str("title")),
                description = b64(e.str("description")),
                start = e.str("start_timestamp")?.toLongOrNull() ?: 0L,
                end = (e.str("stop_timestamp") ?: e.str("end_timestamp"))?.toLongOrNull() ?: 0L,
            )
        }
    }

    fun streamUrl(kind: String, id: String, ext: String?): String {
        val e = ext?.trim()?.trimStart('.')?.takeIf { it.isNotEmpty() } ?: "mp4"
        val path = when (kind) {
            "movie" -> "movie"
            "episode" -> "series"
            else -> "live"
        }
        return "$root/$path/${creds.username}/${creds.password}/$id.$e"
    }

    fun liveUrl(id: Long, format: String): String =
        "$root/live/${creds.username}/${creds.password}/$id.${if (format == "ts") "ts" else "m3u8"}"

    companion object {
        private fun b64(s: String?): String {
            if (s.isNullOrBlank()) return ""
            return try {
                String(Base64.decode(s, Base64.DEFAULT), Charsets.UTF_8)
            } catch (e: Exception) {
                s
            }
        }

        fun parseVodInfo(json: String): VodInfo? {
            val root = try {
                JSONObject(json)
            } catch (e: Exception) {
                return null
            }
            val i = root.optJSONObject("info") ?: return null
            val dur = i.str("duration_secs")?.toIntOrNull()
                ?: parseDuration(i.str("duration"))
                ?: i.str("episode_run_time")?.toIntOrNull()?.times(60)
                ?: 0
            return VodInfo(
                plot = i.str("plot") ?: i.str("description"),
                cast = i.str("cast") ?: i.str("actors"),
                director = i.str("director"),
                genre = i.str("genre"),
                durationSecs = dur,
                rating = TitleParser.rating(i.str("rating"), i.str("rating_5based")),
                backdrop = i.firstUrl("backdrop_path"),
                poster = i.str("movie_image")?.takeIf { it.startsWith("http") } ?: i.str("cover_big")?.takeIf { it.startsWith("http") },
                trailer = i.str("youtube_trailer"),
                tmdbId = i.str("tmdb_id")?.takeIf { it != "0" },
                releaseDate = i.str("releasedate") ?: i.str("release_date"),
                country = i.str("country"),
            )
        }

        fun parseSeriesInfo(json: String): SeriesInfo {
            val root = JSONObject(json)
            val info = root.optJSONObject("info") ?: JSONObject()
            val seasonMeta = HashMap<Int, Pair<String?, String?>>()
            root.optJSONArray("seasons")?.let { arr ->
                for (i in 0 until arr.length()) {
                    val s = arr.optJSONObject(i) ?: continue
                    val n = s.str("season_number")?.toIntOrNull() ?: continue
                    val cover = s.str("cover_big")?.takeIf { it.startsWith("http") } ?: s.str("cover")?.takeIf { it.startsWith("http") }
                    seasonMeta[n] = s.str("name") to cover
                }
            }
            val bySeason = sortedMapOf<Int, MutableList<EpisodeInfo>>()
            fun addEpisode(o: JSONObject, seasonKey: Int?) {
                val id = o.str("id") ?: return
                val season = o.str("season")?.toIntOrNull() ?: seasonKey ?: 1
                val ei = o.optJSONObject("info")
                val number = o.str("episode_num")?.toIntOrNull() ?: ((bySeason[season]?.size ?: 0) + 1)
                val rawTitle = o.str("title") ?: ei?.str("name") ?: ""
                val dur = ei?.str("duration_secs")?.toIntOrNull() ?: parseDuration(ei?.str("duration")) ?: 0
                bySeason.getOrPut(season) { mutableListOf() } += EpisodeInfo(
                    id = id,
                    season = season,
                    number = number,
                    title = cleanEpisodeTitle(rawTitle, number),
                    ext = o.str("container_extension"),
                    plot = ei?.str("plot"),
                    image = ei?.str("movie_image")?.takeIf { it.startsWith("http") },
                    durationSecs = dur,
                    rating = TitleParser.rating(ei?.str("rating"), null),
                )
            }
            when (val eps = root.opt("episodes")) {
                is JSONObject -> {
                    val keys = eps.keys()
                    while (keys.hasNext()) {
                        val k = keys.next()
                        val list = eps.optJSONArray(k) ?: continue
                        for (i in 0 until list.length()) list.optJSONObject(i)?.let { addEpisode(it, k.toIntOrNull()) }
                    }
                }
                is JSONArray -> {
                    for (i in 0 until eps.length()) {
                        when (val v = eps.opt(i)) {
                            is JSONArray -> for (j in 0 until v.length()) v.optJSONObject(j)?.let { addEpisode(it, null) }
                            is JSONObject -> addEpisode(v, null)
                        }
                    }
                }
            }
            val seasons = bySeason.map { (n, list) ->
                val meta = seasonMeta[n]
                SeasonInfo(
                    number = n,
                    name = meta?.first?.takeIf { it.isNotBlank() } ?: if (n == 0) "Specials" else "Season $n",
                    cover = meta?.second,
                    episodes = list.sortedBy { it.number },
                )
            }
            return SeriesInfo(
                plot = info.str("plot"),
                cast = info.str("cast"),
                director = info.str("director"),
                genre = info.str("genre"),
                rating = TitleParser.rating(info.str("rating"), info.str("rating_5based")),
                backdrop = info.firstUrl("backdrop_path"),
                poster = info.str("cover")?.takeIf { it.startsWith("http") },
                trailer = info.str("youtube_trailer"),
                tmdbId = (info.str("tmdb") ?: info.str("tmdb_id"))?.takeIf { it != "0" },
                year = TitleParser.yearFrom(info.str("releaseDate") ?: info.str("release_date")) ?: 0,
                seasons = seasons,
            )
        }

        private fun cleanEpisodeTitle(raw: String, number: Int): String {
            val t = raw.replace(Regex("^.*?S\\d{1,2}\\s*E\\d{1,3}\\s*[-:.]*\\s*", RegexOption.IGNORE_CASE), "").trim()
            return t.ifBlank { "Episode $number" }
        }

        private fun parseDuration(s: String?): Int? {
            if (s.isNullOrBlank()) return null
            val parts = s.split(":").mapNotNull { it.trim().toIntOrNull() }
            return when (parts.size) {
                3 -> parts[0] * 3600 + parts[1] * 60 + parts[2]
                2 -> parts[0] * 60 + parts[1]
                else -> null
            }
        }
    }
}

/** org.json returns the string "null" for JSON nulls, which is never what we want. */
fun JSONObject.str(key: String): String? {
    if (isNull(key)) return null
    val v = opt(key)
    if (v is JSONArray || v is JSONObject) return null
    return optString(key).trim().takeIf { it.isNotEmpty() && it != "null" }
}

fun JSONObject.firstUrl(key: String): String? = when (val v = opt(key)) {
    is JSONArray -> (0 until v.length()).map { v.optString(it) }.firstOrNull { it.startsWith("http") }
    is String -> v.takeIf { it.startsWith("http") }
    else -> null
}

/** Reads any JSON value as a flat string. Arrays give their first element; objects are skipped. */
fun JsonReader.readAny(): String? = when (peek()) {
    JsonToken.NULL -> {
        nextNull()
        null
    }
    JsonToken.STRING, JsonToken.NUMBER -> nextString()
    JsonToken.BOOLEAN -> nextBoolean().toString()
    JsonToken.BEGIN_ARRAY -> {
        beginArray()
        var first: String? = null
        while (hasNext()) {
            val v = readAny()
            if (first == null && !v.isNullOrBlank()) first = v
        }
        endArray()
        first
    }
    else -> {
        skipValue()
        null
    }
}

fun JsonReader.readFlatObject(): Map<String, String?> {
    val m = HashMap<String, String?>(40)
    beginObject()
    while (hasNext()) {
        val k = nextName()
        m[k] = readAny()
    }
    endObject()
    return m
}
