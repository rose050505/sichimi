package app.sichimi.gate

import app.sichimi.gate.GateEffect.Exposure
import app.sichimi.gate.GateEffect.StateChanged
import app.sichimi.gate.GateEffect.VocalRamp
import app.sichimi.gate.GateState.ARMING
import app.sichimi.gate.GateState.KARAOKE
import app.sichimi.gate.GateState.NORMAL
import app.sichimi.gate.GateState.RETURN
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 설계 문서 3절의 전이표와 규칙을 한 줄씩 확인한다. */
class PrivacyGateTest {

    // ---- 입력 만들기 ----

    /** 운전석만 착석, 문·창문 닫힘, 기어 D, 재생 중. 장면 1의 출발점. */
    private fun solo(
        playing: Boolean = true,
        gear: Gear = Gear.DRIVE,
        turnSignalOn: Boolean = false,
    ) = CabinInput(
        seats = mapOf(
            Zone.FRONT_LEFT to known(true),
            Zone.FRONT_RIGHT to known(false),
            Zone.REAR_LEFT to known(false),
            Zone.REAR_RIGHT to known(false),
        ),
        doors = Zone.entries.associateWith { known(false) },
        windows = Zone.entries.associateWith { known(0) },
        gear = gear,
        turnSignalOn = turnSignalOn,
        playing = playing,
    )

    private fun CabinInput.seat(zone: Zone, r: Reading<Boolean>) = copy(seats = seats + (zone to r))
    private fun CabinInput.door(zone: Zone, open: Boolean) = copy(doors = doors + (zone to known(open)))
    private fun CabinInput.window(zone: Zone, pct: Int) = copy(windows = windows + (zone to known(pct)))

    private var now = 1_000L
    private val gate = PrivacyGate()

    private fun feed(input: CabinInput) = gate.update(input, now)
    private fun advance(ms: Long): List<GateEffect> {
        now += ms
        return gate.tick(now)
    }

    /** 노래방까지 데려간다. */
    private fun reachKaraoke(input: CabinInput = solo()) {
        feed(input)
        assertEquals(ARMING, gate.state)
        advance(gate.nextDeadlineMs()!! - now)
        assertEquals(KARAOKE, gate.state)
    }

    private fun List<GateEffect>.ramps() = filterIsInstance<VocalRamp>()
    private fun List<GateEffect>.exposures() = filterIsInstance<Exposure>()

    // ---- 파생값 ----

    @Test fun `혼자 - 운전석만 앉았으면 혼자다`() = assertTrue(solo().isSolo())

    @Test fun `혼자 - 운전석이 비면 혼자가 아니다`() =
        assertFalse(solo().seat(Zone.FRONT_LEFT, known(false)).isSolo())

    @Test fun `혼자 - 동승석에 앉으면 혼자가 아니다`() =
        assertFalse(solo().seat(Zone.FRONT_RIGHT, known(true)).isSolo())

    @Test fun `혼자 - 뒷좌석 한 곳에만 앉아도 혼자가 아니다`() {
        assertFalse(solo().seat(Zone.REAR_LEFT, known(true)).isSolo())
        assertFalse(solo().seat(Zone.REAR_RIGHT, known(true)).isSolo())
    }

    @Test fun `혼자 - 동승석을 읽을 수 없는 차에서는 혼자로 보지 않는다`() {
        assertFalse(solo().seat(Zone.FRONT_RIGHT, Reading.Unsupported).isSolo())
        assertFalse(solo().seat(Zone.FRONT_RIGHT, Reading.Pending).isSolo())
    }

    @Test fun `혼자 - 뒷좌석을 읽을 수 없는 차에서는 비었다고 본다`() {
        val input = solo().seat(Zone.REAR_LEFT, Reading.Unsupported).seat(Zone.REAR_RIGHT, Reading.Unsupported)
        assertTrue(input.isSolo())
    }

    @Test fun `혼자 - 뒷좌석 값을 아직 못 받았으면 혼자로 보지 않는다`() =
        assertFalse(solo().seat(Zone.REAR_LEFT, Reading.Pending).isSolo())

    @Test fun `밀폐 - 문 하나라도 열리면 깨진다`() {
        for (z in Zone.entries) assertFalse(z.name, solo().door(z, true).isSealed(2))
    }

    @Test fun `밀폐 - 창문은 기준 2% 이하면 닫힌 것으로 본다`() {
        assertTrue(solo().window(Zone.FRONT_LEFT, 2).isSealed(2))
        assertFalse(solo().window(Zone.FRONT_LEFT, 3).isSealed(2))
        for (z in Zone.entries) assertFalse(z.name, solo().window(z, 30).isSealed(2))
    }

