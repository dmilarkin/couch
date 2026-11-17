package app.couch.tv.data

import app.couch.tv.CouchApp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject

/** Extra ratings and artwork. TMDB is the source; OMDb adds IMDb and Rotten Tomatoes scores. Both optional. */
data class MetaInfo(
    val tmdbId: String?,
    val overview: String?,
    val backdrop: String?,
    val poster: String?,
    val tmdbRating: Double,
    val votes: Int,
    val genres: List<String>,
    val runtimeMin: Int,
    val imdbId: String?,
    val imdbRating: String?,
    val rotten: String?,
    val metascore: String?,
    val tagline: String?,
    val cast: String?,
    val originalLanguage: String?,
) {
    fun toJson(): JSONObject = JSONObject()
        .put("tmdbId", tmdbId ?: JSONObject.NULL)
        .put("overview", overview ?: JSONObject.NULL)
        .put("backdrop", backdrop ?: JSONObject.NULL)
        .put("poster", poster ?: JSONObject.NULL)
        .put("tmdbRating", tmdbRating)
        .put("votes", votes)
        .put("genres", JSONArray(genres))
        .put("runtimeMin", runtimeMin)
        .put("imdbId", imdbId ?: JSONObject.NULL)
        .put("imdbRating", imdbRating ?: JSONObject.NULL)
        .put("rotten", rotten ?: JSONObject.NULL)
        .put("metascore", metascore ?: JSONObject.NULL)
        .put("tagline", tagline ?: JSONObject.NULL)
        .put("cast", cast ?: JSONObject.NULL)
        .put("originalLanguage", originalLanguage ?: JSONObject.NULL)

    companion object {
        fun fromJson(o: JSONObject): MetaInfo {
            val g = o.optJSONArray("genres")
            return MetaInfo(
                tmdbId = o.str("tmdbId"),
                overview = o.str("overview"),
                backdrop = o.str("backdrop"),
                poster = o.str("poster"),
                tmdbRating = o.optDouble("tmdbRating", 0.0),
                votes = o.optInt("votes"),
                genres = if (g == null) emptyList() else (0 until g.length()).map { g.optString(it) },
                runtimeMin = o.optInt("runtimeMin"),
                imdbId = o.str("imdbId"),
                imdbRating = o.str("imdbRating"),
                rotten = o.str("rotten"),
                metascore = o.str("metascore"),
                tagline = o.str("tagline"),
                cast = o.str("cast"),
                originalLanguage = o.str("originalLanguage"),
            )
        }
    }
}

class MetaService(private val app: CouchApp) {

    val hasTmdb: Boolean get() = app.prefs.config.value.tmdbKey.isNotBlank()

