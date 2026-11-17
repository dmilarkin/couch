package app.couch.tv.ui

import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider
import androidx.tv.material3.Border
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.LocalContentColor
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import app.couch.tv.data.Card
import app.couch.tv.data.Row as ContentRowData
import app.couch.tv.remote.NetUtil
import coil.compose.AsyncImage
import kotlinx.coroutines.delay

// ---------------------------------------------------------------- posters

@Composable
fun PosterCard(
    title: String,
    image: String?,
    modifier: Modifier = Modifier,
    width: Dp = 116.dp,
    wide: Boolean = false,
    badge: String? = null,
    progress: Float? = null,
    subcaption: String? = null,
    showCaption: Boolean = true,
    onFocus: () -> Unit = {},
    onLongClick: (() -> Unit)? = null,
    onClick: () -> Unit,
) {
    var focused by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(10.dp)
    Column(Modifier.width(width)) {
        Surface(
            onClick = onClick,
            onLongClick = onLongClick,
            modifier = modifier
                .fillMaxWidth()
                .aspectRatio(if (wide) 16f / 9f else 2f / 3f)
                .onFocusChanged {
                    focused = it.isFocused
                    if (it.isFocused) onFocus()
                },
            shape = ClickableSurfaceDefaults.shape(shape = shape),
            scale = ClickableSurfaceDefaults.scale(focusedScale = 1.08f),
            border = ClickableSurfaceDefaults.border(
                focusedBorder = Border(border = BorderStroke(3.dp, Color.White), shape = shape),
            ),
            colors = ClickableSurfaceDefaults.colors(
                containerColor = CardBg,
                contentColor = Color.White,
                focusedContainerColor = CardBg,
                focusedContentColor = Color.White,
            ),
        ) {
            Box(Modifier.fillMaxSize().padding(10.dp), contentAlignment = Alignment.Center) {
                Text(title, fontSize = 13.sp, color = Muted, textAlign = TextAlign.Center, maxLines = 4, overflow = TextOverflow.Ellipsis)
            }
            if (image != null) {
                AsyncImage(
                    model = image,
                    contentDescription = title,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize().clip(shape),
                )
            }
            if (badge != null) {
                Box(
                    Modifier
                        .align(Alignment.TopEnd)
                        .padding(6.dp)
                        .background(Color.Black.copy(alpha = 0.7f), RoundedCornerShape(4.dp))
                        .padding(horizontal = 5.dp, vertical = 1.dp),
                ) {
                    Text(badge, fontSize = 10.sp, fontWeight = FontWeight.Bold, color = Color.White)
                }
            }
            if (progress != null && progress > 0f) {
                Box(
                    Modifier
                        .align(Alignment.BottomStart)
                        .fillMaxWidth()
                        .height(4.dp)
                        .background(Color.Black.copy(alpha = 0.55f)),
                ) {
                    Box(Modifier.fillMaxWidth(progress.coerceIn(0.03f, 1f)).fillMaxHeight().background(Accent))
                }
            }
        }
        if (showCaption) {
            Text(
                title,
                fontSize = 13.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                color = if (focused) Color.White else Muted,
                modifier = Modifier.padding(top = 8.dp),
            )
            if (subcaption != null) {
                Text(subcaption, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, color = Faint)
            }
        }
    }
}

@Composable
fun ContentRow(
    row: ContentRowData,
    state: LazyListState,
    restoreId: String?,
    restoreRequester: FocusRequester,
    onFocus: (Card) -> Unit,
    onClick: (Card) -> Unit,
    onLongClick: ((Card) -> Unit)? = null,
) {
    Column(Modifier.fillMaxWidth().padding(bottom = 14.dp)) {
        Text(
            row.title,
            fontSize = 17.sp,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(start = ScreenPad, bottom = 10.dp),
        )
        LazyRow(
            state = state,
            contentPadding = PaddingValues(horizontal = ScreenPad),
            horizontalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            items(row.cards, key = { it.id }) { c ->
                PosterCard(
                    title = c.title,
                    image = c.image,
                    modifier = if (c.id == restoreId) Modifier.focusRequester(restoreRequester) else Modifier,
                    width = if (row.wide) 216.dp else 116.dp,
                    wide = row.wide,
                    badge = c.badge,
                    progress = c.progress,
                    subcaption = if (row.wide) c.subtitle else null,
                    onFocus = { onFocus(c) },
                    onLongClick = onLongClick?.let { f -> { f(c) } },
                    onClick = { onClick(c) },
                )
            }
        }
    }
}

