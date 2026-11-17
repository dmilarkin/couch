package app.couch.tv.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Text
import app.couch.tv.BuildConfig
import app.couch.tv.CouchApp
import kotlinx.coroutines.launch
import java.text.DateFormat
import java.util.Date
import java.util.Locale

private enum class SettingsPicker { PrefLang, SubLang, Ua }

val SUB_LANGS = listOf(
    "en" to "English", "fr" to "French", "es" to "Spanish", "de" to "German", "it" to "Italian",
    "pt-PT" to "Portuguese", "pt-BR" to "Portuguese (Brazil)", "nl" to "Dutch", "el" to "Greek",
    "ar" to "Arabic", "tr" to "Turkish", "pl" to "Polish", "ru" to "Russian", "sv" to "Swedish",
    "no" to "Norwegian", "da" to "Danish", "fi" to "Finnish", "ro" to "Romanian", "hu" to "Hungarian",
    "cs" to "Czech", "he" to "Hebrew", "hi" to "Hindi", "ja" to "Japanese", "ko" to "Korean",
    "zh-CN" to "Chinese (simplified)", "fa" to "Persian", "vi" to "Vietnamese", "th" to "Thai",
)

private val USER_AGENTS = listOf(
    "VLC/3.0.20 LibVLC/3.0.20" to "VLC (default)",
    "IPTVSmartersPro" to "IPTV Smarters",
    "TiviMate/5.0.4 (Android 11)" to "TiviMate",
    "okhttp/4.12.0" to "Plain Android",
    "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0 Safari/537.36" to "Web browser",
)

