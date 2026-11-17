package app.couch.tv.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Text
import app.couch.tv.CouchApp
import app.couch.tv.data.Card
import app.couch.tv.data.Peek
import app.couch.tv.data.Row as DataRow
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** Apple TV style home: the focused poster's backdrop fills the screen, rows of shelves below. */
@Composable
fun HomeScreen(app: CouchApp, nav: Nav) {
    var rows by remember { mutableStateOf(nav.homeRows) }
    val sync by app.sync.state.collectAsState()
    val scope = rememberCoroutineScope()

    LaunchedEffect(sync.finishedAt) {
        val fresh = app.repo.homeRows()
        nav.homeRows = fresh
        rows = fresh
    }

    var focused by remember { mutableStateOf<Card?>(null) }
    var peek by remember { mutableStateOf<Peek?>(null) }
    LaunchedEffect(focused?.id) {
        val c = focused ?: return@LaunchedEffect
        peek = null
        delay(400)
        peek = try {
            app.repo.peek(c)
        } catch (e: Exception) {
            null
        }
    }

    val restore = remember { FocusRequester() }
    val memory = nav.focusMemory["home"]
    val restoreRowKey = memory?.substringBefore('|')
    val restoreCardId = memory?.substringAfter('|')

    LaunchedEffect(rows != null) {
        if (rows != null) restore.tryFocus(120)
    }

    Box(Modifier.fillMaxSize()) {
        val card = focused
        Backdrop(url = peek?.backdrop ?: card?.item?.backdrop ?: card?.let { c -> c.image?.takeIf { c.wide } })

        val list = rows
        when {
            list == null -> Centered { Spinner() }
            list.isEmpty() -> MessageBox(
                "Your library is empty",
                if (sync.running) "Still loading, hang on…" else "Check the library filter in Settings, or refresh the library.",
            )
            else -> Column(Modifier.fillMaxSize().padding(top = TopBarHeight)) {
                Hero(card, peek)
                LazyColumn(
                    state = nav.list("home"),
                    modifier = Modifier.fillMaxWidth().weight(1f),
                    contentPadding = PaddingValues(bottom = 40.dp),
                ) {
                    items(list, key = { it.key }) { row: DataRow ->
                        val restoreHere = if (memory == null) {
                            if (row.key == list.first().key) row.cards.firstOrNull()?.id else null
                        } else if (row.key == restoreRowKey) {
                            restoreCardId
                        } else {
                            null
                        }
                        ContentRow(
                            row = row,
                            state = nav.list("home-row-${row.key}"),
                            restoreId = restoreHere,
                            restoreRequester = restore,
                            onFocus = { c ->
                                focused = c
                                nav.focusMemory["home"] = "${row.key}|${c.id}"
                            },
                            onClick = { c ->
                                val p = c.resume
                                if (p != null) {
                                    scope.launch { app.repo.resume(p)?.let { nav.play(it) } }
                                } else if (c.id.startsWith("live:")) {
                                    scope.launch { app.repo.livePlay(c.id)?.let { nav.play(it) } }
                                } else {
                                    nav.open(c.id)
                                }
                            },
                            onLongClick = { c ->
                                // Long press on Continue Watching opens the details instead of resuming.
                                if (c.resume != null) nav.open(c.id)
                            },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun Hero(card: Card?, peek: Peek?) {
    Column(
        Modifier
            .fillMaxWidth()
            .height(196.dp)
            .padding(start = ScreenPad, top = 14.dp, end = ScreenPad),
    ) {
        if (card != null) {
            Text(
                card.title,
                fontSize = 30.sp,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.width(560.dp),
            )
            val item = card.item
            val bits = listOfNotNull(
                if (card.resume != null) card.subtitle else null,
                item?.year?.takeIf { it > 0 }?.toString(),
                peek?.runtimeMin?.let { formatRuntime(it) },
                (peek?.genre ?: item?.genre?.trim(',')?.replace(",", ", "))?.takeIf { it.isNotBlank() },
                ratingText(peek?.rating?.takeIf { it > 0 } ?: item?.rating ?: 0.0),
                item?.lang,
                item?.quality,
            )
            Row(Modifier.padding(top = 6.dp)) {
                Text(bits.joinToString("  ·  "), fontSize = 14.sp, color = Muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Spacer(Modifier.height(10.dp))
            val plot = peek?.plot ?: item?.plot
            if (plot != null) {
                Text(
                    plot,
                    fontSize = 14.sp,
                    color = Color.White.copy(alpha = 0.85f),
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                    lineHeight = 20.sp,
                    modifier = Modifier.width(520.dp),
                )
            }
        }
    }
}
