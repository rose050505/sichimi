package app.sichimi.gate

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.sin

class SingScoreTest {
    /** 0~2초는 반주만, 2~6초는 A3(57), 6~10초는 C4(60)를 부르는 곡. */
    private fun ref(): PitchRef {
        val frames = ByteArray(500)
        for (i in 100 until 300) frames[i] = (57 * 2).toByte()
        for (i in 300 until 500) frames[i] = (60 * 2).toByte()
        return PitchRef(frames, 20)
    }

    private fun sing(score: SingScore, from: Long, to: Long, midi: (Long) -> Float?) {
        var t = from
        while (t < to) { score.feed(t, midi(t)); t += 20 }
    }

    private fun original(t: Long): Float? = when (t) { in 2000 until 6000 -> 57f; in 6000 until 10000 -> 60f; else -> null }

    @Test fun `원곡과 똑같이 부르면 100점이고 완주다`() {
        val s = SingScore(ref())
        sing(s, 0, 10_000, ::original)
        assertEquals(100, s.score)
        assertTrue(s.finished)
    }

    @Test fun `한 옥타브 낮게 불러도 맞는다`() {
        val s = SingScore(ref())
        sing(s, 0, 10_000) { original(it)?.minus(12) }
        assertEquals(100, s.score)
    }

    @Test fun `부르지 않으면 0점`() {
        val s = SingScore(ref())
        sing(s, 0, 10_000) { null }
        assertEquals(0, s.score)
    }

    @Test fun `반음의 반 어긋나면 맞고, 1·5반음이면 반만, 3반음이면 거의 틀린다`() {
        val a = SingScore(ref()); sing(a, 0, 10_000) { original(it)?.plus(0.5f) }; assertEquals(100, a.score)
        val b = SingScore(ref()); sing(b, 0, 10_000) { original(it)?.plus(1.5f) }; assertEquals(50, b.score)
        // 3반음 높게: 음이 바뀌는 6초 앞 0.2초 동안은 박자 여유 안의 다음 음(C4)과 맞아서 몇 점 남는다
        val c = SingScore(ref()); sing(c, 0, 10_000) { original(it)?.plus(3f) }; assertTrue("점수 ${c.score}", c.score!! <= 5)
    }

    @Test fun `박자가 0·2초 안에서 늦어도 맞는다`() {
        val s = SingScore(ref())
        sing(s, 0, 10_000) { original(it - 150) }
        assertTrue("점수 ${s.score}", s.score!! >= 95)
    }

    @Test fun `반주만 나오는 곳은 채점하지 않는다`() {
        val s = SingScore(ref())
        sing(s, 0, 2000) { 70f }
        assertNull(s.score)
        assertEquals(0, s.judgedFrames)
    }

    @Test fun `절반 넘게 채점해야 완주다`() {
        val s = SingScore(ref())
        sing(s, 0, 4000, ::original) // 음표 400프레임 중 100
        assertFalse(s.finished)
        sing(s, 4000, 7000, ::original)
        assertTrue(s.finished)
    }

    @Test fun `같은 프레임은 한 번만 센다`() {
        val s = SingScore(ref())
        repeat(3) { s.feed(2000, 57f) }
        assertEquals(1, s.judgedFrames)
    }

    @Test fun `옥타브를 무시한 거리`() {
        assertEquals(0f, SingScore.semitoneDistance(57f, 69f), 1e-4f)
        assertEquals(1f, SingScore.semitoneDistance(59f, 48f), 1e-4f)
        assertEquals(6f, SingScore.semitoneDistance(60f, 66f), 1e-4f)
        assertEquals(69f, SingScore.hzToMidi(440f), 1e-3f)
    }

    @Test fun `YIN이 사인파의 높이를 찾고 조용하면 찾지 않는다`() {
        val sr = 16_000
        val yin = Yin(sr)
        for (hz in listOf(110f, 220f, 330f, 440f, 880f)) {
            val x = FloatArray(640) { (0.5 * sin(2 * PI * hz * it / sr)).toFloat() }
            val got = yin.pitchHz(x)!!
            assertEquals("$hz", 0f, SingScore.semitoneDistance(SingScore.hzToMidi(got), SingScore.hzToMidi(hz)), 0.1f)
        }
        val noise = java.util.Random(1).let { r -> FloatArray(640) { (r.nextGaussian() * 0.3).toFloat() } }
        assertNull(yin.pitchHz(noise))
    }
}