    /** kind is "movie" or "series". Returns null when no TMDB key is set or nothing matched. */
    suspend fun lookup(kind: String, title: String, year: Int, tmdbId: String?): MetaInfo? = withContext(Dispatchers.IO) {
        val cfg = app.prefs.config.value
        val key = cfg.tmdbKey.trim()
        if (key.isEmpty()) return@withContext null
        val cacheId = "meta:$kind:" + (tmdbId ?: "${TitleParser.normalize(title)}|$year")
        app.db.cache().get(cacheId)?.let { c ->
            if (System.currentTimeMillis() - c.savedAt < 14L * 24 * 3600_000) {
                return@withContext if (c.json == "{}") null else MetaInfo.fromJson(JSONObject(c.json))
            }
        }
        val type = if (kind == "movie") "movie" else "tv"
        val id = tmdbId?.takeIf { s -> s.all { it.isDigit() } } ?: searchId(key, type, title, year)
        if (id == null) {
            app.db.cache().put(CacheEntry(cacheId, "{}", System.currentTimeMillis()))
            return@withContext null
        }
        val d = get(key, "https://api.themoviedb.org/3/$type/$id", mapOf("append_to_response" to "external_ids,credits"))
            ?: return@withContext null
        val imdb = d.optJSONObject("external_ids")?.str("imdb_id") ?: d.str("imdb_id")
        val genres = d.optJSONArray("genres")?.let { arr -> (0 until arr.length()).mapNotNull { arr.optJSONObject(it)?.str("name") } } ?: emptyList()
        val runtime = if (type == "movie") d.optInt("runtime") else d.optJSONArray("episode_run_time")?.optInt(0) ?: 0
        val cast = d.optJSONObject("credits")?.optJSONArray("cast")?.let { arr ->
            (0 until minOf(5, arr.length())).mapNotNull { arr.optJSONObject(it)?.str("name") }.joinToString(", ")
        }
        var info = MetaInfo(
            tmdbId = id,
            overview = d.str("overview"),
            backdrop = d.str("backdrop_path")?.let { "https://image.tmdb.org/t/p/w1280$it" },
            poster = d.str("poster_path")?.let { "https://image.tmdb.org/t/p/w500$it" },
            tmdbRating = d.optDouble("vote_average", 0.0),
            votes = d.optInt("vote_count"),
            genres = genres,
            runtimeMin = runtime,
            imdbId = imdb,
            imdbRating = null,
            rotten = null,
            metascore = null,
            tagline = d.str("tagline"),
            cast = cast?.takeIf { it.isNotBlank() },
            originalLanguage = d.str("original_language"),
        )
        val omdb = cfg.omdbKey.trim()
        if (omdb.isNotEmpty() && imdb != null) {
            try {
                val url = "https://www.omdbapi.com/".toHttpUrl().newBuilder()
                    .addQueryParameter("i", imdb)
                    .addQueryParameter("apikey", omdb)
                    .build()
                val o = app.http.newCall(Request.Builder().url(url).build()).execute().use { r ->
                    if (r.isSuccessful) JSONObject(r.body?.string() ?: "{}") else null
                }
                if (o != null && o.optString("Response") == "True") {
                    var rt: String? = null
                    o.optJSONArray("Ratings")?.let { arr ->
                        for (i in 0 until arr.length()) {
                            val r = arr.optJSONObject(i) ?: continue
                            if (r.optString("Source") == "Rotten Tomatoes") rt = r.str("Value")
                        }
                    }
                    info = info.copy(
                        imdbRating = o.str("imdbRating")?.takeIf { it != "N/A" },
                        metascore = o.str("Metascore")?.takeIf { it != "N/A" },
                        rotten = rt,
                    )
                }
            } catch (e: Exception) {
                // OMDb is a bonus; ignore failures.
            }
        }
        app.db.cache().put(CacheEntry(cacheId, info.toJson().toString(), System.currentTimeMillis()))
        info
    }

    private fun searchId(key: String, type: String, title: String, year: Int): String? {
        val yearParam = if (type == "movie") "year" else "first_air_date_year"
        val attempts = if (year > 0) listOf(mapOf("query" to title, yearParam to year.toString()), mapOf("query" to title)) else listOf(mapOf("query" to title))
        for (params in attempts) {
            val res = get(key, "https://api.themoviedb.org/3/search/$type", params) ?: continue
            val first = res.optJSONArray("results")?.optJSONObject(0) ?: continue
            return first.optLong("id").takeIf { it > 0 }?.toString()
        }
        return null
    }

    private fun get(key: String, url: String, params: Map<String, String>): JSONObject? = try {
        val b = url.toHttpUrl().newBuilder()
        params.forEach { (k, v) -> b.addQueryParameter(k, v) }
        val rb = Request.Builder()
        // v4 "read access tokens" are long JWTs; v3 keys are short hex strings. Accept either.
        if (key.startsWith("eyJ")) rb.header("Authorization", "Bearer $key") else b.addQueryParameter("api_key", key)
        rb.url(b.build()).header("Accept", "application/json")
        app.http.newCall(rb.build()).execute().use { r ->
            if (r.isSuccessful) JSONObject(r.body?.string() ?: "{}") else null
        }
    } catch (e: Exception) {
        null
    }
}
