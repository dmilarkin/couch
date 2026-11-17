package app.couch.tv

import android.app.Application
import android.content.Intent
import app.couch.tv.data.Account
import app.couch.tv.data.CatalogSync
import app.couch.tv.data.CouchDb
import app.couch.tv.data.Creds
import app.couch.tv.data.MetaService
import app.couch.tv.data.PlayRequest
import app.couch.tv.data.Prefs
import app.couch.tv.data.Repo
import app.couch.tv.data.SubtitleService
import app.couch.tv.remote.NetUtil
import app.couch.tv.remote.NowPlaying
import app.couch.tv.remote.RemoteBus
import app.couch.tv.remote.RemoteCommand
import app.couch.tv.remote.RemoteServer
import coil.ImageLoader
import coil.ImageLoaderFactory
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

class CouchApp : Application(), ImageLoaderFactory {

    lateinit var prefs: Prefs
        private set
    lateinit var http: OkHttpClient
        private set
    lateinit var db: CouchDb
        private set
    lateinit var repo: Repo
        private set
    lateinit var sync: CatalogSync
        private set
    lateinit var meta: MetaService
        private set
    lateinit var subs: SubtitleService
        private set

    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val bus = RemoteBus()
    val nowPlaying = MutableStateFlow<NowPlaying?>(null)

    var server: RemoteServer? = null
        private set

    @Volatile
    var inForeground = false

    override fun onCreate() {
        super.onCreate()
        prefs = Prefs(this)
        http = OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(90, TimeUnit.SECONDS)
            .followRedirects(true)
            .followSslRedirects(true)
            .addInterceptor { chain ->
                val req = chain.request()
                val withUa = if (req.header("User-Agent") == null) {
                    req.newBuilder().header("User-Agent", prefs.config.value.userAgent).build()
                } else {
                    req
                }
                chain.proceed(withUa)
            }
            .build()
        db = CouchDb.create(this)
        repo = Repo(this)
        sync = CatalogSync(this)
        meta = MetaService(this)
        subs = SubtitleService(this)
        startServer()

        val stale = System.currentTimeMillis() - prefs.lastSync > 20L * 3600_000
        if (prefs.config.value.isConfigured && stale) sync.start()
    }

    private fun startServer() {
        for (port in listOf(8642, 8643, 8644, 8645)) {
            try {
                val s = RemoteServer(this, port)
                s.start(10_000, true)
                server = s
                return
            } catch (e: Exception) {
                // port busy, try the next one
            }
        }
    }

    /** The address the QR code points to, e.g. http://192.168.1.40:8642/?t=abc123 */
    fun remoteUrl(): String? {
        val ip = NetUtil.localIp() ?: return null
        val s = server ?: return null
        return "http://$ip:${s.listeningPort}/?t=${prefs.remoteToken}"
    }

    fun remoteHost(): String? {
        val ip = NetUtil.localIp() ?: return null
        val s = server ?: return null
        return "$ip:${s.listeningPort}"
    }

    fun signIn(creds: Creds, account: Account) {
        val changedServer = prefs.config.value.server != creds.server || prefs.config.value.username != creds.username
        prefs.update { it.copy(server = creds.server, username = creds.username, password = creds.password) }
        prefs.account = account
        if (changedServer) prefs.lastSync = 0L
        sync.start()
    }

    fun signOut() {
        prefs.signOut()
        scope.launch(Dispatchers.IO) { db.clearAllTables() }
    }

    /** Called by the phone remote. If the TV app is in the background we keep it and try to come to the front. */
    fun requestPlay(req: PlayRequest): Boolean {
        return if (inForeground) {
            bus.send(RemoteCommand.Play(req))
            true
        } else {
            bus.pending = req
            bus.pendingAt = System.currentTimeMillis()
            try {
                startActivity(Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            } catch (e: Exception) {
                // Android may block background launches; it will play when Couch is opened.
            }
            false
        }
    }

    override fun newImageLoader(): ImageLoader =
        ImageLoader.Builder(this)
            .okHttpClient(http)
            .crossfade(true)
            .build()
}