    @Test fun `밀폐 - 이 차에 없는 문과 창문은 빼고 본다`() {
        val input = solo().copy(
            doors = solo().doors + (Zone.REAR_LEFT to Reading.Unsupported),
            windows = solo().windows + (Zone.REAR_RIGHT to Reading.Unsupported),
        )
        assertTrue(input.isSealed(2))
    }

    @Test fun `밀폐 - 문을 하나도 읽을 수 없으면 밀폐로 보지 않는다`() {
        val input = solo().copy(doors = Zone.entries.associateWith { Reading.Unsupported })
        assertFalse(input.isSealed(2))
    }

    @Test fun `밀폐 - 아직 값을 못 받은 문이 있으면 밀폐가 아니다`() {
        val input = solo().copy(doors = solo().doors + (Zone.FRONT_RIGHT to Reading.Pending))
        assertFalse(input.isSealed(2))
    }

    @Test fun `원인 - 문, 좌석, 창문 순으로 하나만 고른다`() {
        val all = solo().door(Zone.FRONT_RIGHT, true).seat(Zone.FRONT_RIGHT, known(true)).window(Zone.FRONT_LEFT, 30)
        assertEquals(ExposureCause.DOOR, all.breachCause(2))
        val seatAndWindow = solo().seat(Zone.FRONT_RIGHT, known(true)).window(Zone.FRONT_LEFT, 30)
        assertEquals(ExposureCause.SEAT, seatAndWindow.breachCause(2))
        assertEquals(ExposureCause.WINDOW, solo().window(Zone.FRONT_LEFT, 30).breachCause(2))
        assertNull(solo().breachCause(2))
    }

    // ---- 일반 → 대기 ----

    @Test fun `일반에서 대기로 - 혼자, 밀폐, 재생 중, 조작 중 아님`() {
        val effects = feed(solo())
        assertEquals(ARMING, gate.state)
        assertEquals(listOf(StateChanged(NORMAL, ARMING, now)), effects)
        assertEquals(now + 5_000, gate.nextDeadlineMs())
    }

    @Test fun `일반 유지 - 재생 중이 아니면 대기로 가지 않는다`() {
        feed(solo(playing = false))
        assertEquals(NORMAL, gate.state)
        assertNull(gate.nextDeadlineMs())
    }

    @Test fun `일반 유지 - 혼자가 아니거나 밀폐가 아니면 대기로 가지 않는다`() {
        val breaking = listOf(
            solo().seat(Zone.FRONT_RIGHT, known(true)),
            solo().seat(Zone.REAR_RIGHT, known(true)),
            solo().seat(Zone.FRONT_LEFT, known(false)),
            solo().door(Zone.REAR_LEFT, true),
            solo().window(Zone.REAR_RIGHT, 50),
            solo().seat(Zone.FRONT_RIGHT, Reading.Unsupported),
            CabinInput.INITIAL.copy(playing = true),
        )
        for (input in breaking) {
            val g = PrivacyGate()
            g.update(input, now)
            assertEquals(input.toString(), NORMAL, g.state)
        }
    }

    @Test fun `일반 유지 - 후진 중이면 대기로 가지 않는다`() {
        feed(solo(gear = Gear.REVERSE))
        assertEquals(NORMAL, gate.state)
    }

    @Test fun `일반 유지 - 방향지시등이 켜져 있으면 대기로 가지 않는다`() {
        feed(solo(turnSignalOn = true))
        assertEquals(NORMAL, gate.state)
    }

    @Test fun `안전 장치를 끄면 후진 중에도 대기로 간다`() {
        gate.updateConfig(GateConfig(safetyReturn = false), now)
        feed(solo(gear = Gear.REVERSE))
        assertEquals(ARMING, gate.state)
    }

    @Test fun `기능을 끄면 언제나 일반`() {
        gate.updateConfig(GateConfig(enabled = false), now)
        feed(solo())
        advance(60_000)
        assertEquals(NORMAL, gate.state)
    }

    // ---- 대기 → 노래방 ----

    @Test fun `대기에서 노래방으로 - 5초 유지되면 가수 음량을 0점6초에 걸쳐 내린다`() {
        feed(solo())
        assertTrue(advance(4_999).isEmpty())
        assertEquals(ARMING, gate.state)
        val effects = advance(1)
        assertEquals(KARAOKE, gate.state)
        assertEquals(listOf(VocalRamp(0.20f, 600)), effects.ramps())
        assertEquals(0.20f, gate.targetVocalGain())
    }

