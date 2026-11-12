package app.sichimi.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.sichimi.gate.GateState
import app.sichimi.songs.LyricLine
import kotlinx.coroutines.delay

// ---------- 색과 글자 ----------

object Palette {
    val bg = Color(0xFF0E1013)
    val surface = Color(0xFF1B1F24)
    val surfaceHi = Color(0xFF2A3038)
    val text = Color(0xFFF4F6F8)
    val sub = Color(0xFFB4BCC6)
    val dim = Color(0xFF7C8591)
    val accent = Color(0xFFFFB547) // 노래방 상태와, 혼자일 때만 보이는 내 기록에서만 쓴다
    val danger = Color(0xFFFF6B5B)
}

private val BODY = 26.sp

// ---------- 공통 조각 ----------

enum class Transport { PREVIOUS, PLAY, PAUSE, NEXT }

@Composable
fun TransportButton(kind: Transport, label: String, height: Int, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Box(
        modifier
            .heightIn(min = height.dp)
            .clip(RoundedCornerShape(20.dp))
            .background(if (kind == Transport.PLAY || kind == Transport.PAUSE) Palette.surfaceHi else Palette.surface)
            .clickable(onClick = onClick)
            .semantics { contentDescription = label },
        contentAlignment = Alignment.Center,
    ) {
        Canvas(Modifier.size((height * 0.42f).dp)) {
            val w = size.width
            val h = size.height
            val c = Palette.text
            fun triangle(left: Float, right: Float, pointRight: Boolean) {
                val p = Path()
                if (pointRight) { p.moveTo(left, 0f); p.lineTo(right, h / 2); p.lineTo(left, h) } else { p.moveTo(right, 0f); p.lineTo(left, h / 2); p.lineTo(right, h) }
                p.close(); drawPath(p, c)
            }
            when (kind) {
                Transport.PLAY -> triangle(w * 0.15f, w * 0.95f, true)
                Transport.PAUSE -> {
                    drawRect(c, Offset(w * 0.15f, 0f), Size(w * 0.25f, h))
                    drawRect(c, Offset(w * 0.6f, 0f), Size(w * 0.25f, h))
                }
                Transport.NEXT -> { triangle(0f, w * 0.75f, true); drawRect(c, Offset(w * 0.78f, 0f), Size(w * 0.14f, h)) }
                Transport.PREVIOUS -> { drawRect(c, Offset(w * 0.08f, 0f), Size(w * 0.14f, h)); triangle(w * 0.25f, w, false) }
            }
        }
    }
}

@Composable
fun TransportRow(isPlaying: Boolean, height: Int, onPrev: () -> Unit, onPlayPause: () -> Unit, onNext: () -> Unit) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(24.dp)) {
        TransportButton(Transport.PREVIOUS, "이전 곡", height, onPrev, Modifier.weight(1f))
        TransportButton(if (isPlaying) Transport.PAUSE else Transport.PLAY, if (isPlaying) "일시정지" else "재생", height, onPlayPause, Modifier.weight(1.4f))
        TransportButton(Transport.NEXT, "다음 곡", height, onNext, Modifier.weight(1f))
    }
}

/** 상태 문구. 혼자가 아닐 때는 일반 플레이어와 똑같은 문구만 나온다(설계 5절). */
fun statusText(state: GateState, isPlaying: Boolean, hasSong: Boolean): String = when (state) {
    GateState.KARAOKE -> "혼자만의 노래방"
    GateState.RETURN -> "잠시 가수 복귀"
    else -> when {
        !hasSong -> "재생을 누르면 시작합니다"
        isPlaying -> "재생 중"
        else -> "일시정지"
    }
}

// ---------- 주행 화면 ----------