// ---------------------------------------------------------------- buttons

@Composable
fun ActionButton(
    text: String,
    modifier: Modifier = Modifier,
    primary: Boolean = false,
    selected: Boolean = false,
    small: Boolean = false,
    onFocus: () -> Unit = {},
    onClick: () -> Unit,
) {
    Surface(
        onClick = onClick,
        modifier = modifier
            .height(if (small) 34.dp else 42.dp)
            .onFocusChanged { if (it.isFocused) onFocus() },
        shape = ClickableSurfaceDefaults.shape(shape = RoundedCornerShape(50)),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = when {
                primary -> Color.White.copy(alpha = 0.92f)
                selected -> Color.White.copy(alpha = 0.30f)
                else -> Color.White.copy(alpha = 0.12f)
            },
            contentColor = if (primary) Color.Black else Color.White,
            focusedContainerColor = Color.White,
            focusedContentColor = Color.Black,
        ),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1.06f),
    ) {
        Box(
            Modifier.fillMaxHeight().padding(horizontal = if (small) 16.dp else 22.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text(text, fontSize = if (small) 13.sp else 15.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
        }
    }
}

@Composable
fun TabPill(label: String, selected: Boolean, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        modifier = modifier.height(34.dp),
        shape = ClickableSurfaceDefaults.shape(shape = RoundedCornerShape(50)),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = if (selected) Color.White.copy(alpha = 0.16f) else Color.Transparent,
            contentColor = if (selected) Color.White else Muted,
            focusedContainerColor = Color.White,
            focusedContentColor = Color.Black,
        ),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1.05f),
    ) {
        Box(Modifier.fillMaxHeight().padding(horizontal = 16.dp), contentAlignment = Alignment.Center) {
            Text(label, fontSize = 15.sp, fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal)
        }
    }
}

