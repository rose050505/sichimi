package app.sichimi.audio

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.util.Log
import androidx.annotation.OptIn
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.DefaultAudioSink
import androidx.media3.exoplayer.audio.DefaultAudioTrackBufferSizeProvider
import androidx.media3.session.LibraryResult
import androidx.media3.session.MediaLibraryService
import androidx.media3.session.MediaSession
import app.sichimi.MainActivity
import app.sichimi.SichimiApp
import app.sichimi.core.PlayerSnapshot
import app.sichimi.songs.Song
import com.google.common.collect.ImmutableList
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture

object AudioConfig {
    /** AudioTrack 버퍼. 짧을수록 가수 음량 변화가 빨리 들린다. 40ms에서 언더런 0(검증기록 G2). 끊김이 생기면 늘린다. */
    const val PCM_BUFFER_US = 40_000

    /**
     * 믹서가 음량을 바꾼 뒤 소리가 차의 오디오 출력에 닿기까지 더하는 값(대피 시간 기록용).
     * Pleos Connect Emulator 2.1.0에서 버퍼 40ms일 때 AudioFlinger가 보고한 트랙 지연(검증기록 G2).
     */
    const val OUTPUT_LATENCY_MS = 232L
}

/**
 * 재생 서비스. Media3 MediaLibraryService라 MediaBrowserService와 MediaSession을 함께 제공한다.
 * 시스템 미디어 화면, 스티어링 휠 키, 미디어 버튼이 이 세션으로 들어온다.
 * 스스로 재생을 시작하지 않는다. 문지기는 보컬 음량만 바꾼다.
 */
@OptIn(UnstableApi::class)
class PlaybackService : MediaLibraryService() {

    private var session: MediaLibrarySession? = null
    private var itemCounter = 0L
    private var currentKey: String? = null

    private val graph get() = (application as SichimiApp).graph

    override fun onCreate() {
        super.onCreate()
        // Pleos 가이드: bind/unbind만 받으면 onTaskRemoved를 못 받을 수 있으니 Started 상태로 둔다.
        runCatching { startService(Intent(this, PlaybackService::class.java)) }
            .onFailure { Log.w(TAG, "startService 실패(백그라운드 시작 제한일 수 있음): $it") }

        val player = buildPlayer(this, graph.mixer)
        player.addListener(listener(player))
        graph.positionProvider = { player.currentPosition }

        val activity = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        session = MediaLibrarySession.Builder(this, player, LibraryCallback())
            .setSessionActivity(activity)
            .build()
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaLibrarySession? = session

    override fun onTaskRemoved(rootIntent: Intent?) {
        // Pleos 가이드 순서: stopMedia -> stopSelf -> super.onTaskRemoved
        session?.player?.stop()
        stopSelf()
        super.onTaskRemoved(rootIntent)
    }

    override fun onDestroy() {
        session?.run {
            player.release()
            release()
        }
        session = null
        graph.positionProvider = { 0L }
        graph.controller.player.value = PlayerSnapshot()
        super.onDestroy()
    }

    private fun listener(player: ExoPlayer) = object : Player.Listener {
        override fun onEvents(p: Player, events: Player.Events) {
            val item = p.currentMediaItem
            if (events.contains(Player.EVENT_MEDIA_ITEM_TRANSITION) || (item != null && currentKey == null)) {
                currentKey = item?.let { "${it.mediaId}#${++itemCounter}" }
            }
            if (item == null) currentKey = null
            graph.controller.player.value = PlayerSnapshot(isPlaying = p.isPlaying, songId = item?.mediaId, itemKey = currentKey)
        }

        override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
            Log.i(TAG, "playWhenReady=$playWhenReady reason=$reason")
            // Pleos 가이드: 오디오 포커스를 영구히 잃으면 세션이 active로 남지 않도록 정지한다.
            if (!playWhenReady && reason == Player.PLAY_WHEN_READY_CHANGE_REASON_AUDIO_FOCUS_LOSS) {
                Log.i(TAG, "오디오 포커스 영구 손실 -> stop")
                player.stop()
            }
        }

        override fun onPlaybackSuppressionReasonChanged(reason: Int) {
            Log.i(TAG, "재생 억제 사유=$reason (1=일시적 포커스 손실)")
        }
    }