@Composable
fun SettingsScreen(app: CouchApp, nav: Nav) {
    val cfg by app.prefs.config.collectAsState()
    val sync by app.sync.state.collectAsState()
    val scope = rememberCoroutineScope()
    var picker by remember { mutableStateOf<SettingsPicker?>(null) }
    var langs by remember { mutableStateOf<List<String>>(emptyList()) }
    var counts by remember { mutableStateOf(Triple(0, 0, 0)) }
    val url = app.remoteUrl()

    LaunchedEffect(sync.finishedAt) {
        langs = app.repo.browseOptions("movie").langs
        counts = Triple(app.db.items().visibleCount("movie"), app.db.items().visibleCount("series"), app.db.items().visibleCount("live"))
    }

    Row(Modifier.fillMaxSize().padding(top = TopBarHeight + 6.dp, start = ScreenPad, end = ScreenPad)) {
        LazyColumn(
            Modifier.weight(1f).fillMaxHeight(),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            contentPadding = PaddingValues(bottom = 40.dp),
        ) {
            item { Section("Library") }
            item {
                val last = app.prefs.lastSync
                val detail = if (sync.running) {
                    "Updating now: ${sync.stage}"
                } else {
                    "%,d movies · %,d series · %,d channels".format(counts.first, counts.second, counts.third) +
                        if (last > 0) " · updated ${DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(last))}" else ""
                }
                SettingRow("Refresh library", detail) { app.sync.start() }
            }
            item {
                val hidden = cfg.hiddenLangs.size + cfg.hiddenCats.size
                SettingRow("Library filter", if (hidden == 0) "Showing everything. Hide languages or categories you don't watch." else "$hidden languages or categories hidden") {
                    nav.push(Screen.LibraryFilter)
                }
            }
            item {
                SettingRow("Preferred language", "${cfg.prefLang}. Picked first when a movie has several versions, and gets audio priority.") {
                    picker = SettingsPicker.PrefLang
                }
            }
            item { Section("Playback") }
            item {
                ToggleRow("Play next episode automatically", null, cfg.autoplayNext) {
                    app.prefs.update { it.copy(autoplayNext = !it.autoplayNext) }
                }
            }
            item {
                SettingRow("Subtitle language", SUB_LANGS.firstOrNull { it.first == cfg.subLang }?.second ?: cfg.subLang) {
                    picker = SettingsPicker.SubLang
                }
            }
            item {
                ToggleRow(
                    "Live TV as HLS",
                    if (cfg.liveFormat == "m3u8") "On (.m3u8). Turn off to use MPEG-TS if channels stutter or won't start." else "Off, using MPEG-TS (.ts)",
                    cfg.liveFormat == "m3u8",
                ) {
                    app.prefs.update { it.copy(liveFormat = if (it.liveFormat == "m3u8") "ts" else "m3u8") }
                }
            }
            item {
                SettingRow("Player identity (User-Agent)", USER_AGENTS.firstOrNull { it.first == cfg.userAgent }?.second ?: cfg.userAgent) {
                    picker = SettingsPicker.Ua
                }
            }
            item {
                ToggleRow("Show adult categories", "Takes effect after the library refreshes", cfg.showAdult) {
                    app.prefs.update { it.copy(showAdult = !it.showAdult) }
                    app.sync.start()
                }
            }
            item { Section("Extras (add keys from your phone)") }
            item { InfoRow("Artwork and ratings (TMDB)", if (cfg.tmdbKey.isNotBlank()) "Connected" else "Not set up. A free key adds backdrops, plots and TMDB ratings.") }
            item { InfoRow("IMDb and Rotten Tomatoes (OMDb)", if (cfg.omdbKey.isNotBlank()) "Connected" else "Not set up (optional)") }
            item {
                val osDetail = when {
                    cfg.osKey.isBlank() -> "Not set up (optional)"
                    cfg.osUser.isNotBlank() -> "Connected as ${cfg.osUser}"
                    else -> "Connected (add your account login for more downloads per day)"
                }
                InfoRow("Online subtitles (OpenSubtitles)", osDetail)
            }
            item { Section("Account") }
            item {
                val a = app.prefs.account
                val exp = a?.expires?.let { DateFormat.getDateInstance(DateFormat.MEDIUM, Locale.getDefault()).format(Date(it * 1000)) }
                InfoRow(
                    "${cfg.username} @ ${cfg.server.removePrefix("http://").removePrefix("https://")}",
                    listOfNotNull(a?.status, exp?.let { "expires $it" }, a?.let { "${it.maxConnections} connection" + if (it.maxConnections == 1) "" else "s" }).joinToString(" · "),
                )
            }
            item {
                SettingRow("Sign out", "Removes the login and the downloaded library from this TV") {
                    scope.launch { app.signOut() }
                }
            }
            item {
                Text(
                    "Couch ${BuildConfig.VERSION_NAME}. This product uses the TMDB API but is not endorsed or certified by TMDB.",
                    fontSize = 12.sp,
                    color = Faint,
                    modifier = Modifier.padding(top = 16.dp),
                )
            }
        }
        Spacer(Modifier.width(40.dp))
        Column(Modifier.width(300.dp).padding(top = 10.dp)) {
            Text("Phone remote", fontSize = 20.sp, fontWeight = FontWeight.Bold)
            Text(
                "Scan with your iPhone camera to browse, search, pick what to watch and control playback. Same Wi-Fi as the TV.",
                fontSize = 13.sp,
                color = Muted,
                lineHeight = 19.sp,
                modifier = Modifier.padding(top = 6.dp, bottom = 16.dp),
            )
            QrImage(url, 240.dp)
            Text(url?.substringBefore("?") ?: "", fontSize = 12.sp, color = Faint, modifier = Modifier.padding(top = 10.dp))
            Spacer(Modifier.height(8.dp))
            Text("Tip: in Safari, Share > Add to Home Screen makes it feel like an app.", fontSize = 12.sp, color = Faint)
        }
    }

    when (picker) {
        SettingsPicker.PrefLang -> SidePanel(
            title = "Preferred language",
            options = (listOf(cfg.prefLang) + langs).distinct().map { Opt(it, it) },
            isSelected = { it == cfg.prefLang },
            onSelect = { v ->
                app.prefs.update { it.copy(prefLang = v) }
                picker = null
            },
            onDismiss = { picker = null },
        )
        SettingsPicker.SubLang -> SidePanel(
            title = "Subtitle language",
            options = SUB_LANGS.map { Opt(it.first, it.second) },
            isSelected = { it == cfg.subLang },
            onSelect = { v ->
                app.prefs.update { it.copy(subLang = v) }
                picker = null
            },
            onDismiss = { picker = null },
            message = "Used when searching subtitles online.",
        )
        SettingsPicker.Ua -> SidePanel(
            title = "Player identity",
            options = USER_AGENTS.map { Opt(it.first, it.second, it.first) },
            isSelected = { it == cfg.userAgent },
            onSelect = { v ->
                app.prefs.update { it.copy(userAgent = v) }
                picker = null
            },
            onDismiss = { picker = null },
            message = "Some providers only allow certain apps. If streams refuse to play, try another one.",
        )
        null -> Unit
    }
}

@Composable
private fun Section(title: String) {
    Text(title.uppercase(), fontSize = 12.sp, fontWeight = FontWeight.Bold, color = Faint, modifier = Modifier.padding(top = 14.dp, bottom = 2.dp))
}

@Composable
private fun SettingRow(label: String, detail: String?, onClick: () -> Unit) {
    OptionRow(label = label, detail = detail, checked = false, onClick = onClick)
}

@Composable
private fun InfoRow(label: String, detail: String) {
    Column(Modifier.padding(horizontal = 14.dp, vertical = 6.dp)) {
        Text(label, fontSize = 15.sp)
        Text(detail, fontSize = 12.sp, color = Muted)
    }
}