/** 주행 중(기어가 P가 아니거나 주행 규제)에 보이는 한 화면. 목록과 가사는 없다. */
@Composable
fun DrivingScreen(state: GateState, player: PlayerUi, onPrev: () -> Unit, onPlayPause: () -> Unit, onNext: () -> Unit) {
    val karaoke = state == GateState.KARAOKE
    Column(
        Modifier.fillMaxSize().background(Palette.bg).padding(horizontal = 56.dp, vertical = 40.dp),
        verticalArrangement = Arrangement.Center,
    ) {
        Text(
            statusText(state, player.isPlaying, player.songId != null),
            color = if (karaoke) Palette.accent else Palette.sub,
            fontSize = 36.sp, fontWeight = FontWeight.SemiBold,
        )
        Spacer(Modifier.height(24.dp))
        Text(player.title.ifEmpty { " " }, color = Palette.text, fontSize = 52.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Spacer(Modifier.height(8.dp))
        Text(player.artist.ifEmpty { " " }, color = Palette.sub, fontSize = 34.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Spacer(Modifier.height(56.dp))
        TransportRow(player.isPlaying, 128, onPrev, onPlayPause, onNext)
    }
}

// ---------- 주차 화면 ----------

enum class ParkedTab(val label: String) { PLAYER("재생·가사"), QUEUE("예약 목록"), RECORDS("내 기록"), SCORES("점수"), SETTINGS("설정") }

@Composable
fun ParkedScreen(
    solo: Boolean,
    state: GateState,
    player: PlayerUi,
    summary: String?,
    onDismissSummary: () -> Unit,
    lyrics: List<LyricLine>,
    credit: String,
    positionMs: () -> Long,
    tab: ParkedTab,
    onTab: (ParkedTab) -> Unit,
    onPrev: () -> Unit,
    onPlayPause: () -> Unit,
    onNext: () -> Unit,
    queueContent: @Composable () -> Unit,
    recordsContent: @Composable () -> Unit,
    scoresContent: @Composable () -> Unit,
    settingsContent: @Composable () -> Unit,
) {
    // 혼자가 아니면 내 기록, 점수, 설정 탭 자체를 숨긴다.
    val tabs = if (solo) ParkedTab.entries else listOf(ParkedTab.PLAYER, ParkedTab.QUEUE)
    val current = if (tab in tabs) tab else ParkedTab.PLAYER
    Column(Modifier.fillMaxSize().background(Palette.bg).padding(32.dp)) {
        if (summary != null && solo) {
            Row(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(Palette.surfaceHi).padding(24.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(summary, color = Palette.text, fontSize = 28.sp, modifier = Modifier.weight(1f))
                Spacer(Modifier.width(24.dp))
                PillButton("확인", onDismissSummary)
            }
            Spacer(Modifier.height(20.dp))
        }
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            tabs.forEach { t ->
                val selected = t == current
                Box(
                    Modifier.heightIn(min = 80.dp).clip(RoundedCornerShape(16.dp))
                        .background(if (selected) Palette.surfaceHi else Palette.surface)
                        .clickable { onTab(t) }.padding(horizontal = 32.dp),
                    contentAlignment = Alignment.Center,
                ) { Text(t.label, color = if (selected) Palette.text else Palette.sub, fontSize = BODY, fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal) }
            }
        }
        Spacer(Modifier.height(24.dp))
        Box(Modifier.weight(1f).fillMaxWidth()) {
            when (current) {
                ParkedTab.PLAYER -> PlayerTab(state, player, lyrics, credit, positionMs, onPrev, onPlayPause, onNext)
                ParkedTab.QUEUE -> queueContent()
                ParkedTab.RECORDS -> recordsContent()
                ParkedTab.SCORES -> scoresContent()
                ParkedTab.SETTINGS -> settingsContent()
            }
        }
    }
}

@Composable
fun PillButton(label: String, onClick: () -> Unit, color: Color = Palette.surfaceHi, textColor: Color = Palette.text) {
    Box(
        Modifier.heightIn(min = 76.dp).widthIn(min = 140.dp).clip(RoundedCornerShape(38.dp)).background(color)
            .clickable(onClick = onClick).padding(horizontal = 28.dp),
        contentAlignment = Alignment.Center,
    ) { Text(label, color = textColor, fontSize = BODY, fontWeight = FontWeight.SemiBold) }
}

@Composable
private fun PlayerTab(
    state: GateState, player: PlayerUi, lyrics: List<LyricLine>, credit: String, positionMs: () -> Long,
    onPrev: () -> Unit, onPlayPause: () -> Unit, onNext: () -> Unit,
) {
    var pos by remember { mutableLongStateOf(0L) }
    LaunchedEffect(player.songId, player.isPlaying) {
        while (true) { pos = positionMs(); delay(200) }
    }
    val karaoke = state == GateState.KARAOKE
    Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(40.dp)) {
        Column(Modifier.weight(1f).fillMaxSize(), verticalArrangement = Arrangement.Center) {
            Text(statusText(state, player.isPlaying, player.songId != null), color = if (karaoke) Palette.accent else Palette.sub, fontSize = 30.sp, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(16.dp))
            Text(player.title.ifEmpty { " " }, color = Palette.text, fontSize = 42.sp, fontWeight = FontWeight.Bold, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text(player.artist.ifEmpty { " " }, color = Palette.sub, fontSize = 30.sp)
            // 곡 출처와 라이선스(CC BY 표시). 주차 화면에만 둔다.
            if (credit.isNotEmpty()) Text(credit, color = Palette.dim, fontSize = 24.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Spacer(Modifier.height(40.dp))
            TransportRow(player.isPlaying, 112, onPrev, onPlayPause, onNext)
        }
        Column(Modifier.weight(1.2f).fillMaxSize().clip(RoundedCornerShape(24.dp)).background(Palette.surface).padding(32.dp), verticalArrangement = Arrangement.Center) {
            if (lyrics.isEmpty()) {
                Text("가사가 없는 곡입니다", color = Palette.dim, fontSize = BODY)
            } else {
                val idx = lyrics.indexOfLast { it.atMs <= pos }
                val window = (idx - 1..idx + 3)
                window.forEach { i ->
                    val line = lyrics.getOrNull(i)?.text ?: " "
                    val isNow = i == idx
                    Text(
                        line,
                        color = if (isNow) Palette.text else Palette.dim,
                        fontSize = if (isNow) 40.sp else 28.sp,
                        fontWeight = if (isNow) FontWeight.Bold else FontWeight.Normal,
                        modifier = Modifier.padding(vertical = 10.dp),
                        maxLines = 2, overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}

// ---------- 예약 목록 ----------

data class SongRow(val id: String, val title: String, val artist: String)

@Composable
fun QueueTab(player: PlayerUi, songs: List<SongRow>, onPlayNow: (String) -> Unit, onReserve: (String) -> Unit, onRemove: (Int) -> Unit) {
    Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(32.dp)) {
        Column(Modifier.weight(1f)) {
            Text("곡", color = Palette.sub, fontSize = BODY)
            Spacer(Modifier.height(12.dp))
            LazyColumn(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                itemsIndexed(songs) { _, s ->
                    Row(
                        Modifier.fillMaxWidth().heightIn(min = 104.dp).clip(RoundedCornerShape(16.dp)).background(Palette.surface)
                            .clickable { onPlayNow(s.id) }.padding(horizontal = 24.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(s.title, color = Palette.text, fontSize = 28.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(s.artist, color = Palette.sub, fontSize = 24.sp, maxLines = 1)
                        }
                        PillButton("예약", { onReserve(s.id) })
                    }
                }
            }
        }
        Column(Modifier.weight(1f)) {
            Text("이어서 나올 곡", color = Palette.sub, fontSize = BODY)
            Spacer(Modifier.height(12.dp))
            if (player.queue.isEmpty()) Text("비어 있습니다", color = Palette.dim, fontSize = BODY)
            LazyColumn(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                itemsIndexed(player.queue) { i, q ->
                    val now = i == player.currentIndex
                    Row(
                        Modifier.fillMaxWidth().heightIn(min = 96.dp).clip(RoundedCornerShape(16.dp))
                            .background(if (now) Palette.surfaceHi else Palette.surface).padding(horizontal = 24.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text("${i + 1}", color = Palette.dim, fontSize = BODY, modifier = Modifier.width(56.dp))
                        Column(Modifier.weight(1f)) {
                            Text(q.title, color = Palette.text, fontSize = 28.sp, fontWeight = if (now) FontWeight.Bold else FontWeight.Normal, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(q.artist, color = Palette.sub, fontSize = 24.sp, maxLines = 1)
                        }
                        if (!now && i > player.currentIndex) PillButton("빼기", { onRemove(i) }, Palette.surface, Palette.sub)
                    }
                }
            }
        }
    }
}

// ---------- 내 기록 ----------

/** 비밀 차트 한 줄: 이번 달 혼자 부른 횟수와 그 곡에서 혼자 부른 시간. */
data class ChartRow(val title: String, val plays: Int, val soloMs: Long)

data class MonthRecords(
    val soloSongs: Int = 0,
    val soloMs: Long = 0,
    val top: List<ChartRow> = emptyList(),
    val exposures: Map<String, Int> = emptyMap(),
    val fastestMs: Long? = null,
)

fun minutesText(ms: Long): String {
    val totalSec = ms / 1000
    return if (totalSec < 60) "${totalSec}초" else "${(totalSec + 30) / 60}분"
}

fun secondsText(ms: Long): String = "%.2f초".format(ms / 1000.0)

@Composable
fun RecordsTab(r: MonthRecords) {
    Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(32.dp)) {
        Column(Modifier.weight(1f).clip(RoundedCornerShape(24.dp)).background(Palette.surface).padding(32.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Text("이번 달", color = Palette.sub, fontSize = BODY)
            Text("혼자 부른 곡 ${r.soloSongs}곡", color = Palette.text, fontSize = 36.sp, fontWeight = FontWeight.Bold)
            Text("혼자 부른 시간 ${minutesText(r.soloMs)}", color = Palette.text, fontSize = 32.sp)
            Spacer(Modifier.height(8.dp))
            val total = r.exposures.values.sum()
            Text("들킬 뻔한 순간 ${total}번", color = Palette.text, fontSize = 32.sp)
            if (total > 0) {
                val parts = listOf("DOOR" to "문", "SEAT" to "좌석", "WINDOW" to "창문").mapNotNull { (k, label) -> r.exposures[k]?.let { "$label ${it}번" } }
                Text(parts.joinToString(" · "), color = Palette.sub, fontSize = BODY)
            }
            r.fastestMs?.let { Text("가장 빠른 대피 ${secondsText(it)}", color = Palette.accent, fontSize = 30.sp) }
        }
        Column(Modifier.weight(1f).clip(RoundedCornerShape(24.dp)).background(Palette.surface).padding(32.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Text("나만의 비밀 차트 · 많이 부른 곡", color = Palette.sub, fontSize = BODY)
            if (r.top.isEmpty()) Text("아직 없습니다. 혼자 10초 넘게 부른 곡이 여기에 올라갑니다", color = Palette.dim, fontSize = BODY)
            val most = r.top.maxOfOrNull { it.plays } ?: 1
            r.top.forEachIndexed { i, row ->
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("${i + 1}", color = if (i == 0) Palette.accent else Palette.dim, fontSize = 28.sp, fontWeight = FontWeight.Bold, modifier = Modifier.width(48.dp))
                        Text(row.title, color = Palette.text, fontSize = 28.sp, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text("${row.plays}번 · ${minutesText(row.soloMs)}", color = Palette.sub, fontSize = BODY)
                    }
                    Box(Modifier.padding(start = 48.dp).fillMaxWidth().height(10.dp).clip(RoundedCornerShape(5.dp)).background(Palette.bg)) {
                        Box(Modifier.fillMaxWidth(row.plays.toFloat() / most).fillMaxHeight().clip(RoundedCornerShape(5.dp)).background(if (i == 0) Palette.accent else Palette.sub))
                    }
                }
            }
        }
    }
}

// ---------- 점수 ----------

data class ScoreRowUi(val title: String, val best: Int?, val last: Int, val times: Int)

data class LastScoreUi(val title: String, val score: Int, val finished: Boolean, val previousBest: Int?)

@Composable
fun ScoresTab(scoring: Boolean, last: LastScoreUi?, rows: List<ScoreRowUi>) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        if (!scoring) {
            Text("설정에서 「노래 점수」를 켜면 혼자만의 노래방에서 부른 노래에 점수를 매깁니다.", color = Palette.sub, fontSize = BODY)
        }
        if (last != null) {
            Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(Palette.surfaceHi).padding(28.dp)) {
                Text("방금 부른 곡 · 지난번의 나와 대결", color = Palette.sub, fontSize = 26.sp)
                Spacer(Modifier.height(8.dp))
                Row(verticalAlignment = Alignment.Bottom) {
                    Text(last.title, color = Palette.text, fontSize = 34.sp, fontWeight = FontWeight.Bold, maxLines = 1,
                        overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                    Spacer(Modifier.width(24.dp))
                    Text("${last.score}점", color = Palette.accent, fontSize = 64.sp, fontWeight = FontWeight.Bold)
                }
                Spacer(Modifier.height(8.dp))
                Text(battleLine(last), color = Palette.text, fontSize = 28.sp)
            }
        }
        if (rows.isEmpty()) {
            if (scoring) Text("아직 점수가 없습니다. 혼자만의 노래방에서 한 곡을 끝까지 불러 보세요.", color = Palette.sub, fontSize = BODY)
        } else {
            Text("곡별 내 점수", color = Palette.sub, fontSize = 26.sp)
            rows.forEach { r ->
                Row(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(Palette.surface).padding(horizontal = 28.dp, vertical = 20.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(r.title, color = Palette.text, fontSize = BODY, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                    Text("최고 ${r.best?.let { "${it}점" } ?: "—"}", color = Palette.accent, fontSize = BODY, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.width(32.dp))
                    Text("최근 ${r.last}점 · ${r.times}번", color = Palette.sub, fontSize = BODY)
                }
            }
        }
    }
}

private fun battleLine(l: LastScoreUi): String = when {
    !l.finished -> "곡의 절반 넘게 불러야 최고 기록에 들어갑니다"
    l.previousBest == null -> "첫 기록입니다. 다음엔 이 점수를 넘어 보세요"
    l.score > l.previousBest -> "지난 최고 ${l.previousBest}점을 ${l.score - l.previousBest}점 넘었습니다. 새 최고 기록!"
    l.score == l.previousBest -> "지난 최고 ${l.previousBest}점과 같습니다"
    else -> "지난 최고 ${l.previousBest}점까지 ${l.previousBest - l.score}점 모자랍니다"
}

// ---------- 설정 ----------

data class SettingsUi(
    val enabled: Boolean,
    val guidePercent: Int,
    val dwellSeconds: Int,
    val windowThresholdPct: Int,
    val safetyReturn: Boolean,
    val countIn: Boolean,
    val scoring: Boolean,
    val invertSeat: Boolean,
    val rearSeatsUnreadable: Boolean,
    val passengerUnreadable: Boolean,
)

@Composable
fun SettingsTab(
    s: SettingsUi,
    onEnabled: (Boolean) -> Unit,
    onGuide: (Int) -> Unit,
    onDwell: (Int) -> Unit,
    onWindow: (Int) -> Unit,
    onSafety: (Boolean) -> Unit,
    onCountIn: (Boolean) -> Unit,
    onScoring: (Boolean) -> Unit,
    onInvert: (Boolean) -> Unit,
    onClear: () -> Unit,
) {
    var confirmClear by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        SettingSwitch("혼자일 때 가수 목소리 줄이기", "끄면 평범한 음악 플레이어로만 씁니다", s.enabled, onEnabled)
        SettingChoice("가이드 음량(가수 목소리 남기는 정도)", listOf(0, 10, 20, 30, 40), s.guidePercent, { "$it%" }, onGuide)
        SettingChoice("대기 시간(혼자·문 닫힘이 이만큼 이어지면 열림)", listOf(3, 5, 8), s.dwellSeconds, { "${it}초" }, onDwell)
        SettingChoice("창문 기준(이보다 많이 열리면 닫힘)", listOf(2, 10, 20), s.windowThresholdPct, { "$it%" }, onWindow)
        SettingSwitch("안전 장치", "후진하거나 방향지시등을 켜면 가수 목소리를 잠깐 돌려놓습니다", s.safetyReturn, onSafety)
        SettingSwitch("첫 소절 박자 세기", "노래방일 때 첫 소절 바로 앞에서 드럼 스틱으로 탁, 탁, 탁, 탁 박자를 세어 줍니다", s.countIn, onCountIn)
        SettingSwitch("노래 점수(마이크)", "혼자만의 노래방일 때만 마이크로 음높이를 듣고 100점 만점으로 매깁니다. 소리는 저장하지 않습니다", s.scoring, onScoring)
        SettingSwitch("착석 값 뒤집기", "Pleos Connect Emulator 2.1.0은 착석 값이 반대로 옵니다. 실제 차에서 반대로 동작하면 끄세요", s.invertSeat, onInvert)
        if (s.passengerUnreadable) Text("이 차는 동승석 착석을 읽을 수 없어 이 기능이 열리지 않습니다", color = Palette.danger, fontSize = BODY)
        if (s.rearSeatsUnreadable) Text("이 차는 뒷좌석 착석을 읽을 수 없어 뒷좌석은 비어 있다고 봅니다", color = Palette.sub, fontSize = BODY)
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            if (!confirmClear) PillButton("기록 지우기", { confirmClear = true }, Palette.surface, Palette.danger)
            else {
                Text("모든 기록을 지울까요?", color = Palette.text, fontSize = BODY)
                PillButton("지우기", { onClear(); confirmClear = false }, Palette.danger, Palette.bg)
                PillButton("취소", { confirmClear = false })
            }
        }
        Text(
            if (s.scoring) "기록은 이 차 안에만 저장되고 밖으로 보내지 않습니다. 마이크는 노래 점수를 켰을 때, 혼자만의 노래방에서만 씁니다."
            else "기록은 이 차 안에만 저장되고 밖으로 보내지 않습니다. 마이크를 쓰지 않습니다.",
            color = Palette.dim, fontSize = 24.sp,
        )
    }
}

@Composable
private fun SettingSwitch(title: String, sub: String, value: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = 104.dp).clip(RoundedCornerShape(16.dp)).background(Palette.surface)
            .clickable { onChange(!value) }.padding(horizontal = 28.dp, vertical = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, color = Palette.text, fontSize = 28.sp)
            Text(sub, color = Palette.sub, fontSize = 24.sp)
        }
        Switch(
            checked = value, onCheckedChange = onChange,
            modifier = Modifier.size(width = 96.dp, height = 56.dp),
            colors = SwitchDefaults.colors(checkedTrackColor = Palette.accent, checkedThumbColor = Palette.bg),
        )
    }
}

@Composable
private fun SettingChoice(title: String, options: List<Int>, value: Int, label: (Int) -> String, onChange: (Int) -> Unit) {
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(Palette.surface).padding(horizontal = 28.dp, vertical = 20.dp)) {
        Text(title, color = Palette.text, fontSize = 28.sp)
        Spacer(Modifier.height(12.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            options.forEach { o ->
                val sel = o == value
                Box(
                    Modifier.heightIn(min = 76.dp).widthIn(min = 120.dp).clip(RoundedCornerShape(14.dp))
                        .background(if (sel) Palette.accent else Palette.surfaceHi).clickable { onChange(o) },
                    contentAlignment = Alignment.Center,
                ) { Text(label(o), color = if (sel) Palette.bg else Palette.text, fontSize = BODY, fontWeight = FontWeight.SemiBold, textAlign = TextAlign.Center) }
            }
        }
    }
}
