package app.couch.tv.ui

import android.content.Context
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.Tracks
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.extractor.DefaultExtractorsFactory
import androidx.media3.extractor.ts.DefaultTsPayloadReaderFactory
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.CaptionStyleCompat
import androidx.media3.ui.PlayerView
import androidx.media3.ui.SubtitleView
import androidx.tv.material3.Text
import app.couch.tv.CouchApp
import app.couch.tv.data.EpgEntry
import app.couch.tv.data.PlayRequest
import app.couch.tv.data.SubResult
import app.couch.tv.data.TitleParser
import app.couch.tv.remote.NowPlaying
import app.couch.tv.remote.RemoteCommand
import app.couch.tv.remote.SubTrack
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.Locale

private enum class Panel { Speed, Quality, Audio, Subs, SubsOnline, Source }

private data class TrackRef(val group: Tracks.Group, val index: Int) {
    val format: Format get() = group.getTrackFormat(index)
    val selected: Boolean get() = group.isTrackSelected(index)
}

private val SPEEDS = listOf(0.5f, 0.75f, 1f, 1.25f, 1.5f, 1.75f, 2f)

/** Subtitle tracks the TV can actually show, in a stable order shared with the phone remote. */
private fun textRefs(t: Tracks): List<TrackRef> =
    t.groups.filter { it.type == C.TRACK_TYPE_TEXT }.flatMap { g -> (0 until g.length).filter { g.isTrackSupported(it) }.map { TrackRef(g, it) } }

