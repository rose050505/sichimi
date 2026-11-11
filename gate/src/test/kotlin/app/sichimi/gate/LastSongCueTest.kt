package app.sichimi.gate

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class LastSongCueTest {
    private val cue = LastSongCue()

    @Test fun `노래방에서 남은 시간이 3분대면 한 번 말한다`() {
        assertEquals("도착까지 3분, 한 곡 더 됩니다", cue.update(215, GateState.KARAOKE))
        assertNull(cue.update(200, GateState.KARAOKE))
    }

    @Test fun `구간 밖에서는 말하지 않는다`() {
        assertNull(cue.update(600, GateState.KARAOKE))
        assertNull(cue.update(300, GateState.KARAOKE))
        assertNull(cue.update(179, GateState.KARAOKE))
    }

    @Test fun `4분대도 한 곡 더 구간이다`() {
        assertEquals("도착까지 4분, 한 곡 더 됩니다", cue.update(240, GateState.KARAOKE))
    }

    @Test fun `남은 시간이 줄어 구간에 들어오면 그때 말한다`() {
        assertNull(cue.update(360, GateState.KARAOKE))
        assertEquals("도착까지 4분, 한 곡 더 됩니다", cue.update(299, GateState.KARAOKE))
    }

    @Test fun `노래방이 아니면 말하지 않고, 구간 안에서 노래방이 되면 말한다`() {
        for (s in listOf(GateState.NORMAL, GateState.ARMING, GateState.RETURN)) assertNull(cue.update(220, s))
        assertEquals("도착까지 3분, 한 곡 더 됩니다", cue.update(210, GateState.KARAOKE))
    }

    @Test fun `경로가 없어지면 다음 경로에서 다시 말한다`() {
        cue.update(220, GateState.KARAOKE)
        assertNull(cue.update(null, GateState.KARAOKE))
        assertEquals("도착까지 3분, 한 곡 더 됩니다", cue.update(230, GateState.KARAOKE))
    }
}