    @Test fun `대기 시간 설정(3, 8초)을 따른다`() {
        for (dwell in listOf(3_000L, 8_000L)) {
            val g = PrivacyGate(GateConfig(dwellMs = dwell))
            g.update(solo(), 0)
            g.tick(dwell - 1)
            assertEquals(ARMING, g.state)
            g.tick(dwell)
            assertEquals(KARAOKE, g.state)
        }
    }

    @Test fun `가이드 음량 설정을 따른다`() {
        val g = PrivacyGate(GateConfig(guideGain = 0.0f))
        g.update(solo(), 0)
        val effects = g.tick(5_000)
        assertEquals(listOf(VocalRamp(0.0f, 600)), effects.ramps())
    }

    @Test fun `대기 중 신호가 그대로 다시 들어와도 타이머는 처음부터 다시 돌지 않는다`() {
        feed(solo())
        advance(3_000)
        feed(solo())
        advance(2_000)
        assertEquals(KARAOKE, gate.state)
    }

    // ---- 대기 → 일반 ----

    @Test fun `대기에서 일반으로 - 조건이 깨지면 타이머를 취소하고 기록은 남기지 않는다`() {
        feed(solo())
        advance(3_000)
        val effects = feed(solo().door(Zone.FRONT_RIGHT, true))
        assertEquals(NORMAL, gate.state)
        assertTrue(effects.ramps().isEmpty())
        assertTrue(effects.exposures().isEmpty())
        assertNull(gate.nextDeadlineMs())
        assertFalse(gate.cautious)
    }

    @Test fun `대기에서 일반으로 - 후진하면 깨진다`() {
        feed(solo())
        feed(solo(gear = Gear.REVERSE))
        assertEquals(NORMAL, gate.state)
    }

    @Test fun `대기 중 일시정지 - 상태를 유지하고 타이머는 멈춘다`() {
        feed(solo())
        advance(2_000)
        feed(solo(playing = false))
        assertEquals(ARMING, gate.state)
        assertNull(gate.nextDeadlineMs())
        advance(10_000)
        assertEquals(ARMING, gate.state)
        feed(solo(playing = true))
        assertEquals(now + 3_000, gate.nextDeadlineMs())
        advance(2_999)
        assertEquals(ARMING, gate.state)
        advance(1)
        assertEquals(KARAOKE, gate.state)
    }

    // ---- 노래방 → 일반 ----

    @Test fun `노래방에서 일반으로 - 문이 열리면 0점15초 안에 가수를 올리고 기록한다`() {
        reachKaraoke()
        val effects = feed(solo().door(Zone.FRONT_RIGHT, true))
        assertEquals(NORMAL, gate.state)
        val ramp = effects.ramps().single()
        assertEquals(1f, ramp.targetGain)
        assertTrue(ramp.durationMs <= 150)
        assertEquals(listOf(Exposure(ExposureCause.DOOR, now, vocalWasDown = true)), effects.exposures())
        assertEquals(1f, gate.targetVocalGain())
    }

    @Test fun `노래방에서 일반으로 - 좌석과 창문도 원인으로 기록한다`() {
        val cases = mapOf(
            solo().seat(Zone.FRONT_RIGHT, known(true)) to ExposureCause.SEAT,
            solo().seat(Zone.REAR_LEFT, known(true)) to ExposureCause.SEAT,
            solo().seat(Zone.FRONT_LEFT, known(false)) to ExposureCause.SEAT,
            solo().window(Zone.FRONT_LEFT, 30) to ExposureCause.WINDOW,
            solo().door(Zone.REAR_RIGHT, true) to ExposureCause.DOOR,
        )
        for ((input, cause) in cases) {
            val g = PrivacyGate()
            g.update(solo(), 0)
            g.tick(5_000)
            assertEquals(KARAOKE, g.state)
            val effects = g.update(input, 6_000)
            assertEquals(input.toString(), NORMAL, g.state)
            assertEquals(cause, effects.exposures().single().cause)
        }
    }

    @Test fun `노래방 유지 - 기어 P에서도 혼자·밀폐면 열려 있다`() {
        reachKaraoke()
        val effects = feed(solo(gear = Gear.PARK))
        assertEquals(KARAOKE, gate.state)
        assertTrue(effects.isEmpty())
    }

    @Test fun `노래방 - 기어 P에서 시작해도 열린다`() {
        reachKaraoke(solo(gear = Gear.PARK))
    }

    @Test fun `노래방 중 일시정지 - 상태를 유지한다`() {
        reachKaraoke()
        val effects = feed(solo(playing = false))
        assertEquals(KARAOKE, gate.state)
        assertTrue(effects.isEmpty())
    }

