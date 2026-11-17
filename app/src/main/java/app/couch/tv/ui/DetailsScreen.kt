package app.couch.tv.ui

import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Text
import app.couch.tv.CouchApp
import app.couch.tv.data.MetaInfo
import app.couch.tv.data.MovieDetails
import app.couch.tv.data.Row as DataRow
import kotlinx.coroutines.launch

@Composable
fun MovieScreen(app: CouchApp, nav: Nav, id: String) {
    var d by remember { mutableStateOf<MovieDetails?>(null) }
    var failed by remember { mutableStateOf(false) }
    var version by remember { mutableStateOf<String?>(null) }
    var fav by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val ctx = LocalContext.current
    val playFocus = remember { FocusRequester() }

    LaunchedEffect(id) {
        val r = try {
            app.repo.movieDetails(id)
        } catch (e: Exception) {
            null
        }
        if (r == null) failed = true
        d = r
        fav = r?.favorite == true
        if (version == null) version = r?.versions?.firstOrNull()?.id
        playFocus.tryFocus(120)
    }

    val details = d
    if (details == null) {
        if (failed) MessageBox("Couldn't load this title", action = "Back") { nav.pop() } else Centered { Spinner() }
        return
    }
    val item = details.item
    val meta = details.meta
    val info = details.info
    val backdrop = meta?.backdrop ?: info?.backdrop ?: item.backdrop ?: item.poster
    val progress = details.progress
    val canResume = progress != null && !progress.finished && progress.positionMs > 30_000

    fun play(fromStart: Boolean) {
        scope.launch { app.repo.moviePlay(item.id, fromStart, version)?.let { nav.play(it) } }
    }

    Box(Modifier.fillMaxSize()) {
        Backdrop(backdrop)
        LazyColumn(
            Modifier.fillMaxSize(),
            contentPadding = PaddingValues(top = 70.dp, bottom = 40.dp),
        ) {
            item {
                Column(Modifier.padding(horizontal = ScreenPad).width(620.dp)) {
                    Text(item.title, fontSize = 38.sp, fontWeight = FontWeight.Bold, maxLines = 2, overflow = TextOverflow.Ellipsis, lineHeight = 44.sp)
                    meta?.tagline?.let { Text(it, fontSize = 15.sp, color = Muted, modifier = Modifier.padding(top = 4.dp)) }
                    Spacer(Modifier.height(10.dp))
                    MetaLine(
                        listOfNotNull(
                            item.year.takeIf { it > 0 }?.toString(),
                            formatRuntime(info?.durationSecs?.takeIf { it > 0 }?.div(60) ?: meta?.runtimeMin ?: 0),
                            (info?.genre ?: meta?.genres?.joinToString(", "))?.takeIf { it.isNotBlank() },
                        ),
                    )
                    RatingsLine(item.rating, info?.rating ?: 0.0, meta)
                    Spacer(Modifier.height(12.dp))
                    val plot = meta?.overview ?: info?.plot ?: item.plot
                    if (plot != null) Text(plot, fontSize = 15.sp, lineHeight = 22.sp, maxLines = 5, overflow = TextOverflow.Ellipsis, color = Color.White.copy(alpha = 0.9f))
                    val credits = listOfNotNull(
                        info?.director?.let { "Director: $it" },
                        (info?.cast ?: meta?.cast)?.let { "Cast: $it" },
                    )
                    credits.forEach { Text(it, fontSize = 13.sp, color = Muted, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 6.dp)) }
                }
            }
            item {
                Row(
                    Modifier.padding(start = ScreenPad, top = 22.dp, end = ScreenPad),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    if (canResume && progress != null) {
                        ActionButton("▶  Resume ${formatTime(progress.positionMs)}", Modifier.focusRequester(playFocus), primary = true) { play(false) }
                        ActionButton("Start over") { play(true) }
                    } else {
                        ActionButton("▶  Play", Modifier.focusRequester(playFocus), primary = true) { play(true) }
                    }
                    ActionButton(if (fav) "✓  My List" else "+  My List") {
                        scope.launch { fav = app.repo.toggleFavorite(item.id) }
                    }
                    val trailer = info?.trailer
                    if (!trailer.isNullOrBlank()) {
                        ActionButton("Trailer") { openTrailer(ctx, trailer) }
                    }
                }
            }
            if (details.versions.size > 1) {
                item {
                    Column(Modifier.padding(start = ScreenPad, top = 20.dp)) {
                        Text("Version", fontSize = 14.sp, color = Muted, modifier = Modifier.padding(bottom = 8.dp))
                        LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            items(details.versions, key = { it.id }) { v ->
                                ActionButton(app.repo.versionLabel(v), small = true, selected = v.id == version) { version = v.id }
                            }
                        }
                    }
                }
            }
            if (progress != null && progress.durationMs > 0 && canResume) {
                item {
                    Box(
                        Modifier
                            .padding(start = ScreenPad, top = 14.dp)
                            .width(280.dp)
                            .height(4.dp)
                            .background(Color.White.copy(alpha = 0.2f), RoundedCornerShape(2.dp)),
                    ) {
                        Box(
                            Modifier
                                .fillMaxWidth((progress.positionMs.toFloat() / progress.durationMs).coerceIn(0f, 1f))
                                .height(4.dp)
                                .background(Accent, RoundedCornerShape(2.dp)),
                        )
                    }
                }
            }
            if (details.similar.isNotEmpty()) {
                item {
                    Spacer(Modifier.height(30.dp))
                    ContentRow(
                        row = DataRow("similar", "More like this", details.similar.map { app.repo.cardOf(it) }),
                        state = nav.list("similar-$id"),
                        restoreId = null,
                        restoreRequester = remember { FocusRequester() },
                        onFocus = {},
                        onClick = { c -> nav.open(c.id) },
                    )
                }
            }
        }
    }
}

@Composable
fun MetaLine(bits: List<String>) {
    if (bits.isEmpty()) return
    Text(bits.joinToString("  ·  "), fontSize = 15.sp, color = Muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
}

@Composable
fun RatingsLine(providerRating: Double, infoRating: Double, meta: MetaInfo?) {
    val bits = listOfNotNull(
        meta?.imdbRating?.let { "IMDb $it" },
        meta?.rotten?.let { "Rotten Tomatoes $it" },
        meta?.metascore?.let { "Metacritic $it" },
        meta?.tmdbRating?.takeIf { it > 0 }?.let { "TMDB %.1f".format(it) },
        (infoRating.takeIf { it > 0 } ?: providerRating.takeIf { it > 0 })?.let { "★ %.1f".format(it) }?.takeIf { meta == null || meta.tmdbRating <= 0 },
    )
    if (bits.isEmpty()) return
    Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        bits.forEach { b ->
            Box(
                Modifier
                    .background(Color.White.copy(alpha = 0.12f), RoundedCornerShape(6.dp))
                    .padding(horizontal = 8.dp, vertical = 3.dp),
            ) {
                Text(b, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
            }
        }
    }
}

fun openTrailer(ctx: android.content.Context, trailer: String) {
    val id = trailer.substringAfter("v=", trailer).substringAfterLast('/').substringBefore('&')
    val intents = listOf(
        Intent(Intent.ACTION_VIEW, Uri.parse("vnd.youtube:$id")),
        Intent(Intent.ACTION_VIEW, Uri.parse("https://www.youtube.com/watch?v=$id")),
    )
    for (i in intents) {
        try {
            ctx.startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            return
        } catch (e: ActivityNotFoundException) {
            // try the next one
        }
    }
}
