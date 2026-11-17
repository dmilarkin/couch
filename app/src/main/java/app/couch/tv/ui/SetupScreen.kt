package app.couch.tv.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Text
import app.couch.tv.CouchApp
import app.couch.tv.data.CatalogSync
import app.couch.tv.data.Creds
import app.couch.tv.data.Xtream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** First run: scan a QR code with your iPhone and sign in there, or type it with the remote. */
@Composable
fun SetupScreen(app: CouchApp) {
    var manual by remember { mutableStateOf(false) }
    var url by remember { mutableStateOf(app.remoteUrl()) }
    LaunchedEffect(Unit) {
        while (true) {
            url = app.remoteUrl()
            delay(5000)
        }
    }

    if (manual) {
        ManualLogin(app) { manual = false }
        return
    }

    val btn = remember { FocusRequester() }
    LaunchedEffect(Unit) { btn.tryFocus(200) }

    Row(
        Modifier.fillMaxSize().background(Bg).padding(horizontal = 80.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f).padding(end = 48.dp)) {
            Text("Couch", fontSize = 52.sp, fontWeight = FontWeight.Bold, color = Accent)
            Spacer(Modifier.height(10.dp))
            Text("Let's connect your IPTV service.", fontSize = 24.sp, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(18.dp))
            Text(
                "Point your phone's camera at the code and tap the link, or type the address below into its browser. " +
                    "Type your login on the phone, which beats typing with a remote. Your phone and this TV need to be on the same Wi-Fi.",
                fontSize = 16.sp,
                color = Muted,
                lineHeight = 24.sp,
            )
            Spacer(Modifier.height(14.dp))
            Text(url?.substringBefore("?") ?: "Waiting for a network connection…", fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(28.dp))
            ActionButton("Type it on the TV instead", Modifier.focusRequester(btn)) { manual = true }
        }
        QrImage(url, 280.dp)
    }
}

@Composable
private fun ManualLogin(app: CouchApp, onBack: () -> Unit) {
    var server by remember { mutableStateOf("") }
    var user by remember { mutableStateOf("") }
    var pass by remember { mutableStateOf("") }
    var m3u by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    val first = remember { FocusRequester() }
    LaunchedEffect(Unit) { first.tryFocus(200) }
    androidx.activity.compose.BackHandler { onBack() }

    fun submit() {
        if (busy) return
        val creds = Creds.from(server, user, pass, m3u)
        if (creds == null) {
            error = "Fill in the server, username and password, or paste an M3U link."
            return
        }
        busy = true
        error = null
        scope.launch {
            try {
                val acct = withContext(Dispatchers.IO) { Xtream(app.http, creds).login() }
                app.signIn(creds, acct)
            } catch (e: Exception) {
                error = e.message ?: "Couldn't reach the server"
            } finally {
                busy = false
            }
        }
    }

    Box(Modifier.fillMaxSize().background(Bg), contentAlignment = Alignment.TopCenter) {
        Column(
            Modifier.width(560.dp).padding(top = 40.dp).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Text("Sign in", fontSize = 30.sp, fontWeight = FontWeight.Bold)
            Text("Your provider gives you a server address with a port, a username and a password.", fontSize = 14.sp, color = Muted)
            TvTextField(server, { server = it }, "Server (e.g. http://example.com:8080)", Modifier.focusRequester(first))
            TvTextField(user, { user = it }, "Username")
            TvTextField(pass, { pass = it }, "Password", password = true)
            Text("Or paste the full M3U link instead", fontSize = 13.sp, color = Faint, modifier = Modifier.padding(top = 6.dp))
            TvTextField(m3u, { m3u = it }, "", placeholder = "http://host:port/get.php?username=…", imeAction = ImeAction.Done, onIme = { submit() })
            error?.let { Text(it, color = Danger, fontSize = 14.sp) }
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxWidth().padding(top = 6.dp, bottom = 40.dp)) {
                ActionButton(if (busy) "Checking…" else "Sign in", primary = true) { submit() }
                ActionButton("Back") { onBack() }
            }
        }
    }
}

/** Shown while the very first catalog download runs. */
@Composable
fun SyncScreen(app: CouchApp, state: CatalogSync.State) {
    Box(Modifier.fillMaxSize().background(Bg), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.width(620.dp)) {
            val err = state.error
            if (err != null && !state.running) {
                Text("Couldn't load your library", fontSize = 26.sp, fontWeight = FontWeight.Bold)
                Text(err, fontSize = 15.sp, color = Muted, modifier = Modifier.padding(top = 10.dp))
                val fr = remember { FocusRequester() }
                LaunchedEffect(Unit) { fr.tryFocus(100) }
                Row(Modifier.padding(top = 24.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    ActionButton("Try again", Modifier.focusRequester(fr), primary = true) { app.sync.start() }
                    ActionButton("Sign out") { app.signOut() }
                }
            } else {
                Spinner(52.dp)
                Spacer(Modifier.height(26.dp))
                Text("Building your library", fontSize = 26.sp, fontWeight = FontWeight.Bold)
                Text(state.stage.ifBlank { "Starting" } + "…", fontSize = 16.sp, color = Muted, modifier = Modifier.padding(top = 8.dp))
                Spacer(Modifier.height(18.dp))
                Text(
                    "%,d movies   ·   %,d series   ·   %,d channels".format(state.movies, state.series, state.live),
                    fontSize = 15.sp,
                    color = Faint,
                )
                Spacer(Modifier.height(26.dp))
                Text(
                    "Big providers take a minute or two. Meanwhile, on your phone you can choose which languages and categories to keep.",
                    fontSize = 14.sp,
                    color = Faint,
                )
            }
        }
    }
}