@androidx.annotation.OptIn(UnstableApi::class)
@Composable
fun PlayerScreen(app: CouchApp, nav: Nav, initial: PlayRequest) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var req by remember { mutableStateOf(initial) }
    val player = remember { buildPlayer(ctx, app) }

    var isPlaying by remember { mutableStateOf(false) }
    var buffering by remember { mutableStateOf(true) }
    var position by remember { mutableLongStateOf(0L) }
    var duration by remember { mutableLongStateOf(0L) }
    var speed by remember { mutableFloatStateOf(1f) }
    var tracks by remember { mutableStateOf(Tracks.EMPTY) }
    var error by remember { mutableStateOf<String?>(null) }
    var ended by remember { mutableStateOf(false) }
    var controls by remember { mutableStateOf(true) }
    var lastInteraction by remember { mutableLongStateOf(System.currentTimeMillis()) }
    var panel by remember { mutableStateOf<Panel?>(null) }
    var resizeMode by remember { mutableIntStateOf(AspectRatioFrameLayout.RESIZE_MODE_FIT) }
    var toast by remember { mutableStateOf<String?>(null) }
    var sideSubs by remember { mutableStateOf<List<MediaItem.SubtitleConfiguration>>(emptyList()) }
    var currentUrl by remember { mutableStateOf(initial.url) }
    var currentSourceId by remember { mutableStateOf(initial.sourceId) }
    var upNext by remember { mutableStateOf<Int?>(null) }
    var subResults by remember { mutableStateOf<List<SubResult>?>(null) }
    var subMessage by remember { mutableStateOf<String?>(null) }
    var liveNow by remember { mutableStateOf<EpgEntry?>(null) }

    val rootFocus = remember { FocusRequester() }
    val mainFocus = remember { FocusRequester() }
    val upNextFocus = remember { FocusRequester() }
    val errorFocus = remember { FocusRequester() }

    // ---------------------------------------------------------------- actions

    fun showControls() {
        controls = true
        lastInteraction = System.currentTimeMillis()
    }

    fun load(url: String, startMs: Long, subs: List<MediaItem.SubtitleConfiguration>) {
        error = null
        ended = false
        buffering = true
        val b = MediaItem.Builder()
            .setUri(url)
            .setSubtitleConfigurations(subs)
            .setMediaMetadata(MediaMetadata.Builder().setTitle(req.title).build())
        if (url.substringBefore('?').endsWith(".m3u8")) b.setMimeType(MimeTypes.APPLICATION_M3U8)
        player.setMediaItem(b.build(), startMs.coerceAtLeast(0L))
        player.prepare()
        player.playWhenReady = true
    }

    fun togglePlay() {
        if (player.isPlaying) {
            player.pause()
        } else {
            if (player.playbackState == Player.STATE_ENDED) player.seekTo(0L)
            player.play()
        }
    }

    fun seekBy(delta: Long) {
        if (req.kind == "live") return
        val max = player.duration.takeIf { it > 0 } ?: Long.MAX_VALUE
        val target = (player.currentPosition + delta).coerceIn(0L, max)
        player.seekTo(target)
        position = target
        showControls()
    }

    fun playNext() {
        val r = req
        val next = r.queue.firstOrNull() ?: return
        val pos = player.currentPosition
        val dur = player.duration
        app.scope.launch { app.repo.saveProgress(r, pos, if (dur > 0) dur else 0L) }
        upNext = null
        req = next.copy(queue = r.queue.drop(1))
    }

    fun zap(dir: Int) {
        val r = req
        if (r.kind != "live" || r.channels.isEmpty()) return
        val idx = r.channels.indexOf(r.itemId).coerceAtLeast(0)
        val nextId = r.channels[(idx + dir + r.channels.size) % r.channels.size]
        scope.launch {
            app.repo.livePlay(nextId, r.channels)?.let {
                req = it
                showControls()
            }
        }
    }

    fun switchSource(id: String) {
        val src = req.sources.firstOrNull { it.id == id } ?: return
        currentSourceId = id
        currentUrl = src.url
        val pos = player.currentPosition
        load(src.url, pos, sideSubs)
        toast = "Switched to ${src.label}"
    }

    fun addOnlineSub(r: SubResult) {
        panel = null
        toast = "Downloading subtitles…"
        scope.launch {
            try {
                val f = app.subs.download(r.fileId)
                val conf = MediaItem.SubtitleConfiguration.Builder(Uri.fromFile(f))
                    .setMimeType(MimeTypes.APPLICATION_SUBRIP)
                    .setLanguage(r.language)
                    .setLabel("${langName(r.language) ?: r.language} (online)")
                    .setSelectionFlags(C.SELECTION_FLAG_DEFAULT)
                    .build()
                val list = sideSubs + conf
                sideSubs = list
                player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
                    .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false)
                    .clearOverridesOfType(C.TRACK_TYPE_TEXT)
                    .setPreferredTextLanguage(r.language)
                    .build()
                load(currentUrl, player.currentPosition, list)
                toast = "Subtitles added"
            } catch (e: Exception) {
                toast = e.message ?: "Couldn't download subtitles"
            }
        }
    }

    fun step(e: KeyEvent): Long {
        val rc = e.nativeKeyEvent.repeatCount
        return when {
            rc < 3 -> 10_000L
            rc < 10 -> 30_000L
            else -> 60_000L
        }
    }

    fun handleKey(e: KeyEvent): Boolean {
        if (e.type != KeyEventType.KeyDown) return false
        lastInteraction = System.currentTimeMillis()
        val isLive = req.kind == "live"
        when (e.key) {
            Key.MediaPlayPause -> {
                togglePlay()
                showControls()
                return true
            }
            Key.MediaPlay -> {
                player.play()
                return true
            }
            Key.MediaPause -> {
                player.pause()
                showControls()
                return true
            }
            Key.MediaFastForward -> {
                seekBy(30_000L)
                return true
            }
            Key.MediaRewind -> {
                seekBy(-30_000L)
                return true
            }
            Key.MediaNext -> {
                if (isLive) zap(1) else playNext()
                return true
            }
            Key.MediaPrevious -> {
                if (isLive) zap(-1)
                return true
            }
            Key.ChannelUp -> {
                zap(-1)
                return true
            }
            Key.ChannelDown -> {
                zap(1)
                return true
            }
        }
        if (error != null || upNext != null || controls) return false
        // Controls hidden: arrows seek or change channel, OK shows the controls.
        return when (e.key) {
            Key.DirectionLeft -> {
                if (isLive) showControls() else seekBy(-step(e))
                true
            }
            Key.DirectionRight -> {
                if (isLive) showControls() else seekBy(step(e))
                true
            }
            Key.DirectionCenter, Key.Enter, Key.NumPadEnter -> {
                if (!isLive) togglePlay()
                showControls()
                true
            }
            Key.DirectionUp -> {
                if (isLive) zap(-1) else showControls()
                true
            }
            Key.DirectionDown -> {
                if (isLive) zap(1) else showControls()
                true
            }
            Key.Menu -> {
                showControls()
                true
            }
            else -> false
        }
    }

    // ---------------------------------------------------------------- effects

    DisposableEffect(player) {
        val listener = object : Player.Listener {
            override fun onIsPlayingChanged(playing: Boolean) {
                isPlaying = playing
            }

            override fun onPlaybackStateChanged(state: Int) {
                buffering = state == Player.STATE_BUFFERING
                if (state == Player.STATE_READY) {
                    error = null
                    if (player.duration > 0) duration = player.duration
                }
                if (state == Player.STATE_ENDED) ended = true
            }

            override fun onTracksChanged(t: Tracks) {
                tracks = t
                val audio = t.groups.filter { it.type == C.TRACK_TYPE_AUDIO }
                if (audio.isNotEmpty() && audio.none { g -> (0 until g.length).any { g.isTrackSupported(it) } }) {
                    toast = "This TV can't play this audio format. Try another version, or see the README about the FFmpeg decoder."
                }
            }

            override fun onPlaybackParametersChanged(playbackParameters: PlaybackParameters) {
                speed = playbackParameters.speed
            }

            override fun onPlayerError(e: PlaybackException) {
                if (e.errorCode == PlaybackException.ERROR_CODE_BEHIND_LIVE_WINDOW) {
                    player.seekToDefaultPosition()
                    player.prepare()
                    return
                }
                error = friendlyError(e)
            }
        }
        player.addListener(listener)
        onDispose {
            val r = req
            val pos = player.currentPosition
            val dur = player.duration
            if (r.kind != "live" && pos > 0) {
                app.scope.launch { app.repo.saveProgress(r, pos, if (dur > 0) dur else 0L) }
            }
            player.removeListener(listener)
            player.release()
            app.nowPlaying.value = null
        }
    }

    LaunchedEffect(req) {
        val r = req
        currentUrl = r.url
        currentSourceId = r.sourceId
        sideSubs = emptyList()
        subResults = null
        upNext = null
        val audioLang = TitleParser.ISO[app.prefs.config.value.prefLang]
        player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
            .clearOverrides()
            .setPreferredAudioLanguage(audioLang)
            .build()
        load(r.url, r.startMs, emptyList())
        showControls()
        liveNow = null
        if (r.kind == "live") {
            val item = app.db.items().get(r.itemId)
            if (item != null) liveNow = app.repo.epg(item).firstOrNull()
        }
    }

    LaunchedEffect(player) {
        var lastSave = System.currentTimeMillis()
        while (true) {
            position = player.currentPosition
            if (player.duration > 0) duration = player.duration
            val r = req
            val texts = textRefs(tracks)
            val subsOff = player.trackSelectionParameters.disabledTrackTypes.contains(C.TRACK_TYPE_TEXT)
            app.nowPlaying.value = NowPlaying(
                title = r.title,
                subtitle = r.subtitle,
                image = r.image,
                kind = r.kind,
                positionMs = position,
                durationMs = duration,
                playing = player.isPlaying,
                speed = player.playbackParameters.speed,
                hasNext = r.queue.isNotEmpty(),
                subtitles = texts.mapIndexed { i, t -> SubTrack(i, textLabel(t.format, i), !subsOff && t.selected) },
            )
            val now = System.currentTimeMillis()
            if (r.kind != "live" && player.isPlaying && now - lastSave > 10_000) {
                lastSave = now
                app.repo.saveProgress(r, position, duration)
            }
            if (controls && panel == null && error == null && upNext == null && player.isPlaying && now - lastInteraction > 5_000) {
                controls = false
            }
            delay(500)
        }
    }

    LaunchedEffect(ended) {
        if (!ended) return@LaunchedEffect
        val r = req
        if (r.kind == "live") {
            error = "The channel stopped streaming."
            return@LaunchedEffect
        }
        app.repo.saveProgress(r, duration, duration)
        if (r.queue.isEmpty()) {
            nav.pop()
            return@LaunchedEffect
        }
        if (!app.prefs.config.value.autoplayNext) {
            upNext = -1
            return@LaunchedEffect
        }
        var n = 10
        upNext = n
        while (n > 0) {
            delay(1000)
            if (upNext == null) return@LaunchedEffect
            n--
            upNext = n
        }
        playNext()
    }

    LaunchedEffect(controls, error, upNext != null) {
        when {
            error != null -> errorFocus.tryFocus(80)
            upNext != null -> upNextFocus.tryFocus(80)
            controls -> mainFocus.tryFocus(60)
            else -> rootFocus.tryFocus(30)
        }
    }

    LaunchedEffect(toast) {
        if (toast != null) {
            delay(3500)
            toast = null
        }
    }

    LaunchedEffect(panel) {
        if (panel == Panel.SubsOnline && subResults == null) {
            val lang = app.prefs.config.value.subLang
            subMessage = "Searching OpenSubtitles…"
            try {
                val res = app.subs.search(req, lang)
                subResults = res
                subMessage = if (res.isEmpty()) "Nothing found in ${SUB_LANGS.firstOrNull { it.first == lang }?.second ?: lang}. Change the language in Settings." else null
            } catch (e: Exception) {
                subResults = emptyList()
                subMessage = e.message
            }
        }
    }

    LaunchedEffect(Unit) {
        app.bus.commands.collect { c ->
            if (c !is RemoteCommand.Control) return@collect
            when (c.cmd) {
                "toggle" -> togglePlay()
                "play" -> player.play()
                "pause" -> player.pause()
                "seek" -> seekBy((c.value * 1000).toLong())
                "seekTo" -> {
                    player.seekTo((c.value * 1000).toLong())
                    showControls()
                }
                "speed" -> player.setPlaybackSpeed(c.value.toFloat().coerceIn(0.25f, 3f))
                "next" -> playNext()
                "zapUp" -> zap(-1)
                "zapDown" -> zap(1)
                "stop" -> nav.pop()
                "subsOff" -> {
                    player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
                        .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true).build()
                    toast = "Subtitles off"
                }
                "subsOn" -> {
                    // value = track number from the phone, or -1 to pick the best match for the subtitle language.
                    val texts = textRefs(tracks)
                    val lang = app.prefs.config.value.subLang.substringBefore('-')
                    val pick = texts.getOrNull(c.value.toInt())
                        ?: texts.firstOrNull { it.format.language?.startsWith(lang) == true }
                        ?: texts.firstOrNull()
                    val b = player.trackSelectionParameters.buildUpon().setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false)
                    if (pick != null) b.setOverrideForType(TrackSelectionOverride(pick.group.mediaTrackGroup, pick.index))
                    player.trackSelectionParameters = b.build()
                    toast = if (pick != null) "Subtitles: ${textLabel(pick.format, texts.indexOf(pick))}" else "This stream has no subtitles. Try Find online."
                }
                "subsSearch" -> {
                    showControls()
                    panel = Panel.SubsOnline
                }
            }
        }
    }

    BackHandler {
        when {
            upNext != null -> upNext = null
            controls && error == null -> controls = false
            else -> nav.pop()
        }
    }

    // ---------------------------------------------------------------- UI

    val r = req
    val isLive = r.kind == "live"

    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black)
            .focusRequester(rootFocus)
            .onPreviewKeyEvent { handleKey(it) }
            .focusable(),
    ) {
        AndroidView(
            factory = { c ->
                val exo = player
                PlayerView(c).apply {
                    useController = false
                    this.player = exo
                    setShutterBackgroundColor(android.graphics.Color.BLACK)
                    keepScreenOn = true
                    isFocusable = false
                    isFocusableInTouchMode = false
                    subtitleView?.setStyle(
                        CaptionStyleCompat(
                            android.graphics.Color.WHITE,
                            android.graphics.Color.TRANSPARENT,
                            android.graphics.Color.TRANSPARENT,
                            CaptionStyleCompat.EDGE_TYPE_OUTLINE,
                            android.graphics.Color.BLACK,
                            null,
                        ),
                    )
                    subtitleView?.setFractionalTextSize(SubtitleView.DEFAULT_TEXT_SIZE_FRACTION * 1.15f)
                }
            },
            update = { v -> v.resizeMode = resizeMode },
            modifier = Modifier.fillMaxSize(),
        )

        if (buffering && error == null) {
            Centered { Spinner(56.dp) }
        }

        if (controls && error == null) {
            // Top: title
            Column(
                Modifier
                    .align(Alignment.TopStart)
                    .fillMaxWidth()
                    .background(Brush.verticalGradient(listOf(Color.Black.copy(alpha = 0.75f), Color.Transparent)))
                    .padding(horizontal = ScreenPad, vertical = 26.dp),
            ) {
                Text(r.title, fontSize = 26.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                r.subtitle?.let { Text(it, fontSize = 15.sp, color = Color.White.copy(alpha = 0.75f), maxLines = 1, overflow = TextOverflow.Ellipsis) }
                if (isLive) {
                    liveNow?.let { Text("Now: ${it.title}", fontSize = 14.sp, color = Accent, modifier = Modifier.padding(top = 4.dp)) }
                }
            }

            // Bottom: scrubber + buttons
            Column(
                Modifier
                    .align(Alignment.BottomStart)
                    .fillMaxWidth()
                    .background(Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = 0.88f))))
                    .padding(start = ScreenPad, end = ScreenPad, top = 60.dp, bottom = 28.dp),
            ) {
                if (!isLive) {
                    SeekBar(
                        position = position,
                        duration = duration,
                        modifier = Modifier.focusRequester(mainFocus),
                        onSeek = { delta -> seekBy(delta) },
                        onToggle = { togglePlay() },
                        stepFor = { step(it) },
                    )
                    Row(Modifier.fillMaxWidth().padding(top = 8.dp)) {
                        Text(formatTime(position), fontSize = 13.sp, color = Color.White.copy(alpha = 0.8f))
                        Spacer(Modifier.weight(1f))
                        val left = (duration - position).coerceAtLeast(0L)
                        Text(if (duration > 0) "-${formatTime(left)}" else "", fontSize = 13.sp, color = Color.White.copy(alpha = 0.8f))
                    }
                } else {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.size(10.dp).clip(RoundedCornerShape(50)).background(Danger))
                        Text("LIVE", fontSize = 14.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(start = 8.dp))
                        Text("   Up/Down changes channel", fontSize = 13.sp, color = Muted)
                    }
                }
                Spacer(Modifier.height(16.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                    ActionButton(
                        if (isPlaying) "❚❚  Pause" else "▶  Play",
                        if (isLive) Modifier.focusRequester(mainFocus) else Modifier,
                        small = true,
                    ) { togglePlay() }
                    if (isLive) {
                        ActionButton("Channel ▲", small = true) { zap(-1) }
                        ActionButton("Channel ▼", small = true) { zap(1) }
                    }
                    ActionButton("Audio", small = true) { panel = Panel.Audio }
                    ActionButton("Subtitles", small = true) { panel = Panel.Subs }
                    if (!isLive) ActionButton("Speed ${speedLabel(speed)}", small = true, selected = speed != 1f) { panel = Panel.Speed }
                    ActionButton("Quality", small = true) { panel = Panel.Quality }
                    if (r.sources.size > 1) ActionButton("Version", small = true) { panel = Panel.Source }
                    ActionButton(resizeLabel(resizeMode), small = true) {
                        resizeMode = when (resizeMode) {
                            AspectRatioFrameLayout.RESIZE_MODE_FIT -> AspectRatioFrameLayout.RESIZE_MODE_ZOOM
                            AspectRatioFrameLayout.RESIZE_MODE_ZOOM -> AspectRatioFrameLayout.RESIZE_MODE_FILL
                            else -> AspectRatioFrameLayout.RESIZE_MODE_FIT
                        }
                    }
                    if (r.queue.isNotEmpty()) ActionButton("Next episode", small = true) { playNext() }
                }
            }
        }

        val un = upNext
        if (un != null && error == null) {
            val next = r.queue.firstOrNull()
            Column(
                Modifier
                    .align(Alignment.BottomEnd)
                    .padding(ScreenPad)
                    .width(380.dp)
                    .background(PanelBg.copy(alpha = 0.95f), RoundedCornerShape(14.dp))
                    .padding(20.dp),
            ) {
                Text(if (un >= 0) "Up next in $un" else "Up next", fontSize = 13.sp, color = Accent, fontWeight = FontWeight.Bold)
                Text(next?.subtitle ?: "Next episode", fontSize = 17.sp, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 4.dp))
                Row(Modifier.padding(top = 14.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    ActionButton("Play now", Modifier.focusRequester(upNextFocus), primary = true, small = true) { playNext() }
                    ActionButton("Cancel", small = true) {
                        upNext = null
                        nav.pop()
                    }
                }
            }
        }

        val err = error
        if (err != null) {
            Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.75f)), contentAlignment = Alignment.Center) {
                Column(Modifier.width(600.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("Can't play this right now", fontSize = 24.sp, fontWeight = FontWeight.Bold)
                    Text(err, fontSize = 14.sp, color = Muted, modifier = Modifier.padding(top = 10.dp))
                    Row(Modifier.padding(top = 22.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        ActionButton("Try again", Modifier.focusRequester(errorFocus), primary = true) {
                            load(currentUrl, player.currentPosition, sideSubs)
                        }
                        val alt = r.sources.firstOrNull { it.id != currentSourceId }
                        if (alt != null) ActionButton("Try ${alt.label}") { switchSource(alt.id) }
                        ActionButton("Back") { nav.pop() }
                    }
                }
            }
        }

        val t = toast
        if (t != null) {
            Box(
                Modifier
                    .align(Alignment.TopEnd)
                    .padding(top = 28.dp, end = ScreenPad)
                    .width(420.dp)
                    .background(PanelBg.copy(alpha = 0.95f), RoundedCornerShape(12.dp))
                    .padding(horizontal = 16.dp, vertical = 12.dp),
            ) {
                Text(t, fontSize = 14.sp)
            }
        }
    }

    // ---------------------------------------------------------------- panels

    fun refs(type: Int): List<TrackRef> =
        tracks.groups.filter { it.type == type }.flatMap { g -> (0 until g.length).filter { g.isTrackSupported(it) }.map { TrackRef(g, it) } }

    when (panel) {
        Panel.Speed -> SidePanel(
            title = "Playback speed",
            options = SPEEDS.map { Opt(it, speedLabel(it) + if (it == 1f) " (normal)" else "") },
            isSelected = { it == speed },
            onSelect = {
                player.setPlaybackSpeed(it)
                panel = null
            },
            onDismiss = { panel = null },
        )
        Panel.Quality -> {
            val video = refs(C.TRACK_TYPE_VIDEO).sortedByDescending { it.format.height }
            val overridden = player.trackSelectionParameters.overrides.keys.any { it.type == C.TRACK_TYPE_VIDEO }
            SidePanel(
                title = "Quality",
                options = listOf(Opt<TrackRef?>(null, "Auto", "Picks the best quality your connection can handle")) +
                    video.map { Opt<TrackRef?>(it, videoLabel(it.format)) },
                isSelected = { v -> if (v == null) !overridden else overridden && v.selected },
                onSelect = { v ->
                    val b = player.trackSelectionParameters.buildUpon()
                    if (v == null) {
                        b.clearOverridesOfType(C.TRACK_TYPE_VIDEO)
                    } else {
                        b.setOverrideForType(TrackSelectionOverride(v.group.mediaTrackGroup, v.index))
                    }
                    player.trackSelectionParameters = b.build()
                    panel = null
                },
                onDismiss = { panel = null },
                message = if (video.size <= 1) {
                    if (r.sources.size > 1) "This stream has one quality. Your provider has other versions of this title under Version." else "This stream only comes in one quality."
                } else {
                    null
                },
            )
        }
        Panel.Audio -> {
            val audio = refs(C.TRACK_TYPE_AUDIO)
            SidePanel(
                title = "Audio",
                options = audio.mapIndexed { i, t -> Opt(t, audioLabel(t.format, i)) },
                isSelected = { it.selected },
                onSelect = { t ->
                    player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
                        .setOverrideForType(TrackSelectionOverride(t.group.mediaTrackGroup, t.index))
                        .build()
                    panel = null
                },
                onDismiss = { panel = null },
                message = if (audio.isEmpty()) "No audio tracks this TV can play." else null,
            )
        }
        Panel.Subs -> {
            val text = refs(C.TRACK_TYPE_TEXT)
            val disabled = player.trackSelectionParameters.disabledTrackTypes.contains(C.TRACK_TYPE_TEXT)
            val anySelected = !disabled && text.any { it.selected }
            SidePanel(
                title = "Subtitles",
                options = listOf(Opt(-1, "Off")) +
                    text.mapIndexed { i, t -> Opt(i, textLabel(t.format, i)) } +
                    listOf(Opt(-2, "Search online…", if (app.subs.configured) "OpenSubtitles" else "Needs a free OpenSubtitles key (add it from your phone)")),
                isSelected = { v -> if (v == -1) !anySelected else v >= 0 && anySelected && text[v].selected },
                onSelect = { v ->
                    when {
                        v == -1 -> {
                            player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
                                .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true).build()
                            panel = null
                        }
                        v == -2 -> panel = Panel.SubsOnline
                        else -> {
                            val t = text[v]
                            player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
                                .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false)
                                .setOverrideForType(TrackSelectionOverride(t.group.mediaTrackGroup, t.index))
                                .build()
                            panel = null
                        }
                    }
                },
                onDismiss = { panel = null },
            )
        }
        Panel.SubsOnline -> {
            val list = subResults.orEmpty()
            SidePanel(
                title = "Online subtitles",
                options = list.map { s ->
                    Opt(
                        s,
                        s.release,
                        listOfNotNull(langName(s.language) ?: s.language, "%,d downloads".format(s.downloads), if (s.hearingImpaired) "SDH" else null).joinToString(" · "),
                    )
                },
                isSelected = { false },
                onSelect = { addOnlineSub(it) },
                onDismiss = { panel = null },
                message = subMessage,
            )
        }
        Panel.Source -> SidePanel(
            title = "Version",
            options = r.sources.map { Opt(it.id, it.label) },
            isSelected = { it == currentSourceId },
            onSelect = {
                panel = null
                switchSource(it)
            },
            onDismiss = { panel = null },
            message = "Other copies of this title from your provider. Your position is kept.",
        )
        null -> Unit
    }
}