@Composable
fun TopBar(nav: Nav, syncing: Boolean) {
    Row(
        Modifier.fillMaxWidth().height(TopBarHeight).padding(horizontal = ScreenPad),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text("Couch", fontSize = 21.sp, fontWeight = FontWeight.Bold, color = Accent, modifier = Modifier.padding(end = 22.dp))
        Row(Modifier.focusRestorer(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            Tab.entries.forEach { t ->
                TabPill(t.label, selected = nav.tab == t) { nav.tab = t }
            }
        }
        Spacer(Modifier.weight(1f))
        if (syncing) Text("Updating library…", fontSize = 12.sp, color = Muted)
    }
}

// ---------------------------------------------------------------- backdrop

@Composable
fun Backdrop(url: String?, modifier: Modifier = Modifier) {
    Box(modifier.fillMaxSize().background(Bg)) {
        Crossfade(targetState = url, animationSpec = tween(600), label = "backdrop") { u ->
            if (u != null) {
                AsyncImage(
                    model = u,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                )
            } else {
                Box(Modifier.fillMaxSize())
            }
        }
        Box(
            Modifier.fillMaxSize().background(
                Brush.horizontalGradient(
                    0f to Bg,
                    0.35f to Bg.copy(alpha = 0.9f),
                    0.7f to Bg.copy(alpha = 0.3f),
                    1f to Color.Transparent,
                ),
            ),
        )
        Box(
            Modifier.fillMaxSize().background(
                Brush.verticalGradient(
                    0f to Bg.copy(alpha = 0.6f),
                    0.22f to Color.Transparent,
                    0.5f to Bg.copy(alpha = 0.45f),
                    0.75f to Bg.copy(alpha = 0.92f),
                    1f to Bg,
                ),
            ),
        )
    }
}

// ---------------------------------------------------------------- panels

data class Opt<T>(val value: T, val label: String, val detail: String? = null)

/** A list of choices that slides in from the right, like Apple TV's menus. */
@Composable
fun <T> SidePanel(
    title: String,
    options: List<Opt<T>>,
    isSelected: (T) -> Boolean,
    onSelect: (T) -> Unit,
    onDismiss: () -> Unit,
    message: String? = null,
) {
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        val window = (LocalView.current.parent as? DialogWindowProvider)?.window
        SideEffect { window?.setDimAmount(0.5f) }
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.CenterEnd) {
            Column(
                Modifier
                    .fillMaxHeight()
                    .width(420.dp)
                    .background(PanelBg)
                    .padding(horizontal = 26.dp, vertical = 30.dp),
            ) {
                Text(title, fontSize = 22.sp, fontWeight = FontWeight.Bold)
                if (message != null) Text(message, color = Muted, fontSize = 14.sp, modifier = Modifier.padding(top = 8.dp))
                Spacer(Modifier.height(14.dp))
                val startIndex = options.indexOfFirst { isSelected(it.value) }.coerceAtLeast(0)
                val listState = rememberLazyListState(initialFirstVisibleItemIndex = startIndex)
                val fr = remember { FocusRequester() }
                LazyColumn(state = listState, verticalArrangement = Arrangement.spacedBy(4.dp), modifier = Modifier.weight(1f)) {
                    itemsIndexed(options) { i, o ->
                        OptionRow(
                            label = o.label,
                            detail = o.detail,
                            checked = isSelected(o.value),
                            modifier = if (i == startIndex) Modifier.focusRequester(fr) else Modifier,
                        ) { onSelect(o.value) }
                    }
                }
                LaunchedEffect(options.size) {
                    if (options.isNotEmpty()) {
                        delay(80)
                        try {
                            fr.requestFocus()
                        } catch (e: Exception) {
                            // not laid out yet
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun OptionRow(label: String, detail: String?, checked: Boolean, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        modifier = modifier.fillMaxWidth(),
        shape = ClickableSurfaceDefaults.shape(shape = RoundedCornerShape(10.dp)),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = Color.Transparent,
            contentColor = Color.White,
            focusedContainerColor = Color.White,
            focusedContentColor = Color.Black,
        ),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1.02f),
    ) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(label, fontSize = 15.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                if (detail != null) {
                    Text(detail, fontSize = 12.sp, maxLines = 2, overflow = TextOverflow.Ellipsis, color = LocalContentColor.current.copy(alpha = 0.6f))
                }
            }
            if (checked) Text("✓", fontSize = 18.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(start = 10.dp))
        }
    }
}

/** A row with an on/off state, used by the library filter and settings. */
@Composable
fun ToggleRow(label: String, detail: String?, on: Boolean, modifier: Modifier = Modifier, onFocus: () -> Unit = {}, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        modifier = modifier.fillMaxWidth().onFocusChanged { if (it.isFocused) onFocus() },
        shape = ClickableSurfaceDefaults.shape(shape = RoundedCornerShape(10.dp)),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = Color.White.copy(alpha = 0.05f),
            contentColor = Color.White,
            focusedContainerColor = Color.White,
            focusedContentColor = Color.Black,
        ),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1.01f),
    ) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 11.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(label, fontSize = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (detail != null) {
                    Text(detail, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, color = LocalContentColor.current.copy(alpha = 0.6f))
                }
            }
            Switch(on)
        }
    }
}

@Composable
private fun Switch(on: Boolean) {
    Box(
        Modifier
            .padding(start = 12.dp)
            .size(width = 40.dp, height = 22.dp)
            .clip(RoundedCornerShape(50))
            .background(if (on) Color(0xFF34C759) else Color(0xFF55555E)),
        contentAlignment = if (on) Alignment.CenterEnd else Alignment.CenterStart,
    ) {
        Box(Modifier.padding(3.dp).size(16.dp).clip(RoundedCornerShape(50)).background(Color.White))
    }
}

// ---------------------------------------------------------------- inputs & misc

