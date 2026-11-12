package app.sichimi.rank

import android.util.Log
import app.sichimi.settings.SettingsStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * 내 녹음 올리기·지우기. 화면 탭을 옮겨도 끊기지 않게 앱 범위에서 돈다.
 * [status]: 곡 id → 화면에 보일 한 줄(올리는 중, 올렸습니다, 실패 등).
 */
class TakeUploader(
    private val scope: CoroutineScope,
    private val ranking: RankingClient,
    private val takes: TakeStore,
    private val settings: SettingsStore,
) {
    private val _status = MutableStateFlow<Map<String, String>>(emptyMap())
    val status: StateFlow<Map<String, String>> = _status.asStateFlow()

    /** 서버의 녹음이 바뀔 때마다 오른다. 랭킹 화면이 다시 읽는 신호. */
    private val _version = MutableStateFlow(0)
    val version: StateFlow<Int> = _version.asStateFlow()

    private fun say(songId: String, text: String?) = _status.update { if (text == null) it - songId else it + (songId to text) }

    fun upload(songId: String) {
        val t = takes.find(songId) ?: return
        val audio = takes.bytes(songId) ?: return
        if (!settings.state.value.rankUpload || !ranking.configured) return
        say(songId, "올리는 중…")
        scope.launch {
            runCatching { ranking.submitTake(songId, settings.state.value.nickname, t, audio) }
                .onSuccess { takes.markUploaded(songId, true); say(songId, "올렸습니다. 전국 랭킹에서 누구나 들을 수 있습니다") }
                .onFailure { Log.w(TAG, "녹음 올리기 실패: $it"); say(songId, "녹음을 올리지 못했습니다(점수는 그대로입니다)") }
            _version.value++
        }
    }

    /** 내 녹음을 지운다: 서버(올렸던 것)와 차 안 파일 모두. */
    fun delete(songId: String) {
        say(songId, "지우는 중…")
        scope.launch {
            val wasUploaded = takes.find(songId)?.uploadedAt != null
            val server = if (ranking.configured) runCatching { ranking.deleteTake(songId) } else Result.success(Unit)
            server.onFailure { Log.w(TAG, "서버 녹음 지우기 실패: $it") }
            if (server.isSuccess || !wasUploaded) {
                takes.delete(songId)
                say(songId, null)
            } else {
                say(songId, "서버에서 지우지 못했습니다. 네트워크를 확인하고 다시 눌러 주세요")
            }
            _version.value++
        }
    }

    /** 설정의 「서버의 내 녹음 모두 지우기」. 차 안 녹음은 남기고 "안 올림"으로 돌린다. */
    fun deleteAllOnServer(songIds: List<String>, done: (Boolean) -> Unit) {
        scope.launch {
            var ok = true
            for (id in songIds) {
                runCatching { ranking.deleteTake(id) }
                    .onSuccess { takes.markUploaded(id, false) }
                    .onFailure { ok = false; Log.w(TAG, "서버 녹음 지우기 실패 $id: $it") }
            }
            _version.value++
            done(ok)
        }
    }

    companion object { const val TAG = "SC.Take" }
}