    private inner class LibraryCallback : MediaLibrarySession.Callback {
        private val songs get() = graph.songs

        override fun onGetLibraryRoot(
            session: MediaLibrarySession, browser: MediaSession.ControllerInfo, params: LibraryParams?,
        ): ListenableFuture<LibraryResult<MediaItem>> = Futures.immediateFuture(LibraryResult.ofItem(rootItem(), params))

        override fun onGetChildren(
            session: MediaLibrarySession, browser: MediaSession.ControllerInfo, parentId: String,
            page: Int, pageSize: Int, params: LibraryParams?,
        ): ListenableFuture<LibraryResult<ImmutableList<MediaItem>>> {
            val children = when (parentId) {
                ROOT_ID -> listOf(songsFolder())
                SONGS_ID -> songs.songs().map { it.toMediaItem() }
                else -> emptyList()
            }
            return Futures.immediateFuture(LibraryResult.ofItemList(ImmutableList.copyOf(children), params))
        }

        override fun onGetItem(
            session: MediaLibrarySession, browser: MediaSession.ControllerInfo, mediaId: String,
        ): ListenableFuture<LibraryResult<MediaItem>> {
            val item = when (mediaId) {
                ROOT_ID -> rootItem()
                SONGS_ID -> songsFolder()
                else -> songs.find(mediaId)?.toMediaItem()
            }
            return Futures.immediateFuture(
                if (item != null) LibraryResult.ofItem(item, null) else LibraryResult.ofError(LibraryResult.RESULT_ERROR_BAD_VALUE)
            )
        }

        override fun onSearch(
            session: MediaLibrarySession, browser: MediaSession.ControllerInfo, query: String, params: LibraryParams?,
        ): ListenableFuture<LibraryResult<Void>> {
            session.notifySearchResultChanged(browser, query, search(query).size, params)
            return Futures.immediateFuture(LibraryResult.ofVoid())
        }

        override fun onGetSearchResult(
            session: MediaLibrarySession, browser: MediaSession.ControllerInfo, query: String,
            page: Int, pageSize: Int, params: LibraryParams?,
        ): ListenableFuture<LibraryResult<ImmutableList<MediaItem>>> =
            Futures.immediateFuture(LibraryResult.ofItemList(ImmutableList.copyOf(search(query).map { it.toMediaItem() }), params))

        override fun onAddMediaItems(
            mediaSession: MediaSession, controller: MediaSession.ControllerInfo, mediaItems: MutableList<MediaItem>,
        ): ListenableFuture<MutableList<MediaItem>> {
            // 미디어 ID나 검색어로 들어온 요청을 실제 곡으로 바꾼다(미디어 ID 기반 재생, 검색 기반 재생).
            val resolved = mediaItems.flatMap { req ->
                songs.find(req.mediaId)?.let { listOf(it.toMediaItem()) }
                    ?: req.requestMetadata.searchQuery?.let { q -> search(q).map { it.toMediaItem() } }
                    ?: emptyList()
            }
            return Futures.immediateFuture(resolved.toMutableList())
        }

        override fun onPlaybackResumption(
            mediaSession: MediaSession, controller: MediaSession.ControllerInfo,
        ): ListenableFuture<MediaSession.MediaItemsWithStartPosition> {
            // 시스템 재개 요청에만 응한다. 곡 목록을 처음부터 돌려준다.
            val items = songs.songs().map { it.toMediaItem() }
            return Futures.immediateFuture(MediaSession.MediaItemsWithStartPosition(items, 0, C.TIME_UNSET))
        }

        private fun search(q: String): List<Song> {
            val key = q.trim()
            if (key.isEmpty()) return songs.songs()
            return songs.songs().filter { it.title.contains(key, true) || it.artist.contains(key, true) }
        }
    }

    private fun rootItem() = MediaItem.Builder().setMediaId(ROOT_ID).setMediaMetadata(
        MediaMetadata.Builder().setTitle("시치미").setIsBrowsable(true).setIsPlayable(false)
            .setMediaType(MediaMetadata.MEDIA_TYPE_FOLDER_MIXED).build()
    ).build()

    private fun songsFolder() = MediaItem.Builder().setMediaId(SONGS_ID).setMediaMetadata(
        MediaMetadata.Builder().setTitle("모든 곡").setIsBrowsable(true).setIsPlayable(false)
            .setMediaType(MediaMetadata.MEDIA_TYPE_FOLDER_PLAYLISTS).build()
    ).build()

    companion object {
        const val TAG = "SC.Player"
        const val ROOT_ID = "root"
        const val SONGS_ID = "songs"

        fun Song.toMediaItem(): MediaItem = MediaItem.Builder()
            .setMediaId(id)
            .setUri(uri)
            .setMediaMetadata(
                MediaMetadata.Builder()
                    .setTitle(title)
                    .setArtist(artist)
                    .setIsBrowsable(false)
                    .setIsPlayable(true)
                    .setMediaType(MediaMetadata.MEDIA_TYPE_MUSIC)
                    .setExtras(Bundle().apply { putLong("durationMs", durationMs) })
                    .build()
            )
            .build()

        fun buildPlayer(context: Context, mixer: StemMixProcessor): ExoPlayer {
            val renderers = object : DefaultRenderersFactory(context) {
                override fun buildAudioSink(
                    context: Context, enableFloatOutput: Boolean, enableAudioTrackPlaybackParams: Boolean,
                ): AudioSink = DefaultAudioSink.Builder(context)
                    .setAudioProcessors(arrayOf(mixer))
                    .setAudioTrackBufferSizeProvider(
                        DefaultAudioTrackBufferSizeProvider.Builder()
                            .setMinPcmBufferDurationUs(AudioConfig.PCM_BUFFER_US)
                            .setMaxPcmBufferDurationUs(AudioConfig.PCM_BUFFER_US)
                            .setPcmBufferMultiplicationFactor(1)
                            .build()
                    )
                    .build()
            }
            val attrs = AudioAttributes.Builder()
                .setUsage(C.USAGE_MEDIA)
                .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
                .build()
            return ExoPlayer.Builder(context, renderers)
                .setAudioAttributes(attrs, /* handleAudioFocus = */ true)
                .setHandleAudioBecomingNoisy(true)
                .build()
        }
    }
}
