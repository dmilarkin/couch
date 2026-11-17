package app.couch.tv.data

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.json.JSONObject
import kotlin.random.Random

data class Config(
    val server: String = "",
    val username: String = "",
    val password: String = "",
    val tmdbKey: String = "",
    val omdbKey: String = "",
    val osKey: String = "",
    val osUser: String = "",
    val osPass: String = "",
    val subLang: String = "en",
    val prefLang: String = "English",
    val userAgent: String = DEFAULT_UA,
    val liveFormat: String = "m3u8",
    val autoplayNext: Boolean = true,
    val showAdult: Boolean = false,
    /** Languages switched off in the library filter. "?" means items with no language tag. */
    val hiddenLangs: Set<String> = emptySet(),
    /** Provider categories switched off, stored as "kind:categoryId" (e.g. "movie:42"). */
    val hiddenCats: Set<String> = emptySet(),
    /** Set once the user has been through the language picker after the first sync. */
    val filterChosen: Boolean = false,
    /** When on, only phones that scanned the QR code (which carries a secret) can use the remote. */
    val requirePairing: Boolean = false,
) {
    val isConfigured: Boolean get() = server.isNotBlank() && username.isNotBlank()

    fun isHidden(kind: String, categoryId: String, lang: String?): Boolean =
        hiddenCats.contains("$kind:$categoryId") || hiddenLangs.contains(lang ?: "?")
    fun creds(): Creds? = if (isConfigured) Creds(server, username, password) else null

    companion object {
        const val DEFAULT_UA = "VLC/3.0.20 LibVLC/3.0.20"
    }
}

data class Account(
    val status: String,
    val expires: Long?,
    val maxConnections: Int,
    val activeConnections: Int,
    val formats: List<String>,
) {
    fun toJson(): JSONObject = JSONObject()
        .put("status", status)
        .put("expires", expires ?: JSONObject.NULL)
        .put("maxConnections", maxConnections)
        .put("activeConnections", activeConnections)
        .put("formats", org.json.JSONArray(formats))

    companion object {
        fun fromJson(s: String?): Account? = try {
            if (s.isNullOrBlank()) null else {
                val o = JSONObject(s)
                val f = o.optJSONArray("formats")
                Account(
                    status = o.optString("status"),
                    expires = if (o.isNull("expires")) null else o.optLong("expires"),
                    maxConnections = o.optInt("maxConnections"),
                    activeConnections = o.optInt("activeConnections"),
                    formats = if (f == null) emptyList() else (0 until f.length()).map { f.optString(it) },
                )
            }
        } catch (e: Exception) {
            null
        }
    }
}

class Prefs(ctx: Context) {
    private val sp = ctx.getSharedPreferences("couch", Context.MODE_PRIVATE)
    private val _config = MutableStateFlow(load())
    val config: StateFlow<Config> = _config

    fun update(block: (Config) -> Config) {
        val c = block(_config.value)
        save(c)
        _config.value = c
    }

    private fun s(key: String, def: String = ""): String = sp.getString(key, def) ?: def

    private fun load() = Config(
        server = s("server"),
        username = s("username"),
        password = s("password"),
        tmdbKey = s("tmdbKey"),
        omdbKey = s("omdbKey"),
        osKey = s("osKey"),
        osUser = s("osUser"),
        osPass = s("osPass"),
        subLang = s("subLang", "en"),
        prefLang = s("prefLang", "English"),
        userAgent = s("userAgent", Config.DEFAULT_UA).ifBlank { Config.DEFAULT_UA },
        liveFormat = s("liveFormat", "m3u8"),
        autoplayNext = sp.getBoolean("autoplayNext", true),
        showAdult = sp.getBoolean("showAdult", false),
        hiddenLangs = (sp.getStringSet("hiddenLangs", null) ?: emptySet()).toSet(),
        hiddenCats = (sp.getStringSet("hiddenCats", null) ?: emptySet()).toSet(),
        filterChosen = sp.getBoolean("filterChosen", false),
        requirePairing = sp.getBoolean("requirePairing", false),
    )

    private fun save(c: Config) {
        sp.edit()
            .putString("server", c.server)
            .putString("username", c.username)
            .putString("password", c.password)
            .putString("tmdbKey", c.tmdbKey)
            .putString("omdbKey", c.omdbKey)
            .putString("osKey", c.osKey)
            .putString("osUser", c.osUser)
            .putString("osPass", c.osPass)
            .putString("subLang", c.subLang)
            .putString("prefLang", c.prefLang)
            .putString("userAgent", c.userAgent)
            .putString("liveFormat", c.liveFormat)
            .putBoolean("autoplayNext", c.autoplayNext)
            .putBoolean("showAdult", c.showAdult)
            .putStringSet("hiddenLangs", HashSet(c.hiddenLangs))
            .putStringSet("hiddenCats", HashSet(c.hiddenCats))
            .putBoolean("filterChosen", c.filterChosen)
            .putBoolean("requirePairing", c.requirePairing)
            .apply()
    }

    var lastSync: Long
        get() = sp.getLong("lastSync", 0L)
        set(v) {
            sp.edit().putLong("lastSync", v).apply()
        }

    var account: Account?
        get() = Account.fromJson(sp.getString("account", null))
        set(v) {
            sp.edit().putString("account", v?.toJson()?.toString()).apply()
        }

    var osToken: String?
        get() = sp.getString("osToken", null)
        set(v) {
            sp.edit().putString("osToken", v).putLong("osTokenAt", System.currentTimeMillis()).apply()
        }

    val osTokenAt: Long get() = sp.getLong("osTokenAt", 0L)

    /** Short secret embedded in the QR code so only your phone can control the TV. */
    val remoteToken: String by lazy {
        sp.getString("remoteToken", null) ?: run {
            val alphabet = "abcdefghjkmnpqrstuvwxyz23456789"
            val t = (1..8).map { alphabet[Random.nextInt(alphabet.length)] }.joinToString("")
            sp.edit().putString("remoteToken", t).apply()
            t
        }
    }

    fun signOut() {
        update { it.copy(server = "", username = "", password = "", hiddenCats = emptySet(), filterChosen = false) }
        lastSync = 0L
        account = null
    }
}