@Composable
private fun SeekBar(
    position: Long,
    duration: Long,
    modifier: Modifier,
    onSeek: (Long) -> Unit,
    onToggle: () -> Unit,
    stepFor: (KeyEvent) -> Long,
) {
    var focused by remember { mutableStateOf(false) }
    val frac = if (duration > 0) (position.toFloat() / duration).coerceIn(0f, 1f) else 0f
    Box(
        modifier
            .fillMaxWidth()
            .height(22.dp)
            .onFocusChanged { focused = it.isFocused }
            .onKeyEvent { e ->
                if (e.type != KeyEventType.KeyDown) return@onKeyEvent false
                when (e.key) {
                    Key.DirectionLeft -> {
                        onSeek(-stepFor(e))
                        true
                    }
                    Key.DirectionRight -> {
                        onSeek(stepFor(e))
                        true
                    }
                    Key.DirectionCenter, Key.Enter, Key.NumPadEnter -> {
                        onToggle()
                        true
                    }
                    else -> false
                }
            }
            .focusable(),
        contentAlignment = Alignment.CenterStart,
    ) {
        val h = if (focused) 8.dp else 5.dp
        Box(Modifier.fillMaxWidth().height(h).clip(RoundedCornerShape(4.dp)).background(Color.White.copy(alpha = 0.25f)))
        Box(Modifier.fillMaxWidth(frac).height(h).clip(RoundedCornerShape(4.dp)).background(if (focused) Color.White else Color.White.copy(alpha = 0.85f)))
        if (focused) {
            Box(
                Modifier
                    .fillMaxWidth(frac)
                    .height(18.dp),
                contentAlignment = Alignment.CenterEnd,
            ) {
                Box(Modifier.offset(x = 9.dp).size(18.dp).clip(RoundedCornerShape(50)).background(Color.White))
            }
        }
    }
}

