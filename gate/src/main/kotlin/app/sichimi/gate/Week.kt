package app.sichimi.gate

import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.IsoFields
import java.time.temporal.TemporalAdjusters

/** 주(월~일, 차 시계). 주간 투어, 이번 주 미션, 주간 대피 랭킹이 쓴다. */
object Week {
    /** ISO 주 이름. 예: "2026-W46". 서버 경로에 쓴다. */
    fun key(epochMs: Long, zone: ZoneId): String {
        val d = Instant.ofEpochMilli(epochMs).atZone(zone).toLocalDate()
        return "%04d-W%02d".format(d.get(IsoFields.WEEK_BASED_YEAR), d.get(IsoFields.WEEK_OF_WEEK_BASED_YEAR))
    }

    fun monday(epochMs: Long, zone: ZoneId): LocalDate =
        Instant.ofEpochMilli(epochMs).atZone(zone).toLocalDate().with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))

    /** 이번 주 월요일 0시(epoch ms). */
    fun startMs(epochMs: Long, zone: ZoneId): Long = monday(epochMs, zone).atStartOfDay(zone).toInstant().toEpochMilli()

    /** 밤: 19시부터 다음 날 6시 전까지. */
    fun isNight(epochMs: Long, zone: ZoneId): Boolean {
        val h = Instant.ofEpochMilli(epochMs).atZone(zone).hour
        return h >= 19 || h < 6
    }

    val KEY_PATTERN = Regex("""^20[0-9]{2}-W[0-5][0-9]$""")
}
