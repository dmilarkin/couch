package app.couch.tv.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Text
import app.couch.tv.CouchApp
import app.couch.tv.data.Row as DataRow
import app.couch.tv.data.SearchResult
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@Composable
fun SearchScreen(app: CouchApp, nav: Nav) {
    var query by remember { mutableStateOf(nav.lastQuery) }
    var results by remember { mutableStateOf<SearchResult?>(null) }
    val scope = rememberCoroutineScope()
    val keyboard = LocalSoftwareKeyboardController.current
    val focusManager = LocalFocusManager.current
    val fieldFocus = remember { FocusRequester() }

    LaunchedEffect(query) {
        nav.lastQuery = query
        if (query.isBlank()) {
            results = null
            return@LaunchedEffect
        }
        delay(300)
        results = app.repo.search(query)
    }
    LaunchedEffect(Unit) { if (query.isBlank()) fieldFocus.tryFocus(150) }

    Column(Modifier.fillMaxSize().padding(top = TopBarHeight + 8.dp)) {
        Row(Modifier.padding(horizontal = ScreenPad), verticalAlignment = Alignment.CenterVertically) {
            TvTextField(
                value = query,
                onValueChange = { query = it },
                label = "",
                placeholder = "Search movies, series and channels",
                imeAction = ImeAction.Search,
                onIme = {
                    keyboard?.hide()
                    focusManager.moveFocus(FocusDirection.Down)
                },
                modifier = Modifier.width(560.dp).focusRequester(fieldFocus),
            )
            val host = app.remoteHost()
            if (host != null) {
                Text(
                    "Typing is easier on your phone: open $host",
                    fontSize = 12.sp,
                    color = Faint,
                    modifier = Modifier.padding(start = 24.dp),
                )
            }
        }
        val r = results
        when {
            query.isBlank() -> MessageBox("Find anything", "Search by title. Results come from your whole library, minus anything you've hidden.")
            r == null -> Centered { Spinner() }
            r.isEmpty -> MessageBox("No matches for \"$query\"")
            else -> LazyColumn(Modifier.fillMaxWidth().padding(top = 18.dp), contentPadding = PaddingValues(bottom = 40.dp)) {
                val rows = listOf(
                    DataRow("s-movies", "Movies", r.movies.map { app.repo.cardOf(it) }),
                    DataRow("s-series", "Series", r.series.map { app.repo.cardOf(it) }),
                    DataRow("s-live", "Channels", r.live.map { app.repo.cardOf(it) }),
                ).filter { it.cards.isNotEmpty() }
                items(rows, key = { it.key }) { row ->
                    ContentRow(
                        row = row,
                        state = nav.list("search-${row.key}"),
                        restoreId = null,
                        restoreRequester = remember { FocusRequester() },
                        onFocus = {},
                        onClick = { c ->
                            if (c.id.startsWith("live:")) {
                                scope.launch { app.repo.livePlay(c.id)?.let { nav.play(it) } }
                            } else {
                                nav.open(c.id)
                            }
                        },
                    )
                }
            }
        }
    }
}