// ---------------------------------------------------------------- player setup & labels

@androidx.annotation.OptIn(UnstableApi::class)
private fun buildPlayer(ctx: Context, app: CouchApp): ExoPlayer {
    val http = OkHttpDataSource.Factory(app.http).setUserAgent(app.prefs.config.value.userAgent)
    val data = DefaultDataSource.Factory(ctx, http)
    val extractors = DefaultExtractorsFactory()
        .setTsExtractorFlags(DefaultTsPayloadReaderFactory.FLAG_ALLOW_NON_IDR_KEYFRAMES or DefaultTsPayloadReaderFactory.FLAG_DETECT_ACCESS_UNITS)
        .setConstantBitrateSeekingEnabled(true)
    val renderers = DefaultRenderersFactory(ctx)
        .setExtensionRendererMode(DefaultRenderersFactory.EXTENSION_RENDERER_MODE_ON)
        .setEnableDecoderFallback(true)
    val loadControl = DefaultLoadControl.Builder()
        .setBufferDurationsMs(20_000, 60_000, 2_000, 4_000)
        .build()
    return ExoPlayer.Builder(ctx, renderers)
        .setMediaSourceFactory(DefaultMediaSourceFactory(data, extractors))
        .setLoadControl(loadControl)
        .setAudioAttributes(
            AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_MOVIE).build(),
            true,
        )
        .setSeekBackIncrementMs(10_000)
        .setSeekForwardIncrementMs(10_000)
        .build()
}

