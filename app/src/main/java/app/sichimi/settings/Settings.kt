package app.sichimi.settings

import android.content.Context
import app.sichimi.gate.GateConfig
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class AppSettings(
    val enabled: Boolean = true,
    /** 가이드 음량(%) 0~40. */
    val guidePercent: Int = 20,
    /** 대기 시간(초) 3·5·8. */
    val dwellSeconds: Int = 5,
    /** 창문 기준(%). */
    val windowThresholdPct: Int = 2,
    /** 후진·방향지시등 때 가수를 잠깐 돌려놓는다. */
    val safetyReturn: Boolean = true,
    /** 착석 값 뒤집기. Pleos Connect Emulator 2.1.0에서는 반전돼 오므로 기본으로 켠다(검증기록 G1). */
    val invertSeatOccupancy: Boolean = true,
    /** 첫 소절 앞 박자 세기(카운트인). 노래방일 때만 들린다. */
    val countIn: Boolean = true,
    /** 노래 점수(마이크). 혼자만의 노래방일 때만 마이크를 켠다. 동의를 받고 켜므로 기본은 끔. */
    val scoring: Boolean = false,
    /** 전국 랭킹에 별명과 곡별 최고 점수를 올린다. 기본은 끔. */
    val rankUpload: Boolean = false,
    /** 랭킹에 보일 별명(1~12자). */
    val nickname: String = "",
    /** 고스트 목소리 크기(%) 5·10·15. 가이드 음량보다 작게만 적용(GhostGain.level). */
    val ghostPercent: Int = 10,
) {
    fun toGateConfig() = GateConfig(
        enabled = enabled,
        guideGain = guidePercent.coerceIn(0, 40) / 100f,
        dwellMs = dwellSeconds * 1000L,
        windowThresholdPct = windowThresholdPct,
        safetyReturn = safetyReturn,
    )
}

/** 기기 안의 SharedPreferences에만 둔다. */
class SettingsStore(context: Context) {
    private val prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)
    private val _state = MutableStateFlow(read())
    val state: StateFlow<AppSettings> = _state.asStateFlow()

    private fun read() = AppSettings(
        enabled = prefs.getBoolean("enabled", true),
        guidePercent = prefs.getInt("guidePercent", 20),
        dwellSeconds = prefs.getInt("dwellSeconds", 5),
        windowThresholdPct = prefs.getInt("windowThresholdPct", 2),
        safetyReturn = prefs.getBoolean("safetyReturn", true),
        invertSeatOccupancy = prefs.getBoolean("invertSeatOccupancy", true),
        countIn = prefs.getBoolean("countIn", true),
        scoring = prefs.getBoolean("scoring", false),
        rankUpload = prefs.getBoolean("rankUpload", false),
        nickname = prefs.getString("nickname", null) ?: "운전자${(1000..9999).random()}".also { prefs.edit().putString("nickname", it).apply() },
        ghostPercent = prefs.getInt("ghostPercent", 10),
    )

    fun update(change: (AppSettings) -> AppSettings) {
        val next = change(_state.value)
        prefs.edit()
            .putBoolean("enabled", next.enabled)
            .putInt("guidePercent", next.guidePercent)
            .putInt("dwellSeconds", next.dwellSeconds)
            .putInt("windowThresholdPct", next.windowThresholdPct)
            .putBoolean("safetyReturn", next.safetyReturn)
            .putBoolean("invertSeatOccupancy", next.invertSeatOccupancy)
            .putBoolean("countIn", next.countIn)
            .putBoolean("scoring", next.scoring)
            .putBoolean("rankUpload", next.rankUpload)
            .putString("nickname", next.nickname)
            .putInt("ghostPercent", next.ghostPercent)
            .apply()
        _state.value = next
    }
}
