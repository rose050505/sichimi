package app.sichimi.gate

/** 문지기 상태. */
enum class GateState {
    /** 평범한 음악 플레이어. 가수 음량 100%. */
    NORMAL,

    /** 혼자·밀폐가 됐고 대기 타이머가 도는 중. 가수 음량 100%. */
    ARMING,

    /** 노래방. 가수 음량은 가이드 음량. */
    KARAOKE,

    /** 노래방 중 후진·방향지시등으로 잠깐 가수를 돌려놓음. 가수 음량 100%. */
    RETURN,
}

data class GateConfig(
    /** 기능 전체 켜기. 끄면 언제나 [GateState.NORMAL]. */
    val enabled: Boolean = true,
    /** 노래방일 때 가수 음량(0.0~0.4). */
    val guideGain: Float = 0.20f,
    /** 대기 시간. */
    val dwellMs: Long = 5_000,
    /** 이 값(%) 이하로 열린 창문은 닫힌 것으로 본다. */
    val windowThresholdPct: Int = 2,
    /** 후진(·방향지시등) 때 가수를 잠깐 돌려놓는 안전 장치. */
    val safetyReturn: Boolean = true,
) {
    init {
        require(guideGain in 0f..MAX_GUIDE_GAIN) { "guideGain은 0~$MAX_GUIDE_GAIN" }
        require(dwellMs >= 0) { "dwellMs는 0 이상" }
        require(windowThresholdPct in 0..100) { "windowThresholdPct는 0~100" }
    }

    companion object {
        const val MAX_GUIDE_GAIN = 0.40f
    }
}

/** 문지기가 바깥(오디오, 기록, 화면)에 요청하는 일. */
sealed interface GateEffect {
    /** 가수 음량을 [targetGain]까지 [durationMs]에 걸쳐 바꾼다. */
    data class VocalRamp(val targetGain: Float, val durationMs: Long) : GateEffect

    /**
     * 들킬 뻔했다.
     * @property signalAtMs 원인 신호가 들어온 시각.
     * @property vocalWasDown 이 순간 가수 음량이 내려가 있었는가. 잠시 복귀 중이었다면 false이고 대피 시간이 없다.
     */
    data class Exposure(val cause: ExposureCause, val signalAtMs: Long, val vocalWasDown: Boolean) : GateEffect

    data class StateChanged(val from: GateState, val to: GateState, val atMs: Long) : GateEffect
}

/**
 * 문지기 상태 기계. Android에 기대지 않는다.
 *
 * 차 신호가 바뀔 때마다 [update], 설정이 바뀌면 [updateConfig], 시간이 흘렀으면 [tick]을 부른다.
 * 다음에 [tick]을 불러야 하는 시각은 [nextDeadlineMs]가 알려 준다.
 * 모든 시각은 같은 단조 시계(밀리초)를 쓴다.
 */