@androidx.annotation.OptIn(UnstableApi::class)
private fun friendlyError(e: PlaybackException): String {
    val why = when (e.errorCode) {
        PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS ->
            "The provider refused the stream. You may be at your connection limit, or the provider blocks this player (try another Player identity in Settings)."
        PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED,
        PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT,
        -> "Couldn't connect to the stream. Check the TV's internet connection."
        PlaybackException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED,
        PlaybackException.ERROR_CODE_DECODER_INIT_FAILED,
        PlaybackException.ERROR_CODE_DECODER_QUERY_FAILED,
        -> "This TV can't decode this video or audio format. Try another version."
        PlaybackException.ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED,
        PlaybackException.ERROR_CODE_PARSING_CONTAINER_MALFORMED,
        PlaybackException.ERROR_CODE_PARSING_MANIFEST_MALFORMED,
        -> "The stream is damaged or in an unsupported format."
        else -> "Playback failed."
    }
    return "$why\n(${e.errorCodeName})"
}

private fun speedLabel(s: Float): String =
    if (s == s.toInt().toFloat()) "${s.toInt()}x" else "${s}x"

private fun resizeLabel(mode: Int): String = when (mode) {
    AspectRatioFrameLayout.RESIZE_MODE_ZOOM -> "Zoom"
    AspectRatioFrameLayout.RESIZE_MODE_FILL -> "Stretch"
    else -> "Fit"
}