    @Test fun `노래방 중 일시정지 상태에서 문이 열리면 일반으로 가되 기록은 남기지 않는다`() {
        reachKaraoke()
        feed(solo(playing = false))
        val effects = feed(solo(playing = false).door(Zone.FRONT_LEFT, true))
        assertEquals(NORMAL, gate.state)
        assertEquals(1, effects.ramps().size)
        assertTrue(effects.exposures().isEmpty())
        assertTrue(gate.cautious)
    }

    @Test fun `노래방 중 기능을 끄면 일반으로 돌아가고 기록은 남기지 않는다`() {
        reachKaraoke()
        val effects = gate.updateConfig(GateConfig(enabled = false), now)
        assertEquals(NORMAL, gate.state)
        assertEquals(1f, effects.ramps().single().targetGain)
        assertTrue(effects.exposures().isEmpty())
        assertFalse(gate.cautious)
    }

    @Test fun `노래방 중 가이드 음량을 바꾸면 그 값으로 옮겨 간다`() {
        reachKaraoke()
        val effects = gate.updateConfig(GateConfig(guideGain = 0.35f), now)
        assertEquals(KARAOKE, gate.state)
        assertEquals(listOf(VocalRamp(0.35f, 600)), effects.ramps())
    }

    // ---- 들킬 뻔한 뒤 8초 ----

    @Test fun `들킬 뻔한 직후에는 대기 시간이 8초다 - 장면 2에서 4까지`() {
        reachKaraoke()
        // 장면 2: 동승석 문을 연다
        feed(solo().door(Zone.FRONT_RIGHT, true))
        assertTrue(gate.cautious)
        // 장면 3: 동승석 착석, 문 닫음, 기어 P
        feed(solo().seat(Zone.FRONT_RIGHT, known(true)).door(Zone.FRONT_RIGHT, true))
        feed(solo().seat(Zone.FRONT_RIGHT, known(true)))
        feed(solo(gear = Gear.PARK).seat(Zone.FRONT_RIGHT, known(true)))
        advance(20_000)
        assertEquals(NORMAL, gate.state)
        // 장면 4: 동승석 착석 끄고 문을 열었다 닫고 기어 D
        feed(solo(gear = Gear.PARK).seat(Zone.FRONT_RIGHT, known(false)).door(Zone.FRONT_RIGHT, true))
        feed(solo(gear = Gear.PARK))
        assertEquals(ARMING, gate.state)
        feed(solo(gear = Gear.DRIVE))
        assertEquals(now + 8_000, gate.nextDeadlineMs())
        advance(7_999)
        assertEquals(ARMING, gate.state)
        advance(1)
        assertEquals(KARAOKE, gate.state)
        assertFalse(gate.cautious)
    }

    @Test fun `노래방이 다시 열린 뒤에는 대기 시간이 설정값으로 돌아온다`() {
        reachKaraoke()
        feed(solo().door(Zone.FRONT_RIGHT, true))
        feed(solo())
        advance(8_000)
        assertEquals(KARAOKE, gate.state)
        assertFalse(gate.cautious)
        // 기능을 껐다 켜서(들킬 뻔 없이) 다시 대기에 들어가면 설정값 5초
        gate.updateConfig(GateConfig(enabled = false), now)
        gate.updateConfig(GateConfig(enabled = true), now)
        assertEquals(ARMING, gate.state)
        assertEquals(now + 5_000, gate.nextDeadlineMs())
    }

    @Test fun `대기 시간 설정이 8초보다 길면 그 값을 쓴다`() {
        val g = PrivacyGate(GateConfig(dwellMs = 8_000), PrivacyGate.Timings(postExposureDwellMs = 6_000))
        g.update(solo(), 0); g.tick(8_000); g.update(solo().door(Zone.FRONT_LEFT, true), 9_000)
        g.update(solo(), 10_000)
        assertEquals(18_000L, g.nextDeadlineMs())
    }

    // ---- 노래방 → 잠시 복귀 ----

    @Test fun `노래방에서 잠시 복귀로 - 후진하면 0점2초에 가수를 올리고 기록은 남기지 않는다`() {
        reachKaraoke()
        val effects = feed(solo(gear = Gear.REVERSE))
        assertEquals(RETURN, gate.state)
        assertEquals(listOf(VocalRamp(1f, 200)), effects.ramps())
        assertTrue(effects.exposures().isEmpty())
        assertFalse(gate.cautious)
    }

    @Test fun `노래방에서 잠시 복귀로 - 방향지시등도 조작이다`() {
        reachKaraoke()
        feed(solo(turnSignalOn = true))
        assertEquals(RETURN, gate.state)
    }

