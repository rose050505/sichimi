package app.sichimi.gate

/** 기록 한 줄(기기 안 Room에서 옮김). Android에 기대지 않게 필요한 값만. */
data class PlayRow(val tripId: Long?, val songId: String, val startedAt: Long, val durationMs: Long, val soloMs: Long,
                   val rainSoloMs: Long = 0, val nightSoloMs: Long = 0)
data class ExposureRow(val tripId: Long?, val at: Long, val reactionMs: Long?, val relievedAt: Long? = null)
data class ScoreRow(val songId: String, val at: Long, val score: Int, val finished: Boolean, val ghostScore: Int? = null)

/** 기록을 모아 이번 주 기록, 누적 기록, 성장 재료를 만든다. */
object Stats {
    private fun PlayRow.soloSong() = soloMs >= SoloLedger.SOLO_SONG_MIN_MS
    private fun PlayRow.full() = Growth.isFullSong(soloMs, durationMs)
    private fun ScoreRow.ghostWin() = finished && ghostScore != null && score > ghostScore

    /** [fromMs, toMs) 동안의 기록. */
    fun week(fromMs: Long, toMs: Long, plays: List<PlayRow>, exposures: List<ExposureRow>, scores: List<ScoreRow>): WeekStats {
        val p = plays.filter { it.startedAt in fromMs until toMs }
        val e = exposures.filter { it.at in fromMs until toMs }
        val s = scores.filter { it.at in fromMs until toMs }
        val soloByTrip = p.filter { it.soloSong() && it.tripId != null }.groupBy { it.tripId!! }
        val exposedTrips = e.mapNotNull { it.tripId }.toSet()
        return WeekStats(
            shows = soloByTrip.size,
            soloMs = p.sumOf { it.soloMs },
            soloSongs = p.count { it.soloSong() },
            bestScore = s.filter { it.finished }.maxOfOrNull { it.score },
            fastestEscapeMs = e.mapNotNull { it.reactionMs }.minOrNull(),
            exposures = e.size,
            maxCleanTripSongs = soloByTrip.filterKeys { it !in exposedTrips }.values.maxOfOrNull { it.size } ?: 0,
            rainSongs = p.count { it.rainSoloMs >= SoloLedger.SOLO_SONG_MIN_MS },
            nightSongs = p.count { it.nightSoloMs >= SoloLedger.SOLO_SONG_MIN_MS },
            ghostWins = s.count { it.ghostWin() },
            maxFullPlaysOfOneSong = p.filter { it.full() }.groupingBy { it.songId }.eachCount().values.maxOrNull() ?: 0,
        )
    }

    fun lifetime(week: WeekStats, plays: List<PlayRow>, exposures: List<ExposureRow>, scores: List<ScoreRow>) = LifetimeStats(
        soloSongs = plays.count { it.soloSong() },
        fastestEscapeMs = exposures.mapNotNull { it.reactionMs }.minOrNull(),
        relievedHides = exposures.count { it.relievedAt != null },
        rainSongs = plays.count { it.rainSoloMs >= SoloLedger.SOLO_SONG_MIN_MS },
        nightSongs = plays.count { it.nightSoloMs >= SoloLedger.SOLO_SONG_MIN_MS },
        ghostWins = scores.count { it.ghostWin() },
        week = week,
    )

    fun growth(plays: List<PlayRow>, exposures: List<ExposureRow>, missionBonus: Int) = Growth.Inputs(
        soloMs = plays.sumOf { it.soloMs },
        fullSongs = plays.count { it.full() },
        hideReactionsMs = exposures.mapNotNull { it.reactionMs },
        missionBonus = missionBonus,
    )

    /** 이번 주 셋리스트: 혼자 부른 곡을 많이 부른 순으로(같으면 먼저 부른 곡). */
    fun setlist(fromMs: Long, toMs: Long, plays: List<PlayRow>): List<String> =
        plays.filter { it.startedAt in fromMs until toMs && it.soloSong() }
            .groupBy { it.songId }.entries
            .sortedWith(compareByDescending<Map.Entry<String, List<PlayRow>>> { it.value.size }.thenBy { e -> e.value.minOf { it.startedAt } })
            .map { it.key }
}