fun langName(code: String?): String? {
    if (code.isNullOrBlank() || code == "und") return null
    val name = Locale.forLanguageTag(code).getDisplayLanguage(Locale.ENGLISH)
    return name.ifBlank { code }
}

private fun videoLabel(f: Format): String {
    val res = if (f.height > 0) "${f.height}p" else "Video"
    val rate = if (f.bitrate > 0) " · %.1f Mbps".format(f.bitrate / 1_000_000.0) else ""
    val fps = if (f.frameRate > 0) " · ${f.frameRate.toInt()} fps" else ""
    return res + rate + fps
}

private fun audioLabel(f: Format, i: Int): String {
    val channels = when (f.channelCount) {
        1 -> "Mono"
        2 -> "Stereo"
        6 -> "5.1"
        8 -> "7.1"
        else -> if (f.channelCount > 0) "${f.channelCount} ch" else null
    }
    val codec = when (f.sampleMimeType) {
        MimeTypes.AUDIO_AC3 -> "Dolby Digital"
        MimeTypes.AUDIO_E_AC3 -> "Dolby Digital+"
        MimeTypes.AUDIO_E_AC3_JOC -> "Dolby Atmos"
        MimeTypes.AUDIO_DTS, MimeTypes.AUDIO_DTS_HD -> "DTS"
        MimeTypes.AUDIO_TRUEHD -> "TrueHD"
        MimeTypes.AUDIO_AAC -> "AAC"
        MimeTypes.AUDIO_MPEG -> "MP3"
        MimeTypes.AUDIO_OPUS -> "Opus"
        else -> null
    }
    val name = f.label ?: langName(f.language) ?: "Track ${i + 1}"
    return listOfNotNull(name, channels, codec).joinToString(" · ")
}

private fun textLabel(f: Format, i: Int): String {
    val name = f.label ?: langName(f.language) ?: "Subtitles ${i + 1}"
    val forced = if (f.selectionFlags and C.SELECTION_FLAG_FORCED != 0) " (forced)" else ""
    return name + forced
}
