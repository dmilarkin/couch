package app.couch.tv.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import app.couch.tv.CouchApp
import app.couch.tv.data.Category
import app.couch.tv.data.EpgEntry
import app.couch.tv.data.Item
import coil.compose.AsyncImage
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Live TV: categories on the left, channels in the middle, what's on now and next on the right. */
@Composable
fun LiveScreen(app: CouchApp, nav: Nav) {
    var cats by remember { mutableStateOf<List<Category>>(emptyList()) }
    var selectedCat by remember { mutableStateOf(nav.liveCategory) }
    var pendingCat by remember { mutableStateOf(nav.liveCategory) }
    var channels by remember { mutableStateOf<List<Item>>(emptyList()) }
    var focusedCh by remember { mutableStateOf<Item?>(null) }
    var epg by remember { mutableStateOf<List<EpgEntry>>(emptyList()) }
    var favNote by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    val sync by app.sync.state.collectAsState()

    LaunchedEffect(sync.finishedAt) {
        cats = app.repo.liveCategories()
        if (selectedCat == null || cats.none { it.id == selectedCat }) {
            selectedCat = cats.firstOrNull()?.id
            pendingCat = selectedCat
        }
    }
    LaunchedEffect(pendingCat) {
        delay(250)
        if (pendingCat != null) selectedCat = pendingCat
    }
    LaunchedEffect(selectedCat, sync.finishedAt) {
        val c = selectedCat ?: return@LaunchedEffect
        nav.liveCategory = c
        channels = app.repo.channels(c)
    }
    LaunchedEffect(focusedCh?.id) {
        epg = emptyList()
        val ch = focusedCh ?: return@LaunchedEffect
        delay(400)
        epg = app.repo.epg(ch)
    }
    LaunchedEffect(favNote) {
        if (favNote != null) {
            delay(2000)
            favNote = null
        }
    }

    if (cats.isEmpty()) {
        MessageBox("No live channels", if (sync.running) "Still loading your library…" else "Your provider has no live TV, or it's hidden by the library filter.")
        return
    }

    Row(Modifier.fillMaxSize().padding(top = TopBarHeight + 6.dp, start = ScreenPad, end = ScreenPad)) {
        LazyColumn(
            Modifier.width(230.dp).fillMaxHeight().focusRestorer(),
            verticalArrangement = Arrangement.spacedBy(2.dp),
            contentPadding = PaddingValues(bottom = 30.dp),
        ) {
            items(cats, key = { it.id }) { c ->
                ListRow(
                    text = c.name,
                    selected = c.id == selectedCat,
                    onFocus = { pendingCat = c.id },
                    onClick = { selectedCat = c.id },
                )
            }
        }
        Spacer(Modifier.width(18.dp))
        LazyColumn(
            Modifier.weight(1f).fillMaxHeight().focusRestorer(),
            verticalArrangement = Arrangement.spacedBy(2.dp),
            contentPadding = PaddingValues(bottom = 30.dp),
        ) {
            items(channels, key = { it.id }) { ch ->
                ChannelRow(
                    ch = ch,
                    onFocus = { focusedCh = ch },
                    onLongClick = {
                        scope.launch {
                            val on = app.repo.toggleFavorite(ch.id)
                            favNote = if (on) "Added ${ch.title} to Favorites" else "Removed ${ch.title} from Favorites"
                        }
                    },
                    onClick = {
                        scope.launch { app.repo.livePlay(ch.id, channels.map { it.id })?.let { nav.play(it) } }
                    },
                )
            }
        }
        Spacer(Modifier.width(22.dp))
        Column(Modifier.width(300.dp).fillMaxHeight().padding(top = 8.dp)) {
            val ch = focusedCh
            if (ch != null) {
                Box(Modifier.size(width = 120.dp, height = 68.dp).background(CardBg, RoundedCornerShape(10.dp)), contentAlignment = Alignment.Center) {
                    if (ch.poster != null) {
                        AsyncImage(model = ch.poster, contentDescription = ch.title, contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize().padding(8.dp))
                    } else {
                        Text(ch.title.take(3), fontSize = 20.sp, fontWeight = FontWeight.Bold)
                    }
                }
                Text(ch.title, fontSize = 20.sp, fontWeight = FontWeight.Bold, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 12.dp))
                Spacer(Modifier.height(14.dp))
                if (epg.isEmpty()) {
                    Text("No guide information", fontSize = 13.sp, color = Faint)
                } else {
                    epg.take(4).forEachIndexed { i, e ->
                        Text(
                            (if (i == 0) "Now  " else "") + timeRange(e),
                            fontSize = 12.sp,
                            color = if (i == 0) Accent else Muted,
                            modifier = Modifier.padding(top = if (i == 0) 0.dp else 12.dp),
                        )
                        Text(e.title, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        if (i == 0 && e.description.isNotBlank()) {
                            Text(e.description, fontSize = 13.sp, color = Muted, maxLines = 4, overflow = TextOverflow.Ellipsis, lineHeight = 18.sp, modifier = Modifier.padding(top = 4.dp))
                        }
                    }
                }
                Spacer(Modifier.weight(1f))
                Text("Hold OK to add to Favorites", fontSize = 12.sp, color = Faint, modifier = Modifier.padding(bottom = 24.dp))
            }
            favNote?.let { Text(it, fontSize = 13.sp, color = Accent, modifier = Modifier.padding(bottom = 8.dp)) }
        }
    }
}

private val hhmm = SimpleDateFormat("HH:mm", Locale.getDefault())

private fun timeRange(e: EpgEntry): String =
    if (e.start <= 0) "" else hhmm.format(Date(e.start * 1000)) + if (e.end > 0) " – " + hhmm.format(Date(e.end * 1000)) else ""

@Composable
private fun ListRow(text: String, selected: Boolean, onFocus: () -> Unit, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth().onFocusChanged { if (it.isFocused) onFocus() },
        shape = ClickableSurfaceDefaults.shape(shape = RoundedCornerShape(8.dp)),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = if (selected) Color.White.copy(alpha = 0.14f) else Color.Transparent,
            contentColor = if (selected) Color.White else Muted,
            focusedContainerColor = Color.White,
            focusedContentColor = Color.Black,
        ),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1.02f),
    ) {
        Text(
            text,
            fontSize = 14.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 9.dp),
        )
    }
}

@Composable
private fun ChannelRow(ch: Item, onFocus: () -> Unit, onLongClick: () -> Unit, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        onLongClick = onLongClick,
        modifier = Modifier.fillMaxWidth().onFocusChanged { if (it.isFocused) onFocus() },
        shape = ClickableSurfaceDefaults.shape(shape = RoundedCornerShape(8.dp)),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = Color.White.copy(alpha = 0.04f),
            contentColor = Color.White,
            focusedContainerColor = Color.White,
            focusedContentColor = Color.Black,
        ),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1.02f),
    ) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 7.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(if (ch.num > 0) ch.num.toString() else "", fontSize = 13.sp, modifier = Modifier.width(42.dp))
            Box(Modifier.size(width = 54.dp, height = 32.dp), contentAlignment = Alignment.Center) {
                if (ch.poster != null) {
                    AsyncImage(model = ch.poster, contentDescription = null, contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize())
                }
            }
            Text(ch.title, fontSize = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(start = 12.dp).weight(1f))
            ch.quality?.let { Text(it, fontSize = 11.sp, modifier = Modifier.padding(start = 8.dp)) }
        }
    }
}
