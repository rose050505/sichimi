package app.sichimi.gate

import org.junit.Assert.assertEquals
import org.junit.Test

class KaraokeWipeTest {
    private val starts = listOf(10_000L, 14_000L, 30_000L)
    private val lengths = listOf(10, 10, 2)

    @Test fun `첫 줄 전에는 전주`() {
        assertEquals(WipeLine(-1, 0f), KaraokeWipe.at(starts, lengths, 9_999))
    }

    @Test fun `줄 시작에는 0, 다음 줄 바로 앞에서 1`() {
        assertEquals(0f, KaraokeWipe.at(starts, lengths, 10_000).fraction, 1e-6f)
        assertEquals(1f, KaraokeWipe.at(starts, lengths, 13_850).fraction, 1e-6f)
        assertEquals(0, KaraokeWipe.at(starts, lengths, 13_999).index)
    }

    @Test fun `중간은 시간 비율로 채운다`() {
        // 줄 끝 = 14000 - 150 = 13850, 길이 3850
        val w = KaraokeWipe.at(starts, lengths, 10_000 + 3_850 / 2)
        assertEquals(0, w.index)
        assertEquals(0.5f, w.fraction, 0.01f)
    }

    @Test fun `간주가 길면 글자 수로 자른다`() {
        // 둘째 줄: 다음 줄은 30000이지만 10글자 × 600ms = 6000에서 끝
        assertEquals(20_000L, KaraokeWipe.lineEndMs(starts, lengths, 1))
        assertEquals(1f, KaraokeWipe.at(starts, lengths, 25_000).fraction, 1e-6f)
    }

    @Test fun `마지막 줄은 글자 수로만, 너무 짧으면 최소 길이`() {
        assertEquals(30_000L + KaraokeWipe.MIN_LINE_MS, KaraokeWipe.lineEndMs(starts, lengths, 2))
    }

    @Test fun `줄 찾기와 글자 수`() {
        assertEquals(2, KaraokeWipe.lineIndex(starts, 99_000))
        assertEquals(1, KaraokeWipe.lineIndex(starts, 14_000))
        assertEquals(7, KaraokeWipe.letters("아리랑 아리랑 ~"))
    }
}
