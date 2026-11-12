package app.sichimi.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.sichimi.audio.TakePlayUi
import app.sichimi.gate.GateState
import app.sichimi.rank.RankEntry
import app.sichimi.songs.LyricLine
import kotlinx.coroutines.delay

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

private fun clock(ms: Long): String {
    val s = (ms / 1000).coerceAtLeast(0)
    return "%d:%02d".format(s / 60, s % 60)
}

// ---------- 주행 화면 ----------

/**
 * 주행 중(기어가 P가 아니거나 주행 규제)에 보이는 한 화면. 곡 제목, 가수, 버튼 세 개뿐이다. 목록·가사·점수·긴 글은 없다.
 * 버튼 높이 136dp. 노래방일 때 달라지는 것은 상태 한 줄의 색뿐이다.
 */
@Composable
fun DrivingScreen(state: GateState, player: PlayerUi, onPrev: () -> Unit, onPlayPause: () -> Unit, onNext: () -> Unit) {
    val karaoke = state == GateState.KARAOKE
    Column(
        Modifier.fillMaxSize().background(Palette.bg).padding(horizontal = 64.dp, vertical = Dimen.xl),
        verticalArrangement = Arrangement.Center,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            ArtTile(264.dp)
            Spacer(Modifier.width(Dimen.xl))
            Column(Modifier.weight(1f)) {
                StatusLine(statusText(state, player.isPlaying, player.songId != null), karaoke, player.isPlaying, big = true)
                Spacer(Modifier.height(Dimen.s))
                Text(player.title.ifEmpty { "시치미" }, style = Type.headline, color = Palette.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Spacer(Modifier.height(Dimen.xs))
                Text(player.artist.ifEmpty { " " }, style = Type.bodyL.copy(fontSize = Type.titleS.fontSize), color = Palette.sub, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        Spacer(Modifier.height(64.dp))
        TransportRow(player.isPlaying, Dimen.driveButton, onPrev, onPlayPause, onNext)
    }
}

// ---------- 주차 화면 ----------

enum class ParkedTab(val label: String) { PLAYER("재생·가사"), QUEUE("예약 목록"), RECORDS("내 기록"), SCORES("점수"), SETTINGS("설정") }

private fun ParkedTab.icon(): ImageVector = when (this) {
    ParkedTab.PLAYER -> AppIcons.Note
    ParkedTab.QUEUE -> AppIcons.Queue
    ParkedTab.RECORDS -> AppIcons.Chart
    ParkedTab.SCORES -> AppIcons.Trophy
    ParkedTab.SETTINGS -> AppIcons.Gear
}

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
    Column(Modifier.fillMaxSize().background(Palette.bg).padding(Dimen.l)) {
        SegmentedTabs(tabs, current, { it.label }, { it.icon() }, onTab)
        Spacer(Modifier.height(Dimen.m))
        if (summary != null && solo) {
            Row(
                Modifier.fillMaxWidth().card(Palette.surfaceHi).padding(start = Dimen.l, end = Dimen.s, top = Dimen.s, bottom = Dimen.s),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(AppIcons.Chart, null, tint = Palette.karaoke, modifier = Modifier.size(Dimen.icon))
                Spacer(Modifier.width(Dimen.m))
                Text(summary, style = Type.bodyM, color = Palette.text, modifier = Modifier.weight(1f))
                Spacer(Modifier.width(Dimen.m))
                AppButton("확인", onDismissSummary, style = ButtonStyle.TONAL)
            }
            Spacer(Modifier.height(Dimen.m))
        }
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

/** 예전 이름을 쓰는 곳을 위해 남긴 알약 버튼. 새 화면은 [AppButton]을 쓴다. */
@Composable
fun PillButton(label: String, onClick: () -> Unit, color: Color = Palette.control, textColor: Color = Palette.text) {
    AppButton(label, onClick, style = if (color == Palette.accent) ButtonStyle.FILLED else ButtonStyle.TONAL)
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
    // 위치는 200ms마다 읽지만, 화면은 가사 줄이나 초가 바뀔 때만 다시 그린다
    val lyricIdx by remember(lyrics) { derivedStateOf { lyrics.indexOfLast { it.atMs <= pos } } }
    val sec by remember { derivedStateOf { pos / 1000 } }
    Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(Dimen.l)) {
        Column(Modifier.weight(1f).fillMaxHeight().card().padding(Dimen.l)) {
            ArtTile(240.dp)
            Spacer(Modifier.height(Dimen.m))
            StatusLine(statusText(state, player.isPlaying, player.songId != null), karaoke, player.isPlaying)
            Text(player.title.ifEmpty { "시치미" }, style = Type.titleL, color = Palette.text, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text(player.artist.ifEmpty { " " }, style = Type.bodyL, color = Palette.sub, maxLines = 1, overflow = TextOverflow.Ellipsis)
            // 곡 출처와 라이선스(CC BY 표시). 주차 화면에만 둔다.
            if (credit.isNotEmpty()) {
                Spacer(Modifier.height(Dimen.xs))
                Text(credit, style = Type.label, color = Palette.dim, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
            Spacer(Modifier.weight(1f))
            val dur = player.durationMs
            val frac = if (dur > 0) (sec * 1000f / dur).coerceIn(0f, 1f) else 0f
            Box(Modifier.fillMaxWidth().height(8.dp).clip(RoundedCornerShape(4.dp)).background(Palette.surfaceHi)) {
                Box(Modifier.fillMaxWidth(frac).fillMaxHeight().clip(RoundedCornerShape(4.dp)).background(Palette.sub))
            }
            Spacer(Modifier.height(Dimen.xs))
            // 경과 시간은 1초마다 바뀌므로 접근성 트리에서 뺀다(계속 바뀌는 글자가 화면 읽기를 방해하지 않게)
            Row(Modifier.fillMaxWidth().clearAndSetSemantics { }) {
                Text(clock(sec * 1000), style = Type.label, color = Palette.dim, modifier = Modifier.weight(1f))
                Text(if (dur > 0) clock(dur) else "", style = Type.label, color = Palette.dim)
            }
            Spacer(Modifier.height(Dimen.m))
            TransportRow(player.isPlaying, Dimen.parkButton, onPrev, onPlayPause, onNext)
        }
        // 가사: 지금 줄만 크고 밝게. 상태와 상관없이 같은 모양(노래방 색을 쓰지 않는다).
        Column(Modifier.weight(1.15f).fillMaxHeight().card().padding(horizontal = Dimen.xl, vertical = Dimen.l), verticalArrangement = Arrangement.Center) {
            if (lyrics.isEmpty()) {
                Text("가사가 없는 곡입니다", style = Type.bodyL, color = Palette.dim)
            } else {
                val idx = lyricIdx
                (idx - 1..idx + 3).forEach { i ->
                    val line = lyrics.getOrNull(i)?.text ?: " "
                    val isNow = i == idx
                    Text(
                        line,
                        style = if (isNow) Type.titleL else Type.bodyL,
                        color = when { isNow -> Palette.text; i < idx -> Palette.dim; else -> Palette.sub },
                        modifier = Modifier.padding(vertical = 12.dp),
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
    Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(Dimen.l)) {
        Column(Modifier.weight(1f).fillMaxHeight().card().padding(vertical = Dimen.m)) {
            ListHeader(AppIcons.Note, "곡", "${songs.size}곡")
            LazyColumn {
                itemsIndexed(songs) { i, s ->
                    if (i > 0) Divider(start = 128.dp)
                    Row(
                        Modifier.fillMaxWidth().heightIn(min = 112.dp)
                            .pressable({ onPlayNow(s.id) }, Palette.surface, Palette.surfaceHi, RoundedCornerShape(0.dp))
                            .padding(horizontal = Dimen.l),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        ArtTile(72.dp)
                        Spacer(Modifier.width(Dimen.m))
                        Column(Modifier.weight(1f)) {
                            Text(s.title, style = Type.bodyM.copy(fontWeight = FontWeight.SemiBold), color = Palette.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(s.artist, style = Type.label, color = Palette.sub, maxLines = 1)
                        }
                        Spacer(Modifier.width(Dimen.s))
                        AppButton("예약", { onReserve(s.id) }, icon = AppIcons.Plus, style = ButtonStyle.TONAL)
                    }
                }
            }
        }
        Column(Modifier.weight(1f).fillMaxHeight().card().padding(vertical = Dimen.m)) {
            ListHeader(AppIcons.Queue, "이어서 나올 곡", if (player.queue.isEmpty()) "" else "${player.queue.size}곡")
            if (player.queue.isEmpty()) Body("비어 있습니다", Palette.dim, Type.bodyM, Modifier.padding(horizontal = Dimen.l, vertical = Dimen.s))
            LazyColumn {
                itemsIndexed(player.queue) { i, q ->
                    val now = i == player.currentIndex
                    if (i > 0) Divider(start = 104.dp)
                    Row(
                        Modifier.fillMaxWidth().heightIn(min = 104.dp).background(if (now) Palette.surfaceHi else Palette.surface)
                            .padding(horizontal = Dimen.l),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Box(Modifier.width(48.dp), contentAlignment = Alignment.CenterStart) {
                            if (now) Icon(AppIcons.Note, null, tint = Palette.text, modifier = Modifier.size(32.dp))
                            else Text("${i + 1}", style = Type.bodyM, color = Palette.dim)
                        }
                        Spacer(Modifier.width(Dimen.s))
                        Column(Modifier.weight(1f)) {
                            Text(q.title, style = Type.bodyM.copy(fontWeight = if (now) FontWeight.Bold else FontWeight.SemiBold),
                                color = Palette.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(q.artist, style = Type.label, color = Palette.sub, maxLines = 1)
                        }
                        if (!now && i > player.currentIndex) AppButton("빼기", { onRemove(i) }, icon = AppIcons.Minus, style = ButtonStyle.QUIET)
                    }
                }
            }
        }
    }
}

@Composable
private fun ListHeader(icon: ImageVector, title: String, trailing: String) {
    Row(Modifier.fillMaxWidth().padding(horizontal = Dimen.l, vertical = Dimen.s), verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, null, tint = Palette.sub, modifier = Modifier.size(32.dp))
        Spacer(Modifier.width(Dimen.s))
        Text(title, style = Type.titleS, color = Palette.text, modifier = Modifier.weight(1f))
        Text(trailing, style = Type.label, color = Palette.dim)
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
private fun StatTile(icon: ImageVector, label: String, value: String, modifier: Modifier, sub: String? = null, highlight: Boolean = false) {
    Column(modifier.card().padding(Dimen.l), verticalArrangement = Arrangement.spacedBy(Dimen.s, Alignment.CenterVertically)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, null, tint = if (highlight) Palette.karaoke else Palette.sub, modifier = Modifier.size(30.dp))
            Spacer(Modifier.width(12.dp))
            Text(label, style = Type.bodyS, color = Palette.sub)
        }
        Text(value, style = Type.display, color = if (highlight) Palette.karaoke else Palette.text, maxLines = 1)
        if (sub != null) Text(sub, style = Type.label, color = Palette.sub, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
fun RecordsTab(r: MonthRecords) {
    Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(Dimen.l)) {
        Column(Modifier.weight(1f).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(Dimen.m)) {
            Text("이번 달", style = Type.bodyM.copy(fontWeight = FontWeight.SemiBold), color = Palette.sub, modifier = Modifier.padding(start = 8.dp))
            val total = r.exposures.values.sum()
            val parts = listOf("DOOR" to "문", "SEAT" to "좌석", "WINDOW" to "창문").mapNotNull { (k, label) -> r.exposures[k]?.let { "$label ${it}번" } }
            Row(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(Dimen.m)) {
                StatTile(AppIcons.Mic, "혼자 부른 곡", "${r.soloSongs}곡", Modifier.weight(1f).fillMaxHeight())
                StatTile(AppIcons.Clock, "혼자 부른 시간", minutesText(r.soloMs), Modifier.weight(1f).fillMaxHeight())
            }
            Row(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(Dimen.m)) {
                StatTile(AppIcons.Eye, "들킬 뻔한 순간", "${total}번", Modifier.weight(1f).fillMaxHeight(), if (total > 0) parts.joinToString(" · ") else "아직 없음")
                StatTile(AppIcons.Bolt, "가장 빠른 대피", r.fastestMs?.let { secondsText(it) } ?: "—", Modifier.weight(1f).fillMaxHeight(), highlight = r.fastestMs != null)
            }
        }
        Column(Modifier.weight(1f).fillMaxHeight().card().padding(vertical = Dimen.m)) {
            ListHeader(AppIcons.Chart, "나만의 비밀 차트", "많이 부른 곡")
            if (r.top.isEmpty()) Body("아직 없습니다. 혼자 10초 넘게 부른 곡이 여기에 올라갑니다", Palette.dim, Type.bodyM, Modifier.padding(horizontal = Dimen.l, vertical = Dimen.s))
            val most = r.top.maxOfOrNull { it.plays } ?: 1
            r.top.forEachIndexed { i, row ->
                if (i > 0) Divider()
                Column(Modifier.padding(horizontal = Dimen.l, vertical = 20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("${i + 1}", style = Type.titleS, color = if (i == 0) Palette.karaoke else Palette.dim, modifier = Modifier.width(56.dp))
                        Text(row.title, style = Type.bodyM.copy(fontWeight = FontWeight.SemiBold), color = Palette.text, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text("${row.plays}번 · ${minutesText(row.soloMs)}", style = Type.bodyS, color = Palette.sub)
                    }
                    Box(Modifier.padding(start = 56.dp).fillMaxWidth().height(12.dp).clip(RoundedCornerShape(6.dp)).background(Palette.surfaceHi)) {
                        Box(Modifier.fillMaxWidth(row.plays.toFloat() / most).fillMaxHeight().clip(RoundedCornerShape(6.dp))
                            .background(if (i == 0) Palette.karaoke else Palette.sub))
                    }
                }
            }
        }
    }
}

// ---------- 점수 ----------

data class ScoreRowUi(val title: String, val best: Int?, val last: Int, val times: Int)

data class LastScoreUi(val title: String, val score: Int, val finished: Boolean, val previousBest: Int?)

/** 전국 랭킹 칸의 상태. */
sealed interface RankUi {
    data object NotConfigured : RankUi
    data object Loading : RankUi
    data object Failed : RankUi
    data class Ready(val top: List<RankEntry>, val myRank: Int?, val total: Int) : RankUi
}

@Composable
fun ScoresTab(
    scoring: Boolean,
    last: LastScoreUi?,
    rows: List<ScoreRowUi>,
    rankSongs: List<Pair<String, String>>,
    rankSong: String?,
    onRankSong: (String) -> Unit,
    rank: RankUi,
    uploading: Boolean,
    recording: Boolean = false,
    myTakes: List<MyTakeUi> = emptyList(),
    play: TakePlayUi = TakePlayUi(),
    takeActions: TakeActions? = null,
    takeWorking: String? = null,
) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(Dimen.l)) {
        if (!scoring) Notice(AppIcons.Info, "설정에서 「노래 점수」를 켜면 혼자만의 노래방에서 부른 노래에 점수를 매깁니다.")
        if (last != null) {
            Row(Modifier.fillMaxWidth().card(Palette.surfaceHi).padding(Dimen.l), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(Dimen.xs)) {
                    Text("방금 부른 곡 · 지난번의 나와 대결", style = Type.bodyS, color = Palette.sub)
                    Text(last.title, style = Type.titleM, color = Palette.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(battleLine(last), style = Type.bodyM, color = Palette.text)
                }
                Spacer(Modifier.width(Dimen.l))
                Text("${last.score}점", style = Type.display, color = Palette.karaoke)
            }
        }
        if (rows.isEmpty()) {
            if (scoring) Notice(AppIcons.Mic, "아직 점수가 없습니다. 혼자만의 노래방에서 한 곡을 끝까지 불러 보세요.")
        } else {
            Section("곡별 내 점수", AppIcons.Trophy) {
                rows.forEachIndexed { i, r ->
                    if (i > 0) Divider()
                    Row(Modifier.fillMaxWidth().heightIn(min = 96.dp).padding(horizontal = Dimen.l), verticalAlignment = Alignment.CenterVertically) {
                        Text(r.title, style = Type.bodyM.copy(fontWeight = FontWeight.SemiBold), color = Palette.text, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                        Text("최근 ${r.last}점 · ${r.times}번", style = Type.bodyS, color = Palette.sub)
                        Spacer(Modifier.width(Dimen.l))
                        Text("최고 ${r.best?.let { "${it}점" } ?: "—"}", style = Type.titleS, color = Palette.karaoke)
                    }
                }
            }
        }
        if ((recording || myTakes.isNotEmpty()) && takeActions != null) MyTakesSection(myTakes, play, takeActions, recording, takeWorking)
        RankSection(rankSongs, rankSong, onRankSong, rank, uploading, play, takeActions)
    }
}

@Composable
private fun Notice(icon: ImageVector, text: String) {
    Row(Modifier.fillMaxWidth().card().padding(Dimen.l), verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, null, tint = Palette.sub, modifier = Modifier.size(Dimen.icon))
        Spacer(Modifier.width(Dimen.m))
        Text(text, style = Type.bodyM, color = Palette.sub)
    }
}

// ---------- 내 녹음 ----------

/** 차 안에 둔 내 녹음 한 줄. */
data class MyTakeUi(
    val songId: String, val title: String, val score: Int, val lengthMs: Long, val kbps: Int, val kb: Int,
    val cut: Boolean, val uploaded: Boolean, val status: String?,
)

/** 녹음 듣기·올리기·지우기. 화면은 주차하고 혼자일 때만 보인다(점수 탭). */
class TakeActions(
    val playMine: (songId: String) -> Unit,
    val playOther: (songId: String, entry: RankEntry) -> Unit,
    val stop: () -> Unit,
    val upload: (songId: String) -> Unit,
    val delete: (songId: String) -> Unit,
)

fun lengthText(ms: Long): String {
    val s = ms / 1000
    return if (s < 60) "${s}초" else "${s / 60}분 %02d초".format(s % 60)
}

@Composable
private fun MyTakesSection(takes: List<MyTakeUi>, play: TakePlayUi, a: TakeActions, canUpload: Boolean, working: String?) {
    Section("내 녹음 · 최고 기록을 낸 판의 내 목소리", AppIcons.Mic) {
        if (working != null) Body("방금 부른 곡의 녹음을 정리하는 중입니다…", Palette.text, Type.bodyM, Modifier.padding(Dimen.l))
        if (takes.isEmpty() && working == null) {
            Body("아직 없습니다. 한 곡을 끝까지 불러 최고 기록이 되면 그 판의 목소리가 여기에 남습니다(차 안에만).", Palette.dim, Type.bodyM, Modifier.padding(Dimen.l))
        }
        takes.forEachIndexed { i, t ->
            if (i > 0 || working != null) Divider()
            var confirm by remember(t.songId) { mutableStateOf(false) }
            Column(Modifier.fillMaxWidth().padding(Dimen.l), verticalArrangement = Arrangement.spacedBy(Dimen.s)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("${t.title} · ${t.score}점", style = Type.bodyL.copy(fontWeight = FontWeight.SemiBold), color = Palette.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text("${lengthText(t.lengthMs)} · ${t.kbps}kbps · ${t.kb}KB" + (if (t.cut) " · 앞 7분만" else ""), style = Type.label, color = Palette.sub)
                    }
                    Spacer(Modifier.width(Dimen.s))
                    Badge(if (t.uploaded) "전국 랭킹에 공개됨" else "차 안에만 있음", if (t.uploaded) AppIcons.Upload else AppIcons.Lock, t.uploaded)
                }
                Row(horizontalArrangement = Arrangement.spacedBy(Dimen.s), verticalAlignment = Alignment.CenterVertically) {
                    ListenButton("mine:${t.songId}", play, "미리 듣기", { a.playMine(t.songId) }, a.stop)
                    if (!t.uploaded && canUpload) AppButton("녹음 올리기", { a.upload(t.songId) }, icon = AppIcons.Upload, style = ButtonStyle.FILLED)
                    AppButton("지우기", { confirm = true }, icon = AppIcons.Trash, style = ButtonStyle.DANGER)
                }
                if (!t.uploaded && canUpload && t.status == null) Text("올리면 전국 랭킹에서 누구나 이 목소리를 들을 수 있습니다. 반주는 올리지 않습니다.", style = Type.label, color = Palette.dim)
                t.status?.let { Text(it, style = Type.bodyS, color = Palette.text) }
            }
            if (confirm) {
                ConfirmDialog(
                    AppIcons.Trash, Palette.danger, "내 녹음을 지울까요?",
                    if (t.uploaded) "서버에 올린 녹음과 이 차에 있는 녹음을 모두 지웁니다. 점수는 그대로 남습니다." else "이 차에 있는 녹음을 지웁니다. 점수는 그대로 남습니다.",
                    if (t.uploaded) "서버와 차에서 지우기" else "차에서 지우기",
                    { a.delete(t.songId); confirm = false }, { confirm = false }, ButtonStyle.DANGER,
                )
            }
        }
    }
}

@Composable
private fun Badge(text: String, icon: ImageVector, strong: Boolean) {
    Row(
        Modifier.clip(RoundedCornerShape(50)).background(if (strong) Palette.surfaceHi else Palette.bg).padding(horizontal = 18.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, null, tint = if (strong) Palette.text else Palette.sub, modifier = Modifier.size(26.dp))
        Spacer(Modifier.width(8.dp))
        Text(text, style = Type.label, color = if (strong) Palette.text else Palette.sub)
    }
}

@Composable
private fun ListenButton(key: String, play: TakePlayUi, idleLabel: String, onPlay: () -> Unit, onStop: () -> Unit) {
    val mine = play.key == key
    when {
        mine && play.phase == TakePlayUi.Phase.PLAYING -> AppButton("정지", onStop, icon = AppIcons.Stop, style = ButtonStyle.FILLED)
        mine && play.phase == TakePlayUi.Phase.LOADING -> AppButton("불러오는 중…", onStop, icon = AppIcons.Headphones, style = ButtonStyle.TONAL)
        mine && play.phase == TakePlayUi.Phase.FAILED -> AppButton("다시 듣기", onPlay, icon = AppIcons.Headphones, style = ButtonStyle.DANGER)
        else -> AppButton(idleLabel, onPlay, icon = AppIcons.Headphones, style = ButtonStyle.TONAL)
    }
}

@Composable
private fun RankSection(
    songs: List<Pair<String, String>>, selected: String?, onSelect: (String) -> Unit, rank: RankUi, uploading: Boolean,
    play: TakePlayUi, actions: TakeActions?,
) {
    Column(verticalArrangement = Arrangement.spacedBy(Dimen.s)) {
        Row(Modifier.padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(AppIcons.Trophy, null, tint = Palette.sub, modifier = Modifier.size(30.dp))
            Spacer(Modifier.width(12.dp))
            Text("전국 랭킹", style = Type.bodyM.copy(fontWeight = FontWeight.SemiBold), color = Palette.sub)
        }
        if (rank == RankUi.NotConfigured) {
            Notice(AppIcons.Info, "전국 랭킹 서버가 아직 연결되지 않았습니다.")
            return
        }
        Row(Modifier.horizontalScroll(rememberScrollState())) {
            SegmentedTabs(songs.map { it.first }, selected ?: "", { id -> songs.firstOrNull { it.first == id }?.second ?: id }, { null }, onSelect)
        }
        Column(Modifier.fillMaxWidth().card()) {
            when (rank) {
                RankUi.Loading -> Body("불러오는 중…", Palette.dim, Type.bodyM, Modifier.padding(Dimen.l))
                RankUi.Failed -> Body("랭킹을 불러오지 못했습니다. 네트워크를 확인해 주세요.", Palette.dim, Type.bodyM, Modifier.padding(Dimen.l))
                is RankUi.Ready -> {
                    if (rank.top.isEmpty()) Body("아직 이 곡의 기록이 없습니다. 첫 번째가 되어 보세요.", Palette.dim, Type.bodyM, Modifier.padding(Dimen.l))
                    if (rank.top.any { it.take != null }) Body("「듣기」는 다른 사람이 올린 목소리를 앱에 든 반주 위에 맞춰 틉니다. 주차 중에만 들을 수 있습니다.",
                        Palette.dim, Type.label, Modifier.padding(start = Dimen.l, end = Dimen.l, top = Dimen.m))
                    rank.top.forEachIndexed { i, e ->
                        if (i > 0) Divider(start = 120.dp)
                        Row(
                            Modifier.fillMaxWidth().heightIn(min = 104.dp).background(if (e.mine) Palette.surfaceHi else Palette.surface)
                                .padding(horizontal = Dimen.l),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Box(
                                Modifier.size(64.dp).clip(CircleShape).background(if (e.rank == 1) Palette.karaoke else Palette.control),
                                contentAlignment = Alignment.Center,
                            ) { Text("${e.rank}위", style = Type.label.copy(fontWeight = FontWeight.Bold), color = if (e.rank == 1) Palette.onKaraoke else Palette.text) }
                            Spacer(Modifier.width(Dimen.m))
                            Text(e.nickname + if (e.mine) " (나)" else "", style = Type.bodyM.copy(fontWeight = FontWeight.SemiBold), color = Palette.text,
                                modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text("${e.score}점", style = Type.titleS, color = Palette.text)
                            // 녹음이 있으면 듣기(점수 탭은 주차·혼자일 때만 보인다. 재생기도 그때만 튼다)
                            if (e.take != null && actions != null && selected != null) {
                                Spacer(Modifier.width(Dimen.m))
                                ListenButton("$selected/${e.uid}", play, "듣기 ${lengthText(e.take.lengthMs)}", { actions.playOther(selected, e) }, actions.stop)
                            }
                        }
                    }
                    val me = rank.myRank
                    Divider(start = 0.dp)
                    Body(
                        when {
                            !uploading -> "설정에서 「전국 랭킹에 올리기」를 켜면 내 최고 점수도 여기에 올라갑니다."
                            me == null -> "이 곡을 끝까지 부르면 내 점수가 올라갑니다."
                            else -> "내 순위 ${me}위 / ${rank.total}명"
                        },
                        Palette.sub, Type.bodyS, Modifier.padding(Dimen.l),
                    )
                }
                RankUi.NotConfigured -> Unit
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
    val rankUpload: Boolean,
    val nickname: String,
    val rankConfigured: Boolean,
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
    onRankUpload: (Boolean) -> Unit,
    onNickname: (String) -> Unit,
    onInvert: (Boolean) -> Unit,
    onClear: () -> Unit,
    onDeleteServerTakes: (() -> Unit)? = null,
    serverTakesStatus: String? = null,
) {
    var confirmClear by remember { mutableStateOf(false) }
    var confirmRank by remember { mutableStateOf(false) }
    var confirmServerDelete by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(Dimen.l)) {
        Section("노래방", AppIcons.Mic) {
            SwitchRow("혼자일 때 가수 목소리 줄이기", "끄면 평범한 음악 플레이어로만 씁니다", s.enabled, onEnabled)
            Divider()
            ChoiceRow("가이드 음량(가수 목소리 남기는 정도)", listOf(0, 10, 20, 30, 40), s.guidePercent, { "$it%" }, onGuide)
            Divider()
            ChoiceRow("대기 시간(혼자·문 닫힘이 이만큼 이어지면 열림)", listOf(3, 5, 8), s.dwellSeconds, { "${it}초" }, onDwell)
            Divider()
            ChoiceRow("창문 기준(이보다 많이 열리면 닫힘)", listOf(2, 10, 20), s.windowThresholdPct, { "$it%" }, onWindow)
            Divider()
            SwitchRow("안전 장치", "후진하거나 방향지시등을 켜면 가수 목소리를 잠깐 돌려놓습니다", s.safetyReturn, onSafety)
            Divider()
            SwitchRow("첫 소절 박자 세기", "노래방일 때 첫 소절 바로 앞에서 드럼 스틱으로 탁, 탁, 탁, 탁 박자를 세어 줍니다", s.countIn, onCountIn)
        }
        Section("점수와 전국 랭킹", AppIcons.Trophy) {
            SwitchRow("노래 점수(마이크)", "혼자만의 노래방일 때만 마이크로 음높이를 듣고 100점 만점으로 매깁니다. 「전국 랭킹에 올리기」를 켜지 않으면 소리는 저장하지 않습니다", s.scoring, onScoring)
            if (s.rankConfigured) {
                Divider()
                // 켤 때는 녹음 공개를 먼저 분명히 알리고 한 번 더 누르게 한다
                SwitchRow("전국 랭킹에 올리기", "별명, 곡별 최고 점수, 그리고 내가 고른 녹음을 올립니다. 녹음도 함께 공개됩니다",
                    s.rankUpload, { on -> if (on) confirmRank = true else onRankUpload(false) })
                Divider()
                NicknameRow(s.nickname, onNickname)
                if (onDeleteServerTakes != null) {
                    Divider()
                    Row(Modifier.fillMaxWidth().heightIn(min = 112.dp).padding(horizontal = Dimen.l, vertical = Dimen.s), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("서버에 올린 내 녹음", style = Type.bodyM.copy(fontWeight = FontWeight.SemiBold), color = Palette.text)
                            Text(serverTakesStatus ?: "올린 녹음은 누구나 들을 수 있습니다. 여기서 한 번에 지웁니다", style = Type.label, color = Palette.sub)
                        }
                        Spacer(Modifier.width(Dimen.m))
                        AppButton("서버의 내 녹음 모두 지우기", { confirmServerDelete = true }, icon = AppIcons.Trash, style = ButtonStyle.DANGER)
                    }
                }
            }
        }
        Section("차", AppIcons.Car) {
            SwitchRow("착석 값 뒤집기", "Pleos Connect Emulator 2.1.0은 착석 값이 반대로 옵니다. 실제 차에서 반대로 동작하면 끄세요", s.invertSeat, onInvert)
            if (s.passengerUnreadable) Body("이 차는 동승석 착석을 읽을 수 없어 이 기능이 열리지 않습니다", Palette.danger, Type.bodyS, Modifier.padding(start = Dimen.l, end = Dimen.l, bottom = Dimen.m))
            if (s.rearSeatsUnreadable) Body("이 차는 뒷좌석 착석을 읽을 수 없어 뒷좌석은 비어 있다고 봅니다", Palette.sub, Type.bodyS, Modifier.padding(start = Dimen.l, end = Dimen.l, bottom = Dimen.m))
        }
        Section("기록과 개인정보", AppIcons.Lock) {
            Row(Modifier.fillMaxWidth().padding(Dimen.l), verticalAlignment = Alignment.Top) {
                Icon(AppIcons.Info, null, tint = Palette.sub, modifier = Modifier.size(Dimen.icon))
                Spacer(Modifier.width(Dimen.m))
                Text(
                    (if (s.rankUpload) "전국 랭킹에는 별명, 곡별 최고 점수, 그리고 내가 「녹음 올리기」를 누른 녹음만 보냅니다. 올린 녹음은 서버(Firebase Firestore)에 " +
                        "공개로 저장되어 누구나 들을 수 있고, 「점수」 탭의 「지우기」나 위의 「서버의 내 녹음 모두 지우기」로 지웁니다. 녹음은 한 곡에 7분까지 담깁니다. " +
                        "그 밖의 기록은 이 차 안에만 저장됩니다. "
                    else "기록은 이 차 안에만 저장되고 밖으로 보내지 않습니다. ") +
                        (if (s.scoring) "마이크는 노래 점수를 켰을 때, 혼자만의 노래방에서만 씁니다." else "마이크를 쓰지 않습니다."),
                    style = Type.bodyS, color = Palette.sub, modifier = Modifier.weight(1f),
                )
            }
            Divider()
            Row(Modifier.fillMaxWidth().padding(Dimen.l), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("이 차의 기록", style = Type.bodyM.copy(fontWeight = FontWeight.SemiBold), color = Palette.text)
                    Text("내 기록, 점수, 차 안의 녹음을 지웁니다", style = Type.label, color = Palette.sub)
                }
                AppButton("기록 지우기", { confirmClear = true }, icon = AppIcons.Trash, style = ButtonStyle.DANGER)
            }
        }
        Spacer(Modifier.height(Dimen.s))
    }
    if (confirmRank && !s.rankUpload) {
        ConfirmDialog(
            AppIcons.Mic, Palette.accent, "녹음도 함께 공개됩니다",
            "켜면 혼자만의 노래방에서 부른 내 목소리를 녹음해 둡니다(반주는 녹음하지 않습니다). 최고 기록을 낸 판의 녹음은 「점수」 탭에서 " +
                "미리 들어 보고 「녹음 올리기」를 누르면 서버에 공개로 저장되어, 전국 랭킹에서 누구나 들을 수 있습니다. " +
                "올린 녹음은 「점수」 탭이나 이 설정에서 언제든 지울 수 있습니다.",
            "알겠습니다, 켜기", { onRankUpload(true); confirmRank = false }, { confirmRank = false },
        )
    }
    if (confirmClear) {
        ConfirmDialog(AppIcons.Trash, Palette.danger, "모든 기록을 지울까요?", "이 차에 저장된 내 기록, 점수, 올리지 않은 녹음을 지웁니다. 서버에 올린 것은 남습니다.",
            "지우기", { onClear(); confirmClear = false }, { confirmClear = false }, ButtonStyle.DANGER)
    }
    if (confirmServerDelete && onDeleteServerTakes != null) {
        ConfirmDialog(AppIcons.Trash, Palette.danger, "서버에 올린 내 녹음을 모두 지울까요?", "세 곡 모두에서 내 녹음을 지웁니다. 차 안의 녹음과 점수는 남습니다.",
            "지우기", { onDeleteServerTakes(); confirmServerDelete = false }, { confirmServerDelete = false }, ButtonStyle.DANGER)
    }
}

@Composable
private fun ChoiceRow(title: String, options: List<Int>, value: Int, label: (Int) -> String, onChange: (Int) -> Unit) {
    Column(Modifier.fillMaxWidth().padding(horizontal = Dimen.l, vertical = Dimen.m), verticalArrangement = Arrangement.spacedBy(Dimen.s)) {
        Text(title, style = Type.bodyM.copy(fontWeight = FontWeight.SemiBold), color = Palette.text)
        Segmented(options, value, label, onChange)
    }
}

@Composable
private fun NicknameRow(nickname: String, onSave: (String) -> Unit) {
    var editing by remember { mutableStateOf(false) }
    var text by remember(nickname) { mutableStateOf(nickname) }
    val focus = remember { androidx.compose.ui.focus.FocusRequester() }
    Row(Modifier.fillMaxWidth().heightIn(min = 112.dp).padding(horizontal = Dimen.l, vertical = Dimen.s), verticalAlignment = Alignment.CenterVertically) {
        Text("랭킹 별명", style = Type.bodyM.copy(fontWeight = FontWeight.SemiBold), color = Palette.text, modifier = Modifier.widthIn(min = 200.dp))
        if (!editing) {
            Text(nickname, style = Type.bodyL.copy(fontWeight = FontWeight.Bold), color = Palette.text,
                modifier = Modifier.weight(1f).pressable({ editing = true }, Color.Transparent, Palette.surfaceHi, RoundedCornerShape(12.dp)).padding(8.dp))
            AppButton("바꾸기", { editing = true }, icon = AppIcons.Edit, style = ButtonStyle.TONAL)
        } else {
            OutlinedTextField(
                value = text, onValueChange = { text = it.take(12) }, singleLine = true,
                textStyle = Type.bodyL.copy(color = Palette.text),
                colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = Palette.accent, unfocusedBorderColor = Palette.control, cursorColor = Palette.accent),
                modifier = Modifier.weight(1f).focusRequester(focus),
            )
            LaunchedEffect(Unit) { focus.requestFocus() }
            Spacer(Modifier.width(Dimen.s))
            AppButton("저장", { if (text.isNotBlank()) { onSave(text.trim()); editing = false } }, icon = AppIcons.Check, style = ButtonStyle.FILLED)
        }
    }
}
