package app.sichimi.ui

import android.content.ComponentName
import android.content.Context
import androidx.core.content.ContextCompat
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import app.sichimi.audio.PlaybackService
import app.sichimi.audio.PlaybackService.Companion.toMediaItem
import app.sichimi.songs.SongSource
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class QueueEntry(val songId: String, val title: String, val artist: String)

data class PlayerUi(
    val connected: Boolean = false,
    val isPlaying: Boolean = false,
    val songId: String? = null,
    val title: String = "",
    val artist: String = "",
    val durationMs: Long = 0,
    val queue: List<QueueEntry> = emptyList(),
    val currentIndex: Int = -1,
)

/** 화면에서 재생 서비스로 가는 통로(Media3 MediaController). 재생은 사용자가 누를 때만 시작한다. */
class PlayerConnection(private val context: Context, private val songs: SongSource) {
    private var controller: MediaController? = null
    private val _state = MutableStateFlow(PlayerUi())
    val state: StateFlow<PlayerUi> = _state.asStateFlow()

    private val listener = object : Player.Listener {
        override fun onEvents(player: Player, events: Player.Events) = refresh()
    }

    fun connect() {
        if (controller != null) return
        val token = SessionToken(context, ComponentName(context, PlaybackService::class.java))
        val future = MediaController.Builder(context, token).buildAsync()
        future.addListener({
            runCatching { future.get() }.getOrNull()?.let { c ->
                controller = c
                c.addListener(listener)
                refresh()
            }
        }, ContextCompat.getMainExecutor(context))
    }

    fun release() {
        controller?.removeListener(listener)
        controller?.release()
        controller = null
        _state.value = PlayerUi()
    }

    fun positionMs(): Long = controller?.currentPosition ?: 0L

    fun playPause() {
        val c = controller ?: return
        if (c.mediaItemCount == 0) {
            loadAll(startIndex = 0)
            c.play()
            return
        }
        if (c.playbackState == Player.STATE_IDLE) c.prepare()
        if (c.isPlaying) c.pause() else c.play()
    }

    fun next() = controller?.let { if (it.hasNextMediaItem()) it.seekToNextMediaItem() }
    fun previous() = controller?.seekToPrevious()

    /** 이 곡부터 지금 재생한다. */
    fun playNow(songId: String) {
        val c = controller ?: return
        val idx = (0 until c.mediaItemCount).firstOrNull { c.getMediaItemAt(it).mediaId == songId }
        if (idx != null) c.seekTo(idx, 0) else {
            val item = songs.find(songId)?.toMediaItem() ?: return
            val at = if (c.mediaItemCount == 0) 0 else c.currentMediaItemIndex + 1
            c.addMediaItem(at, item)
            c.seekTo(at, 0)
        }
        if (c.playbackState == Player.STATE_IDLE) c.prepare()
        c.play()
    }

    /** 대기열 끝에 예약한다. 재생은 시작하지 않는다. */
    fun reserve(songId: String) {
        val c = controller ?: return
        val item = songs.find(songId)?.toMediaItem() ?: return
        c.addMediaItem(item)
        if (c.playbackState == Player.STATE_IDLE) c.prepare()
    }

    fun removeAt(index: Int) {
        val c = controller ?: return
        if (index in 0 until c.mediaItemCount && index != c.currentMediaItemIndex) c.removeMediaItem(index)
    }

    private fun loadAll(startIndex: Int) {
        val c = controller ?: return
        val items: List<MediaItem> = songs.songs().map { it.toMediaItem() }
        c.setMediaItems(items, startIndex, 0)
        c.prepare()
    }

    private fun refresh() {
        val c = controller ?: return
        val md = c.mediaMetadata
        val item = c.currentMediaItem
        val song = item?.mediaId?.let(songs::find)
        _state.value = PlayerUi(
            connected = true,
            isPlaying = c.isPlaying,
            songId = item?.mediaId,
            title = md.title?.toString() ?: song?.title.orEmpty(),
            artist = md.artist?.toString() ?: song?.artist.orEmpty(),
            durationMs = song?.durationMs ?: 0,
            queue = (0 until c.mediaItemCount).map { i ->
                val m = c.getMediaItemAt(i)
                QueueEntry(m.mediaId, m.mediaMetadata.title?.toString().orEmpty(), m.mediaMetadata.artist?.toString().orEmpty())
            },
            currentIndex = c.currentMediaItemIndex,
        )
    }
}
