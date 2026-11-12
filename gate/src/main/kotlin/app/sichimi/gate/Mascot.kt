package app.sichimi.gate

/** 시치미가 지금 하는 일. */
enum class MascotPose {
    /** 마이크 들고 흥얼(노래방). */
    SINGING,
    /** 들킬 뻔: 아래로 숨는 중([MascotMood.HIDE_MS] 동안만). */
    HIDING,
    /** 그리지 않음(일반 화면 규칙). */
    AWAY,
    /** 다시 혼자가 되어 노래방이 열림: "휴, 안 들켰다". */
    RELIEVED;

    val visible: Boolean get() = this != AWAY
}

/** 차 상태에 따른 모습. 여러 개가 겹칠 수 있다. */
data class MascotLook(val raincoat: Boolean = false, val night: Boolean = false, val breeze: Boolean = false)

/**
 * 시치미 동작 상태 기계. Android에 기대지 않는다.
 *
 * 문지기 상태와 들킬 뻔 번호(들킬 뻔할 때마다 1씩 오름)를 받아 자세를 정한다.
 * 노래방(KARAOKE)이 아닐 때는 들킬 뻔 직후 [HIDE_MS] 동안의 HIDING 말고는 언제나 AWAY다.
 */
class MascotMood {
    var pose: MascotPose = MascotPose.AWAY
        private set
    private var since = 0L
    private var lastExposureSeq = 0L
    private var reliefPending = false

    fun update(state: GateState, exposureSeq: Long, nowMs: Long): MascotPose {
        val newExposure = exposureSeq != lastExposureSeq
        lastExposureSeq = exposureSeq
        if (state == GateState.KARAOKE) {
            when (pose) {
                MascotPose.AWAY, MascotPose.HIDING -> if (reliefPending) { reliefPending = false; go(MascotPose.RELIEVED, nowMs) } else go(MascotPose.SINGING, nowMs)
                MascotPose.RELIEVED -> if (nowMs - since >= RELIEF_MS) go(MascotPose.SINGING, nowMs)
                MascotPose.SINGING -> Unit
            }
        } else {
            when {
                newExposure && (pose == MascotPose.SINGING || pose == MascotPose.RELIEVED) -> { reliefPending = true; go(MascotPose.HIDING, nowMs) }
                pose == MascotPose.HIDING && nowMs - since >= HIDE_MS -> go(MascotPose.AWAY, nowMs)
                pose == MascotPose.SINGING || pose == MascotPose.RELIEVED -> go(MascotPose.AWAY, nowMs)
                newExposure -> reliefPending = true
            }
        }
        return pose
    }

    /** 다음에 [update]를 다시 불러야 할 시각(시간이 흘러 자세가 바뀌는 때). */
    fun nextChangeMs(): Long? = when (pose) {
        MascotPose.HIDING -> since + HIDE_MS
        MascotPose.RELIEVED -> since + RELIEF_MS
        else -> null
    }

    /** HIDING이 시작된 뒤 흐른 비율(0~1). 그림이 아래로 내려가는 정도. */
    fun hideFraction(nowMs: Long): Float = if (pose == MascotPose.HIDING) ((nowMs - since).toFloat() / HIDE_MS).coerceIn(0f, 1f) else 0f

    private fun go(p: MascotPose, nowMs: Long) { pose = p; since = nowMs }

    companion object {
        const val HIDE_MS = 300L
        const val RELIEF_MS = 4_000L

        /** 와이퍼 → 우비, 밤 → 조명, 창문이 기준 아래로 조금 열림 → 바람. */
        fun look(wiperOn: Boolean, night: Boolean, windowsPct: Collection<Int>, thresholdPct: Int): MascotLook =
            MascotLook(raincoat = wiperOn, night = night, breeze = windowsPct.any { it in 1..thresholdPct })
    }
}

/** 주차 화면 덮개 카드 한 장. */
sealed interface Reveal {
    data class Score(val scoreId: Long) : Reveal
    data class Award(val awardId: Long) : Reveal
    data class Summary(val tripId: Long) : Reveal
}

/**
 * 주차 화면에 띄울 덮개 카드 순서. 주차(기어 P, 주행 규제 없음)이고 혼자일 때만 하나를 고른다.
 * 순서: 점수 화면(오래된 것부터) → 새로 얻은 배지·미션·투어 → 오늘 요약.
 */
object ParkedQueue {
    fun next(parked: Boolean, solo: Boolean, unrevealedScores: List<Long>, unshownAwards: List<Long>, summaryTrip: Long?): Reveal? {
        if (!parked || !solo) return null
        unrevealedScores.minOrNull()?.let { return Reveal.Score(it) }
        unshownAwards.minOrNull()?.let { return Reveal.Award(it) }
        return summaryTrip?.let { Reveal.Summary(it) }
    }
}

/** 고스트 목소리 음량. */
object GhostGain {
    /** 설정 %와 가이드 %로 실제 고스트 크기(0~1)를 정한다. 가이드보다 작게만: 같거나 크면 가이드의 절반. */
    fun level(ghostPct: Int, guidePct: Int): Float {
        if (guidePct <= 0 || ghostPct <= 0) return 0f
        val pct = if (ghostPct < guidePct) ghostPct else guidePct / 2f
        return (pct.toFloat() / 100f).coerceIn(0f, 1f)
    }

    /**
     * 지금 가수 음량에 묶은 고스트 음량. 가수가 가이드 음량까지 내려가 있으면 [level], 100%면 0.
     * 가수가 돌아오는 램프(들킬 뻔 0.12초)와 같은 표본에서 고스트도 사라진다.
     */
    fun follow(vocalGain: Float, guideGain: Float, level: Float): Float {
        if (guideGain >= 1f) return 0f
        val t = ((1f - vocalGain) / (1f - guideGain)).coerceIn(0f, 1f)
        return level * t
    }
}

/** 가장 빠른 대피 랭킹에 올릴 수 있는 값(서버 규칙과 같음). */
object EscapeRule {
    const val MIN_MS = 100L
    const val MAX_MS = 3_000L
    fun valid(ms: Long) = ms in MIN_MS..MAX_MS
    /** 새 기록이 이번 주 이 곡의 내 기록보다 빠른가. */
    fun improves(newMs: Long, oldMs: Long?) = valid(newMs) && (oldMs == null || newMs < oldMs)
}
