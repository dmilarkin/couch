package app.couch.tv.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import app.couch.tv.CouchApp
import app.couch.tv.remote.RemoteCommand

@Composable
fun CouchRoot(app: CouchApp) {
    val cfg by app.prefs.config.collectAsState()
    val sync by app.sync.state.collectAsState()
    val nav = remember { Nav() }
    val lifecycle = LocalLifecycleOwner.current.lifecycle

    // Commands from the phone remote ("Play on TV", open a title).
    LaunchedEffect(lifecycle) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            app.bus.takePending()?.let { nav.play(it) }
            app.bus.commands.collect { c ->
                when (c) {
                    is RemoteCommand.Play -> nav.play(c.req)
                    is RemoteCommand.Open -> nav.open(c.itemId)
                    is RemoteCommand.Control -> Unit // handled by the player
                }
            }
        }
    }

    Box(Modifier.fillMaxSize().background(Bg)) {
        val firstSync = app.prefs.lastSync == 0L
        when {
            !cfg.isConfigured -> SetupScreen(app)
            firstSync && (sync.running || sync.error != null || sync.finishedAt == 0L) -> SyncScreen(app, sync)
            else -> {
                BackHandler(enabled = nav.stack.size > 1) { nav.pop() }
                when (val s = nav.top) {
                    Screen.Main -> MainScreen(app, nav, sync.running)
                    is Screen.Movie -> key(s.id) { MovieScreen(app, nav, s.id) }
                    is Screen.Show -> key(s.id) { SeriesScreen(app, nav, s.id) }
                    is Screen.Player -> key(s.req) { PlayerScreen(app, nav, s.req) }
                    Screen.LibraryFilter -> LibraryFilterScreen(app, nav)
                }
            }
        }
    }
}

@Composable
fun MainScreen(app: CouchApp, nav: Nav, syncing: Boolean) {
    BackHandler(enabled = nav.tab != Tab.Home) { nav.tab = Tab.Home }
    Box(Modifier.fillMaxSize()) {
        when (nav.tab) {
            Tab.Home -> HomeScreen(app, nav)
            Tab.Movies -> key("movie") { BrowseScreen(app, nav, "movie") }
            Tab.Series -> key("series") { BrowseScreen(app, nav, "series") }
            Tab.Live -> LiveScreen(app, nav)
            Tab.Search -> SearchScreen(app, nav)
            Tab.Settings -> SettingsScreen(app, nav)
        }
        TopBar(nav, syncing)
    }
}
