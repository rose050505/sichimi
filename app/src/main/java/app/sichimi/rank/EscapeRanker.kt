package app.sichimi.rank

import android.content.Context
import android.util.Log
import app.sichimi.core.EscapeMoment
import app.sichimi.gate.EscapeRule
import app.sichimi.gate.Week
import app.sichimi.settings.SettingsStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.time.ZoneId

/**
 * 가장 빠른 대피 랭킹(설계 15.6). 「전국 랭킹에 올리기」를 켰을 때만(점수 랭킹과 같은 동의), 이번 주 이 곡의
 * 내 기록이 새로 빨라지면 올린다. 보내는 것은 별명, 대피 시간(ms), 원인(문·좌석·창문), 시각뿐이다.
 * 올리지 못한 것은 기기 안(`escape` 설정)에 남겼다가 앱을 켤 때 다시 올린다.
 */
class EscapeRanker(
    context: Context,
    private val scope: CoroutineScope,
    private val ranking: RankingClient,
    private val settings: SettingsStore,
) {
    private val prefs = context.getSharedPreferences("escape", Context.MODE_PRIVATE)
    private val _version = MutableStateFlow(0)
    /** 올릴 때마다 오른다(화면이 다시 읽는 신호). */
    val version: StateFlow<Int> = _version.asStateFlow()

    fun start() { if (allowed()) retryPending() }

    private fun allowed() = settings.state.value.rankUpload && ranking.configured

    fun onEscape(m: EscapeMoment) {
        if (!EscapeRule.valid(m.reactionMs)) return
        val week = Week.key(m.at, ZoneId.systemDefault())
        val key = "$week|${m.songId}"
        val best = prefs.getString(key, null)?.let { JSONObject(it).getLong("ms") }
        if (!EscapeRule.improves(m.reactionMs, best)) return
        prefs.edit().putString(key, JSONObject().put("ms", m.reactionMs).put("cause", m.cause).put("pending", true).toString()).apply()
        Log.i(TAG, "이번 주 새 대피 기록: ${m.songId} ${m.reactionMs}ms")
        if (allowed()) upload(week, m.songId)
    }

    /** 랭킹 올리기를 켰거나 앱을 켤 때: 못 올린 기록을 올린다. */
    fun retryPending() {
        val thisWeek = Week.key(System.currentTimeMillis(), ZoneId.systemDefault())
        for ((key, v) in prefs.all) {
            val (week, song) = key.split('|').let { it[0] to it.getOrElse(1) { "" } }
            val j = runCatching { JSONObject(v as String) }.getOrNull() ?: continue
            if (week == thisWeek && j.optBoolean("pending")) upload(week, song)
        }
    }

    private fun upload(week: String, songId: String) {
        val key = "$week|$songId"
        val j = prefs.getString(key, null)?.let { JSONObject(it) } ?: return
        scope.launch {
            runCatching { ranking.submitEscape(week, songId, settings.state.value.nickname, j.getLong("ms"), j.getString("cause")) }
                .onSuccess { prefs.edit().putString(key, j.put("pending", false).toString()).apply(); _version.value++ }
                .onFailure { Log.w(TAG, "대피 기록 올리기 실패(다음에 다시): $it") }
        }
    }

    companion object { const val TAG = "SC.Escape" }
}