    @Test fun `노래방에서 잠시 복귀로 - 내비 목적지에 곧 도착하면 가수를 올리고 기록은 남기지 않는다`() {
        reachKaraoke()
        val effects = feed(solo().copy(arrivingSoon = true))
        assertEquals(RETURN, gate.state)
        assertEquals(listOf(VocalRamp(1f, 200)), effects.ramps())
        assertTrue(effects.exposures().isEmpty())
    }

    @Test fun `곧 도착이면 대기로 가지 않는다`() {
        feed(solo().copy(arrivingSoon = true))
        assertEquals(NORMAL, gate.state)
    }

    @Test fun `안전 장치를 끄면 곧 도착이어도 노래방이 유지된다`() {
        gate.updateConfig(GateConfig(safetyReturn = false), now)
        reachKaraoke()
        feed(solo().copy(arrivingSoon = true))
        assertEquals(KARAOKE, gate.state)
    }

    @Test fun `안전 장치를 끄면 후진해도 노래방이 유지된다`() {
        gate.updateConfig(GateConfig(safetyReturn = false), now)
        reachKaraoke()
        feed(solo(gear = Gear.REVERSE))
        assertEquals(KARAOKE, gate.state)
    }

    // ---- 잠시 복귀 → 노래방 ----

    @Test fun `잠시 복귀에서 노래방으로 - 조작이 끝나고 1점5초 지나면 가수를 내린다`() {
        reachKaraoke()
        feed(solo(gear = Gear.REVERSE))
        feed(solo(gear = Gear.DRIVE))
        assertEquals(RETURN, gate.state)
        assertEquals(now + 1_500, gate.nextDeadlineMs())
        assertTrue(advance(1_499).isEmpty())
        val effects = advance(1)
        assertEquals(KARAOKE, gate.state)
        assertEquals(listOf(VocalRamp(0.20f, 600)), effects.ramps())
    }

    @Test fun `잠시 복귀 - 1점5초 안에 다시 조작하면 처음부터 다시 기다린다`() {
        reachKaraoke()
        feed(solo(gear = Gear.REVERSE))
        feed(solo(gear = Gear.DRIVE))
        advance(1_000)
        feed(solo(gear = Gear.REVERSE))
        assertNull(gate.nextDeadlineMs())
        advance(5_000)
        assertEquals(RETURN, gate.state)
        feed(solo(gear = Gear.DRIVE))
        advance(1_499)
        assertEquals(RETURN, gate.state)
        advance(1)
        assertEquals(KARAOKE, gate.state)
    }

    @Test fun `잠시 복귀 중 안전 장치를 끄면 1점5초 뒤 노래방으로 돌아간다`() {
        reachKaraoke()
        feed(solo(gear = Gear.REVERSE))
        gate.updateConfig(GateConfig(safetyReturn = false), now)
        assertEquals(RETURN, gate.state)
        advance(1_500)
        assertEquals(KARAOKE, gate.state)
    }

    // ---- 잠시 복귀 → 일반 ----

    @Test fun `잠시 복귀에서 일반으로 - 혼자·밀폐가 깨지면 기록을 남긴다(대피 시간 없음)`() {
        reachKaraoke()
        feed(solo(gear = Gear.REVERSE))
        val effects = feed(solo(gear = Gear.REVERSE).door(Zone.FRONT_RIGHT, true))
        assertEquals(NORMAL, gate.state)
        assertTrue(effects.ramps().isEmpty())
        assertEquals(listOf(Exposure(ExposureCause.DOOR, now, vocalWasDown = false)), effects.exposures())
        assertTrue(gate.cautious)
    }

    @Test fun `잠시 복귀 중 기능을 끄면 일반으로 가고 기록은 남기지 않는다`() {
        reachKaraoke()
        feed(solo(gear = Gear.REVERSE))
        val effects = gate.updateConfig(GateConfig(enabled = false), now)
        assertEquals(NORMAL, gate.state)
        assertTrue(effects.exposures().isEmpty())
    }

    // ---- 스스로 재생하지 않는다 ----

    @Test fun `문지기는 재생을 시작하라는 요청을 하지 않는다`() {
        // 효과의 종류는 음량, 기록, 상태 변화뿐이다. 재생 시작은 효과 목록에 없다.
        val all = mutableListOf<GateEffect>()
        all += feed(solo()); all += advance(5_000)
        all += feed(solo(gear = Gear.REVERSE)); all += feed(solo()); all += advance(1_500)
        all += feed(solo().door(Zone.FRONT_LEFT, true))
        assertTrue(all.all { it is VocalRamp || it is Exposure || it is StateChanged })
    }
}
