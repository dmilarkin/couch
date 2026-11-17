package app.couch.tv.remote

import app.couch.tv.data.PlayRequest
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow

sealed interface RemoteCommand {
    data class Play(val req: PlayRequest) : RemoteCommand
    data class Open(val itemId: String) : RemoteCommand
    data class Control(val cmd: String, val value: Double = 0.0) : RemoteCommand
}

data class NowPlaying(
    val title: String,
    val subtitle: String?,
    val image: String?,
    val kind: String,
    val positionMs: Long,
    val durationMs: Long,
    val playing: Boolean,
    val speed: Float,
    val hasNext: Boolean,
    val subtitles: List<SubTrack> = emptyList(),
)

/** One subtitle track the TV can show, as listed on the phone remote. */
data class SubTrack(val index: Int, val label: String, val selected: Boolean)

/** Hands commands from the phone (web server thread) to the UI. */
class RemoteBus {
    private val _commands = MutableSharedFlow<RemoteCommand>(extraBufferCapacity = 32)
    val commands: SharedFlow<RemoteCommand> = _commands

    @Volatile
    var pending: PlayRequest? = null

    @Volatile
    var pendingAt: Long = 0L

    fun send(c: RemoteCommand) {
        _commands.tryEmit(c)
    }

    /** A play request that arrived while the app was in the background (kept for 3 minutes). */
    fun takePending(): PlayRequest? {
        val p = pending ?: return null
        pending = null
        return if (System.currentTimeMillis() - pendingAt < 3 * 60_000) p else null
    }
}