@Composable
fun TvTextField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    modifier: Modifier = Modifier,
    password: Boolean = false,
    placeholder: String = "",
    imeAction: ImeAction = ImeAction.Next,
    onIme: () -> Unit = {},
) {
    var focused by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(10.dp)
    Column(modifier) {
        if (label.isNotEmpty()) Text(label, fontSize = 13.sp, color = Muted, modifier = Modifier.padding(bottom = 6.dp))
        Box {
            BasicTextField(
                value = value,
                onValueChange = onValueChange,
                singleLine = true,
                textStyle = TextStyle(color = Color.White, fontSize = 17.sp),
                cursorBrush = SolidColor(Color.White),
                visualTransformation = if (password) PasswordVisualTransformation() else VisualTransformation.None,
                keyboardOptions = KeyboardOptions(
                    keyboardType = if (password) KeyboardType.Password else KeyboardType.Uri,
                    imeAction = imeAction,
                ),
                keyboardActions = KeyboardActions(onAny = { onIme() }),
                modifier = Modifier
                    .fillMaxWidth()
                    .onFocusChanged { focused = it.isFocused }
                    .background(if (focused) Color.White.copy(alpha = 0.16f) else Color.White.copy(alpha = 0.07f), shape)
                    .border(2.dp, if (focused) Color.White else Color.Transparent, shape)
                    .padding(horizontal = 16.dp, vertical = 12.dp),
            )
            if (value.isEmpty() && placeholder.isNotEmpty()) {
                Text(placeholder, color = Faint, fontSize = 17.sp, modifier = Modifier.padding(horizontal = 18.dp, vertical = 12.dp))
            }
        }
    }
}

@Composable
fun QrImage(text: String?, size: Dp) {
    val bmp = remember(text) { text?.let { NetUtil.qr(it) } }
    Box(
        Modifier.size(size).background(Color.White, RoundedCornerShape(16.dp)).padding(12.dp),
        contentAlignment = Alignment.Center,
    ) {
        if (bmp != null) {
            Image(
                bitmap = bmp.asImageBitmap(),
                contentDescription = "QR code",
                modifier = Modifier.fillMaxSize(),
                filterQuality = FilterQuality.None,
            )
        } else {
            Text("Connect the TV to your network", color = Color.Black, textAlign = TextAlign.Center)
        }
    }
}

@Composable
fun Spinner(size: Dp = 44.dp, color: Color = Color.White) {
    val t = rememberInfiniteTransition(label = "spin")
    val angle by t.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(tween(900, easing = LinearEasing), RepeatMode.Restart),
        label = "angle",
    )
    Canvas(Modifier.size(size)) {
        rotate(angle) {
            drawArc(
                color = color,
                startAngle = 0f,
                sweepAngle = 270f,
                useCenter = false,
                style = Stroke(width = 4.dp.toPx(), cap = StrokeCap.Round),
            )
        }
    }
}

@Composable
fun Centered(content: @Composable () -> Unit) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { content() }
}

@Composable
fun MessageBox(title: String, body: String? = null, action: String? = null, onAction: () -> Unit = {}) {
    Centered {
        Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.width(520.dp)) {
            Text(title, fontSize = 22.sp, fontWeight = FontWeight.SemiBold, textAlign = TextAlign.Center)
            if (body != null) Text(body, fontSize = 15.sp, color = Muted, textAlign = TextAlign.Center, modifier = Modifier.padding(top = 10.dp))
            if (action != null) {
                val fr = remember { FocusRequester() }
                ActionButton(action, Modifier.padding(top = 20.dp).focusRequester(fr), primary = true, onClick = onAction)
                LaunchedEffect(Unit) {
                    delay(60)
                    try {
                        fr.requestFocus()
                    } catch (e: Exception) {
                    }
                }
            }
        }
    }
}

fun formatTime(ms: Long): String {
    val s = (ms.coerceAtLeast(0L)) / 1000
    val h = s / 3600
    val m = (s % 3600) / 60
    val sec = s % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, sec) else "%d:%02d".format(m, sec)
}

fun formatRuntime(min: Int): String? = when {
    min <= 0 -> null
    min < 60 -> "${min}m"
    else -> "${min / 60}h ${min % 60}m"
}

fun ratingText(r: Double): String? = if (r > 0) "★ %.1f".format(r) else null

suspend fun FocusRequester.tryFocus(delayMs: Long = 60) {
    delay(delayMs)
    try {
        requestFocus()
    } catch (e: Exception) {
        // target not composed yet
    }
}
