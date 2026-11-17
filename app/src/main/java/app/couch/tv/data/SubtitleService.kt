package app.couch.tv.data

import app.couch.tv.CouchApp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.File

data class SubResult(
    val fileId: Long,
    val language: String,
    val release: String,
    val downloads: Int,
    val hearingImpaired: Boolean,
)

/** OpenSubtitles.com REST API. Needs a free API key; adding your account login raises the daily download limit. */
class SubtitleService(private val app: CouchApp) {

    private val base = "https://api.opensubtitles.com/api/v1"
    private val agent = "Couch v1.0"

    val configured: Boolean get() = app.prefs.config.value.osKey.isNotBlank()

    suspend fun search(req: PlayRequest, lang: String): List<SubResult> = withContext(Dispatchers.IO) {
        val key = app.prefs.config.value.osKey.trim()
        if (key.isEmpty()) throw IllegalStateException("Add a free OpenSubtitles API key in Settings on your phone first.")
        val attempts = mutableListOf<Map<String, String>>()
        val common = mutableMapOf("languages" to lang.lowercase(), "order_by" to "download_count", "order_direction" to "desc")
        if (req.kind == "episode") {
            common["type"] = "episode"
            common["season_number"] = req.season.toString()
            common["episode_number"] = req.episode.toString()
            req.tmdbId?.let { attempts += common + ("parent_tmdb_id" to it) }
            attempts += common + ("query" to req.title.lowercase())
        } else {
            common["type"] = "movie"
            req.tmdbId?.let { attempts += common + ("tmdb_id" to it) }
            attempts += common + ("query" to req.title.lowercase()) + (if (req.year > 0) mapOf("year" to req.year.toString()) else emptyMap())
        }
        for (params in attempts) {
            val found = query(key, params)
            if (found.isNotEmpty()) return@withContext found
        }
        emptyList()
    }

    private fun query(key: String, params: Map<String, String>): List<SubResult> {
        // The API wants parameters sorted alphabetically, otherwise it answers with a redirect.
        val b = "$base/subtitles".toHttpUrl().newBuilder()
        params.toSortedMap().forEach { (k, v) -> b.addQueryParameter(k, v) }
        val req = Request.Builder().url(b.build())
            .header("Api-Key", key)
            .header("User-Agent", agent)
            .header("Accept", "application/json")
            .build()
        val json = app.http.newCall(req).execute().use { r ->
            if (!r.isSuccessful) throw IllegalStateException("OpenSubtitles search failed (HTTP ${r.code}). Check the API key.")
            JSONObject(r.body?.string() ?: "{}")
        }
        val data = json.optJSONArray("data") ?: return emptyList()
        return (0 until data.length()).mapNotNull { i ->
            val a = data.optJSONObject(i)?.optJSONObject("attributes") ?: return@mapNotNull null
            val file = a.optJSONArray("files")?.optJSONObject(0) ?: return@mapNotNull null
            SubResult(
                fileId = file.optLong("file_id"),
                language = a.str("language") ?: "",
                release = a.str("release") ?: file.str("file_name") ?: "Subtitle",
                downloads = a.optInt("download_count"),
                hearingImpaired = a.optBoolean("hearing_impaired"),
            )
        }.filter { it.fileId > 0 }.take(30)
    }

    private fun token(key: String): String? {
        val cfg = app.prefs.config.value
        if (cfg.osUser.isBlank() || cfg.osPass.isBlank()) return null
        val cached = app.prefs.osToken
        if (cached != null && System.currentTimeMillis() - app.prefs.osTokenAt < 12L * 3600_000) return cached
        return try {
            val body = JSONObject().put("username", cfg.osUser).put("password", cfg.osPass).toString()
                .toRequestBody("application/json".toMediaType())
            val req = Request.Builder().url("$base/login").post(body)
                .header("Api-Key", key).header("User-Agent", agent).header("Accept", "application/json").build()
            app.http.newCall(req).execute().use { r ->
                if (!r.isSuccessful) return null
                JSONObject(r.body?.string() ?: "{}").str("token")?.also { app.prefs.osToken = it }
            }
        } catch (e: Exception) {
            null
        }
    }

    suspend fun download(fileId: Long): File = withContext(Dispatchers.IO) {
        val key = app.prefs.config.value.osKey.trim()
        val tok = token(key)
        val body = JSONObject().put("file_id", fileId).put("sub_format", "srt").toString()
            .toRequestBody("application/json".toMediaType())
        val rb = Request.Builder().url("$base/download").post(body)
            .header("Api-Key", key).header("User-Agent", agent).header("Accept", "application/json")
        if (tok != null) rb.header("Authorization", "Bearer $tok")
        val link = app.http.newCall(rb.build()).execute().use { r ->
            val text = r.body?.string() ?: "{}"
            val o = try {
                JSONObject(text)
            } catch (e: Exception) {
                JSONObject()
            }
            if (!r.isSuccessful) {
                val msg = o.str("message") ?: "HTTP ${r.code}"
                throw IllegalStateException("OpenSubtitles: $msg")
            }
            o.str("link") ?: throw IllegalStateException("OpenSubtitles didn't return a file")
        }
        val bytes = app.http.newCall(Request.Builder().url(link).header("User-Agent", agent).build()).execute().use { r ->
            if (!r.isSuccessful) throw IllegalStateException("Subtitle download failed (HTTP ${r.code})")
            r.body?.bytes() ?: ByteArray(0)
        }
        val f = File(app.cacheDir, "subs/$fileId.srt")
        f.parentFile?.mkdirs()
        f.writeBytes(bytes)
        f
    }
}
