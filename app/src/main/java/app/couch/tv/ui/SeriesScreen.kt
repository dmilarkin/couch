package app.couch.tv.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Border
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import app.couch.tv.CouchApp
import app.couch.tv.data.EpisodeInfo
import app.couch.tv.data.Progress
import app.couch.tv.data.SeriesDetails
import coil.compose.AsyncImage
import kotlinx.coroutines.launch

@Composable
fun SeriesScreen(app: CouchApp, nav: Nav, id: String) {
    var d by remember { mutableStateOf<SeriesDetails?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var season by remember { mutableIntStateOf(-1) }
    var fav by remember { mutableStateOf(false) }
    var focusedEp by remember { mutableStateOf<EpisodeInfo?>(null) }
    val scope = rememberCoroutineScope()
    val ctx = LocalContext.current
    val playFocus = remember { FocusRequester() }

    LaunchedEffect(id) {
        try {
            val r = app.repo.seriesDetails(id)
            if (r == null) {
                error = "This series isn't in your library anymore."
            } else {
                d = r
                fav = r.favorite
                if (season < 0) season = r.resume?.episode?.season ?: r.info.seasons.firstOrNull { it.number > 0 }?.number ?: r.info.seasons.firstOrNull()?.number ?: 1
                playFocus.tryFocus(120)
            }
        } catch (e: Exception) {
            error = e.message ?: "Couldn't load episodes"
        }
    }

    val details = d
    if (details == null) {
        val e = error
        if (e != null) MessageBox("Couldn't load this series", e, "Back") { nav.pop() } else Centered { Spinner() }
        return
    }
    val item = details.item
    val info = details.info
    val meta = details.meta
    val seasons = info.seasons
    val current = seasons.firstOrNull { it.number == season } ?: seasons.firstOrNull()

    fun playEpisode(ep: EpisodeInfo, resume: Boolean = true) {
        scope.launch { app.repo.episodePlay(item.id, ep.id, resume)?.let { nav.play(it) } }
    }

    Box(Modifier.fillMaxSize()) {
        Backdrop(meta?.backdrop ?: info.backdrop ?: item.backdrop ?: info.poster ?: item.poster)
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(top = 64.dp, bottom = 40.dp)) {
            item {
                Column(Modifier.padding(horizontal = ScreenPad).width(620.dp)) {
                    Text(item.title, fontSize = 38.sp, fontWeight = FontWeight.Bold, maxLines = 2, overflow = TextOverflow.Ellipsis, lineHeight = 44.sp)
                    Spacer(Modifier.height(8.dp))
                    MetaLine(
                        listOfNotNull(
                            (item.year.takeIf { it > 0 } ?: info.year.takeIf { it > 0 })?.toString(),
                            "${seasons.count { it.number > 0 }.coerceAtLeast(1)} season" + if (seasons.count { it.number > 0 } > 1) "s" else "",
                            (info.genre ?: meta?.genres?.joinToString(", "))?.takeIf { it.isNotBlank() },
                            item.lang,
                        ),
                    )
                    RatingsLine(item.rating, info.rating, meta)
                    Spacer(Modifier.height(12.dp))
                    val plot = focusedEp?.plot ?: meta?.overview ?: info.plot ?: item.plot
                    if (plot != null) {
                        Text(plot, fontSize = 15.sp, lineHeight = 22.sp, maxLines = 4, overflow = TextOverflow.Ellipsis, color = Color.White.copy(alpha = 0.9f))
                    }
                }
            }
            item {
                Row(Modifier.padding(start = ScreenPad, top = 20.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    val r = details.resume
                    if (r != null) {
                        val label = if (r.positionMs > 0) "▶  ${r.label} · ${formatTime(r.positionMs)}" else "▶  ${r.label}"
                        ActionButton(label, Modifier.focusRequester(playFocus), primary = true) { playEpisode(r.episode, true) }
                    }
                    ActionButton(if (fav) "✓  My List" else "+  My List") {
                        scope.launch { fav = app.repo.toggleFavorite(item.id) }
                    }
                    val trailer = info.trailer
                    if (!trailer.isNullOrBlank()) ActionButton("Trailer") { openTrailer(ctx, trailer) }
                }
            }
            if (seasons.size > 1) {
                item {
                    LazyRow(
                        Modifier.padding(top = 24.dp),
                        contentPadding = PaddingValues(horizontal = ScreenPad),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        items(seasons, key = { it.number }) { s ->
                            ActionButton(
                                s.name,
                                small = true,
                                selected = s.number == current?.number,
                                onFocus = { season = s.number },
                            ) { season = s.number }
                        }
                    }
                }
            }
            if (current != null) {
                item {
                    val epState = rememberLazyListState()
                    LaunchedEffect(current.number) {
                        val idx = current.episodes.indexOfFirst { it.id == details.resume?.episode?.id }
                        if (idx > 0) epState.scrollToItem(idx)
                    }
                    LazyRow(
                        state = epState,
                        modifier = Modifier.padding(top = 18.dp),
                        contentPadding = PaddingValues(horizontal = ScreenPad),
                        horizontalArrangement = Arrangement.spacedBy(16.dp),
                    ) {
                        items(current.episodes) { ep ->
                            EpisodeCard(ep, details.progress[ep.id], onFocus = { focusedEp = ep }) { playEpisode(ep) }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun EpisodeCard(ep: EpisodeInfo, progress: Progress?, onFocus: () -> Unit, onClick: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(10.dp)
    Column(Modifier.width(236.dp)) {
        Surface(
            onClick = onClick,
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(16f / 9f)
                .onFocusChanged {
                    focused = it.isFocused
                    if (it.isFocused) onFocus()
                },
            shape = ClickableSurfaceDefaults.shape(shape = shape),
            scale = ClickableSurfaceDefaults.scale(focusedScale = 1.06f),
            border = ClickableSurfaceDefaults.border(focusedBorder = Border(border = BorderStroke(3.dp, Color.White), shape = shape)),
            colors = ClickableSurfaceDefaults.colors(containerColor = CardBg, focusedContainerColor = CardBg),
        ) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("E${ep.number}", fontSize = 26.sp, fontWeight = FontWeight.Bold, color = Faint)
            }
            if (ep.image != null) {
                AsyncImage(model = ep.image, contentDescription = ep.title, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize().clip(shape))
            }
            val watched = progress?.finished == true
            if (watched) {
                Box(
                    Modifier
                        .align(Alignment.TopEnd)
                        .padding(6.dp)
                        .background(Color.Black.copy(alpha = 0.7f), RoundedCornerShape(50))
                        .padding(horizontal = 7.dp, vertical = 2.dp),
                ) { Text("✓", fontSize = 12.sp, color = Color.White) }
            }
            if (progress != null && !progress.finished && progress.durationMs > 0 && progress.positionMs > 0) {
                Box(
                    Modifier
                        .align(Alignment.BottomStart)
                        .fillMaxWidth()
                        .height(4.dp)
                        .background(Color.Black.copy(alpha = 0.55f)),
                ) {
                    Box(
                        Modifier
                            .fillMaxWidth((progress.positionMs.toFloat() / progress.durationMs).coerceIn(0.03f, 1f))
                            .fillMaxHeight()
                            .background(Accent),
                    )
                }
            }
        }
        Text(
            "${ep.number}. ${ep.title}",
            fontSize = 14.sp,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            color = if (focused) Color.White else Muted,
            modifier = Modifier.padding(top = 8.dp),
        )
        val dur = formatRuntime(ep.durationSecs / 60)
        if (dur != null) Text(dur, fontSize = 12.sp, color = Faint)
    }
}
