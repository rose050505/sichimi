package app.sichimi.gate

/** 주행 하나의 결과. 기어가 P를 벗어난 때부터 다시 P가 될 때까지. */
data class TripSummary(
    val startedAt: Long,
    val endedAt: Long,
    val soloMs: Long,
    val soloSongs: Int,
    val exposures: Int,
    /** 가장 빠른 대피 시간. 대피 시간이 잰 들킬 뻔이 없으면 null. */
    val fastestReactionMs: Long?,
)

/** 곡 한 번 재생한 결과. */
data class SongPlaySummary(
    val songId: String,
    val startedAt: Long,
    val totalMs: Long,
    val soloMs: Long,
    /** 주행 중에 시작했으면 그 주행의 시작 시각. 주차 중에 시작했으면 null. */
    val tripStartedAt: Long?,
    /** 곡을 시작할 때마다 바뀌는 값. 같은 재생의 진행 중 기록과 마감 기록을 잇는다. */
    val itemKey: String = "",
)

sealed interface LedgerEvent {
    data class TripStarted(val startedAt: Long) : LedgerEvent
    data class TripEnded(val trip: TripSummary) : LedgerEvent
    data class SongPlayEnded(val play: SongPlaySummary) : LedgerEvent
}

/**
 * 혼자 부른 시간을 센다. Android에 기대지 않는다.
 *
 * 혼자 부른 시간 = 문지기가 [GateState.KARAOKE]이고 소리가 나는 동안의 시간.
 * 모든 시각은 같은 시계(밀리초)를 쓴다.
 */
class SoloLedger {
    companion object {
        /** 한 곡에서 이만큼 이상 혼자 불러야 "부른 곡"으로 센다. */
        const val SOLO_SONG_MIN_MS = 10_000L
    }

    private class TripAcc(val startedAt: Long) {
        var soloMs = 0L
        var exposures = 0
        var fastestReactionMs: Long? = null
        val soloByPlay = mutableMapOf<Int, Long>()
    }

    private class PlayAcc(val index: Int, val key: String, val songId: String, val startedAt: Long, val tripStartedAt: Long?) {
        var totalMs = 0L
        var soloMs = 0L
    }

    private var trip: TripAcc? = null
    private var play: PlayAcc? = null
    private var playCounter = 0
    private var playing = false
    private var gateState = GateState.NORMAL
    private var gear = Gear.UNKNOWN
    private var lastAt: Long? = null

    val inTrip: Boolean get() = trip != null

    /** 진행 중인 주행에서 지금까지 혼자 부른 시간. */
    val currentTripSoloMs: Long get() = trip?.soloMs ?: 0L

    fun onGear(newGear: Gear, nowMs: Long): List<LedgerEvent> {
        accrue(nowMs)
        gear = newGear
        val events = mutableListOf<LedgerEvent>()
        when (newGear) {
            Gear.REVERSE, Gear.NEUTRAL, Gear.DRIVE -> if (trip == null) {
                trip = TripAcc(nowMs)
                events += LedgerEvent.TripStarted(nowMs)
            }
            Gear.PARK -> trip?.let { t ->
                events += LedgerEvent.TripEnded(
                    TripSummary(
                        startedAt = t.startedAt,
                        endedAt = nowMs,
                        soloMs = t.soloMs,
                        soloSongs = t.soloByPlay.values.count { it >= SOLO_SONG_MIN_MS },
                        exposures = t.exposures,
                        fastestReactionMs = t.fastestReactionMs,
                    )
                )
                trip = null
            }
            Gear.UNKNOWN -> Unit
        }
        return events
    }

    fun onGateState(state: GateState, nowMs: Long) {
        accrue(nowMs)
        gateState = state
    }

    /**
     * 플레이어 상태가 바뀌었다.
     * @param itemKey 곡을 새로 시작할 때마다 달라지는 값. 같은 곡을 다시 재생해도 달라야 한다. 재생할 곡이 없으면 null.
     */
    fun onPlayback(itemKey: String?, songId: String?, isPlaying: Boolean, nowMs: Long): List<LedgerEvent> {
        accrue(nowMs)
        val events = mutableListOf<LedgerEvent>()
        if (itemKey != play?.key) {
            endPlay()?.let { events += it }
            if (itemKey != null && songId != null) {
                play = PlayAcc(playCounter++, itemKey, songId, nowMs, trip?.startedAt)
            }
        }
        playing = isPlaying && play != null
        return events
    }

    /** 들킬 뻔했다. [reactionMs]는 잰 대피 시간(없으면 null). */
    fun onExposure(reactionMs: Long?, nowMs: Long) {
        accrue(nowMs)
        val t = trip ?: return
        t.exposures++
        if (reactionMs != null) {
            t.fastestReactionMs = t.fastestReactionMs?.let { minOf(it, reactionMs) } ?: reactionMs
        }
    }

    /** 진행 중인 곡의 지금까지 기록. 재생 중인 곡도 내 기록에 바로 보이도록 쓴다. */
    fun currentPlay(nowMs: Long): SongPlaySummary? {
        accrue(nowMs)
        val p = play ?: return null
        return SongPlaySummary(p.songId, p.startedAt, p.totalMs, p.soloMs, p.tripStartedAt, p.key)
    }

    /** 앱이 끝날 때 진행 중인 곡을 마감한다. */
    fun close(nowMs: Long): List<LedgerEvent> {
        accrue(nowMs)
        return listOfNotNull(endPlay())
    }

    private fun endPlay(): LedgerEvent? {
        val p = play ?: return null
        play = null
        if (p.totalMs <= 0) return null
        return LedgerEvent.SongPlayEnded(SongPlaySummary(p.songId, p.startedAt, p.totalMs, p.soloMs, p.tripStartedAt, p.key))
    }

    private fun accrue(nowMs: Long) {
        val last = lastAt
        lastAt = nowMs
        if (last == null || nowMs <= last) return
        val dt = nowMs - last
        val p = play ?: return
        if (!playing) return
        p.totalMs += dt
        if (gateState == GateState.KARAOKE) {
            p.soloMs += dt
            trip?.let { t ->
                t.soloMs += dt
                t.soloByPlay[p.index] = (t.soloByPlay[p.index] ?: 0L) + dt
            }
        }
    }
}