class PrivacyGate(
    config: GateConfig = GateConfig(),
    private val timings: Timings = Timings(),
) {
    data class Timings(
        /** 노래방이 열릴 때 가수 음량을 내리는 시간. */
        val rampDownMs: Long = 600,
        /** 들킬 뻔할 때 가수 음량을 올리는 시간. 0.15초 안. */
        val exposureRampUpMs: Long = 120,
        /** 잠시 복귀 때 가수 음량을 올리는 시간. */
        val returnRampUpMs: Long = 200,
        /** 조작이 끝나고 노래방으로 돌아가기까지 기다리는 시간. */
        val returnHoldMs: Long = 1_500,
        /** 들킬 뻔한 직후의 대기 시간. */
        val postExposureDwellMs: Long = 8_000,
    )

    var config: GateConfig = config
        private set

    var state: GateState = GateState.NORMAL
        private set

    var input: CabinInput = CabinInput.INITIAL
        private set

    /** 들킬 뻔한 뒤 아직 노래방이 다시 열리지 않았다. 이때 대기 시간은 8초 이상이다. */
    var cautious: Boolean = false
        private set

    // 대기 타이머: 재생 중일 때만 흐른다.
    private var armedElapsedMs = 0L
    private var armedRunningSince: Long? = null
    private var armingDwellMs = 0L

    // 잠시 복귀: 조작이 끝난 시각.
    private var operatingEndedAt: Long? = null

    fun update(newInput: CabinInput, nowMs: Long): List<GateEffect> {
        input = newInput
        return evaluate(nowMs)
    }

    fun updateConfig(newConfig: GateConfig, nowMs: Long): List<GateEffect> {
        val old = config
        config = newConfig
        val effects = mutableListOf<GateEffect>()
        if (state == GateState.KARAOKE && old.guideGain != newConfig.guideGain) {
            effects += GateEffect.VocalRamp(newConfig.guideGain, timings.rampDownMs)
        }
        effects += evaluate(nowMs)
        return effects
    }

    fun tick(nowMs: Long): List<GateEffect> = evaluate(nowMs)

    /** 다음에 [tick]을 불러야 할 시각. 기다릴 것이 없으면 null. */
    fun nextDeadlineMs(): Long? = when (state) {
        GateState.ARMING -> armedRunningSince?.let { it + (armingDwellMs - armedElapsedMs) }
        GateState.RETURN -> operatingEndedAt?.let { it + timings.returnHoldMs }
        else -> null
    }

    /** 지금 가수 음량이 얼마여야 하는가. */
    fun targetVocalGain(): Float = if (state == GateState.KARAOKE) config.guideGain else 1f

    private fun operating(): Boolean =
        config.safetyReturn && (input.gear == Gear.REVERSE || input.turnSignalOn || input.arrivingSoon)

    private fun solitude(): Boolean =
        input.isSolo() && input.isSealed(config.windowThresholdPct)

    private fun evaluate(nowMs: Long): List<GateEffect> {
        val effects = mutableListOf<GateEffect>()
        // 한 번에 여러 단계를 건너는 일은 없지만, 안전하게 안정될 때까지 돈다.
        repeat(4) {
            val before = state
            step(nowMs, effects)
            if (state == before) return effects
        }
        return effects
    }

    private fun step(nowMs: Long, effects: MutableList<GateEffect>) {
        when (state) {
            GateState.NORMAL -> {
                if (config.enabled && solitude() && input.playing && !operating()) {
                    armingDwellMs = if (cautious) maxOf(config.dwellMs, timings.postExposureDwellMs) else config.dwellMs
                    armedElapsedMs = 0
                    armedRunningSince = nowMs
                    moveTo(GateState.ARMING, nowMs, effects)
                }
            }

            GateState.ARMING -> {
                if (!config.enabled || !solitude() || operating()) {
                    clearArming()
                    moveTo(GateState.NORMAL, nowMs, effects)
                    return
                }
                if (!input.playing) {
                    // 일시정지: 상태는 그대로, 타이머는 멈춘다.
                    armedRunningSince?.let { armedElapsedMs += nowMs - it }
                    armedRunningSince = null
                    return
                }
                val since = armedRunningSince ?: nowMs.also { armedRunningSince = it }
                if (armedElapsedMs + (nowMs - since) >= armingDwellMs) {
                    clearArming()
                    cautious = false
                    moveTo(GateState.KARAOKE, nowMs, effects)
                    effects += GateEffect.VocalRamp(config.guideGain, timings.rampDownMs)
                }
            }

            GateState.KARAOKE -> {
                if (!config.enabled) {
                    moveTo(GateState.NORMAL, nowMs, effects)
                    effects += GateEffect.VocalRamp(1f, timings.exposureRampUpMs)
                    return
                }
                val cause = input.breachCause(config.windowThresholdPct)
                if (cause != null) {
                    moveTo(GateState.NORMAL, nowMs, effects)
                    effects += GateEffect.VocalRamp(1f, timings.exposureRampUpMs)
                    recordExposure(cause, nowMs, vocalWasDown = true, effects)
                    return
                }
                if (operating()) {
                    operatingEndedAt = null
                    moveTo(GateState.RETURN, nowMs, effects)
                    effects += GateEffect.VocalRamp(1f, timings.returnRampUpMs)
                }
            }

            GateState.RETURN -> {
                if (!config.enabled) {
                    operatingEndedAt = null
                    moveTo(GateState.NORMAL, nowMs, effects)
                    return
                }
                val cause = input.breachCause(config.windowThresholdPct)
                if (cause != null) {
                    operatingEndedAt = null
                    moveTo(GateState.NORMAL, nowMs, effects)
                    recordExposure(cause, nowMs, vocalWasDown = false, effects)
                    return
                }
                if (operating()) {
                    operatingEndedAt = null
                    return
                }
                val ended = operatingEndedAt ?: nowMs.also { operatingEndedAt = it }
                if (nowMs - ended >= timings.returnHoldMs) {
                    operatingEndedAt = null
                    moveTo(GateState.KARAOKE, nowMs, effects)
                    effects += GateEffect.VocalRamp(config.guideGain, timings.rampDownMs)
                }
            }
        }
    }

    private fun recordExposure(cause: ExposureCause, nowMs: Long, vocalWasDown: Boolean, effects: MutableList<GateEffect>) {
        cautious = true
        // 소리가 나지 않는 동안(일시정지)에는 들킬 것이 없으므로 기록하지 않는다.
        if (input.playing) effects += GateEffect.Exposure(cause, nowMs, vocalWasDown)
    }

    private fun clearArming() {
        armedElapsedMs = 0
        armedRunningSince = null
    }

    private fun moveTo(next: GateState, nowMs: Long, effects: MutableList<GateEffect>) {
        val from = state
        state = next
        effects += GateEffect.StateChanged(from, next, nowMs)
    }
}
