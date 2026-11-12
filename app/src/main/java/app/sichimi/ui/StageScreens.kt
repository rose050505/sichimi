package app.sichimi.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameMillis
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.sichimi.core.MascotUi
import app.sichimi.core.StageUi
import app.sichimi.gate.BadgeId
import app.sichimi.gate.KaraokeWipe
import app.sichimi.gate.MascotMood
import app.sichimi.gate.MascotPose
import app.sichimi.gate.Tour
import app.sichimi.rank.EscapeEntry
import app.sichimi.songs.LyricLine
import kotlinx.coroutines.delay

// ---------- 재생 탭 무대(주차·혼자·노래방일 때만) ----------

/**
 * 노래방 무대. 곡 아트 위에 지금 가사 줄을 크게, 진행에 따라 글자 색이 왼쪽부터 채워진다(LRC 줄 시각으로 추정).
 * 다음 줄은 흐리게. 시치미는 왼쪽 아래. 채움과 시치미 흔들림은 그리기 단계에서만 바뀐다.
 */
@Composable
fun KaraokeStage(
    songId: String?,
    player: PlayerUi,
    lyrics: List<LyricLine>,
    credit: String,
    positionMs: () -> Long,
    mascot: MascotUi,
    mascotStage: Int,
    ghostName: String?,
    onPrev: () -> Unit, onPlayPause: () -> Unit, onNext: () -> Unit,
) {
    val frame = rememberFrameClock(true)
    // 가사 줄 번호는 200ms마다 읽은 위치로 정하고(줄이 바뀔 때만 다시 구성), 채움은 프레임마다 그리기에서 계산한다.
    var pos by remember { mutableLongStateOf(0L) }
    LaunchedEffect(player.songId, player.isPlaying) { while (true) { pos = positionMs(); delay(200) } }
    val starts = remember(lyrics) { lyrics.map { it.atMs } }
    val lengths = remember(lyrics) { lyrics.map { KaraokeWipe.letters(it.text) } }
    val idx by remember(lyrics) { derivedStateOf { KaraokeWipe.lineIndex(starts, pos) } }
    val sec by remember { derivedStateOf { pos / 1000 } }
    Box(Modifier.fillMaxSize().clip(RoundedCornerShape(Dimen.radiusCard)).background(Palette.stageBg)) {
        SongArt(songId, Modifier.fillMaxSize())
        Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color(0x66140C16), Color(0xCC140C16), Color(0xF0140C16)))))
        Column(Modifier.fillMaxSize().padding(Dimen.l)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                StatusLine("혼자만의 노래방", karaoke = true, playing = player.isPlaying)
                Spacer(Modifier.width(Dimen.m))
                Text(player.title, style = Type.bodyL.copy(fontWeight = FontWeight.SemiBold), color = Palette.text, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                Text(player.artist, style = Type.bodyM, color = Palette.sub, maxLines = 1)
            }
            if (ghostName != null) {
                Spacer(Modifier.height(Dimen.xs))
                Row(Modifier.clip(RoundedCornerShape(50)).background(Color(0x33FF5FA8)).padding(horizontal = 18.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(AppIcons.Headphones, null, tint = Palette.spot, modifier = Modifier.size(26.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("고스트 대결 중 · ${ghostName}님의 목소리가 아주 작게 깔립니다", style = Type.label, color = Palette.text)
                }
            }
            Row(Modifier.weight(1f).fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.width(440.dp).fillMaxHeight(), contentAlignment = Alignment.BottomStart) {
                    Mascot(mascotStage, if (mascot.pose == MascotPose.AWAY) MascotPose.SINGING else mascot.pose, mascot.look,
                        Modifier.size(260.dp), frameMs = frame,
                        hide = { if (mascot.pose == MascotPose.HIDING) ((android.os.SystemClock.elapsedRealtime() - mascot.since).toFloat() / MascotMood.HIDE_MS).coerceIn(0f, 1f) else 0f })
                    if (mascot.pose == MascotPose.RELIEVED) Bubble("휴, 안 들켰다", Modifier.padding(start = 170.dp, bottom = 236.dp))
                }
                Column(Modifier.weight(1f).padding(start = Dimen.m), verticalArrangement = Arrangement.Center) {
                    if (lyrics.isEmpty()) {
                        Text("가사가 없는 곡입니다", style = Type.titleL, color = Palette.sub)
                    } else {
                        val now = lyrics.getOrNull(idx)?.text?.ifBlank { "♪" } ?: "♪"
                        val next = lyrics.getOrNull(idx + 1)?.text ?: ""
                        WipeLine(now) {
                            if (idx < 0) 0f else {
                                val end = KaraokeWipe.lineEndMs(starts, lengths, idx)
                                val p = positionMs()
                                ((p - starts[idx]).toFloat() / (end - starts[idx]).coerceAtLeast(1)).coerceIn(0f, 1f).also { frame.value }
                            }
                        }
                        Spacer(Modifier.height(Dimen.m))
                        Text(next, style = Type.bodyL, color = Palette.sub, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
            if (credit.isNotEmpty()) Text(credit, style = Type.label, color = Palette.sub, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Spacer(Modifier.height(Dimen.xs))
            val dur = player.durationMs
            Box(Modifier.fillMaxWidth().height(8.dp).clip(RoundedCornerShape(4.dp)).background(Color(0x33FFFFFF))) {
                Box(Modifier.fillMaxWidth(if (dur > 0) (sec * 1000f / dur).coerceIn(0f, 1f) else 0f).fillMaxHeight().background(Palette.karaoke))
            }
            Spacer(Modifier.height(Dimen.s))
            Box(Modifier.widthIn(max = 760.dp)) { TransportRow(player.isPlaying, Dimen.button + 16.dp, onPrev, onPlayPause, onNext) }
        }
    }
}

/** 노래방 가사 한 줄: 흰 글자 위로 호박색이 왼쪽부터 채워진다. 채움은 그리기 단계에서만 바뀐다. */
@Composable
fun WipeLine(text: String, fraction: () -> Float) {
    val style = Type.headline.copy(fontSize = 56.sp)
    Box {
        Text(text, style = style, color = Palette.text, maxLines = 2, overflow = TextOverflow.Ellipsis)
        Text(text, style = style, color = Palette.karaoke, maxLines = 2, overflow = TextOverflow.Ellipsis,
            modifier = Modifier.clearAndSetSemantics { }.drawWithContent {
                val f = fraction()
                clipRect(right = size.width * f) { this@drawWithContent.drawContent() }
            })
    }
}

@Composable
private fun Bubble(text: String, modifier: Modifier) {
    Box(modifier.clip(RoundedCornerShape(24.dp)).background(Palette.text).padding(horizontal = 20.dp, vertical = 10.dp)) {
        Text(text, style = Type.bodyM.copy(fontWeight = FontWeight.Bold), color = Palette.mascotInk)
    }
}

// ---------- 주차 덮개 카드(점수 화면, 배지·미션·투어) ----------

sealed interface RevealCard {
    data class Score(
        val id: Long, val title: String, val score: Int, val finished: Boolean, val previousBest: Int?,
        val ghostName: String?, val ghostScore: Int?, val left: Int,
    ) : RevealCard
    data class Award(val id: Long, val kind: String, val title: String, val how: String, val points: Int, val tour: TourPoster?, val left: Int) : RevealCard
}

data class TourPoster(val week: String, val setlist: List<String>, val soloMs: Long, val bestScore: Int?, val fastestMs: Long?)

/** 화면 전체를 어둡게 덮고 가운데에 카드 한 장. 「확인」을 누르면 다음 카드. 주차·혼자일 때만 부른다. */
@Composable
fun RevealOverlay(card: RevealCard, stage: Int, look: app.sichimi.gate.MascotLook, onDone: () -> Unit) {
    Box(
        Modifier.fillMaxSize().background(Color(0xE6100A12))
            .clickable(remember { MutableInteractionSource() }, null) { },
        contentAlignment = Alignment.Center,
    ) {
        Column(
            Modifier.widthIn(max = 1180.dp).fillMaxWidth(0.82f).clip(RoundedCornerShape(36.dp)).background(Palette.stageSurface)
                .border(2.dp, Color(0x55FF5FA8), RoundedCornerShape(36.dp)).padding(Dimen.xl),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(Dimen.m),
        ) {
            when (card) {
                is RevealCard.Score -> ScoreReveal(card, stage, look)
                is RevealCard.Award -> AwardReveal(card, stage, look)
            }
            val left = when (card) { is RevealCard.Score -> card.left; is RevealCard.Award -> card.left }
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(if (left > 0) "다음 카드 ${left}장" else "", style = Type.label, color = Palette.sub, modifier = Modifier.weight(1f))
                AppButton("확인", onDone, icon = AppIcons.Check, style = ButtonStyle.FILLED, modifier = Modifier.widthIn(min = 240.dp))
            }
        }
    }
}

@Composable
private fun ScoreReveal(c: RevealCard.Score, stage: Int, look: app.sichimi.gate.MascotLook) {
    // 숫자가 0에서 점수까지 1.2초 동안 올라간다(주차 화면에서만). 접근성에는 최종 점수만 알린다.
    var shown by remember(c.id) { mutableIntStateOf(0) }
    LaunchedEffect(c.id) {
        val start = withFrameMillis { it }
        while (shown < c.score) withFrameMillis { t -> shown = (c.score * ((t - start) / 1200f).coerceIn(0f, 1f)).toInt() }
    }
    val frame = rememberFrameClock(true)
    Text("오늘의 무대 · ${c.title}", style = Type.titleS, color = Palette.sub, maxLines = 1, overflow = TextOverflow.Ellipsis)
    Row(verticalAlignment = Alignment.CenterVertically) {
        Mascot(stage, if (c.previousBest == null || c.score > c.previousBest) MascotPose.RELIEVED else MascotPose.SINGING, look,
            Modifier.size(220.dp), frameMs = frame)
        Spacer(Modifier.width(Dimen.l))
        Box(Modifier.semantics { contentDescription = "${c.score}점" }) {
            Text("$shown", style = Type.display.copy(fontSize = 160.sp, lineHeight = 170.sp), color = Palette.karaoke, modifier = Modifier.clearAndSetSemantics { })
        }
        Text("점", style = Type.titleL, color = Palette.karaoke, modifier = Modifier.padding(top = 60.dp))
    }
    val line = when {
        !c.finished -> "곡의 절반 넘게 불러야 최고 기록에 들어갑니다"
        c.previousBest == null -> "첫 기록! 다음엔 이 점수를 넘어 보세요"
        c.score > c.previousBest -> "최고 기록! 지난 최고 ${c.previousBest}점보다 ${c.score - c.previousBest}점 높아요"
        c.score == c.previousBest -> "지난 최고 ${c.previousBest}점과 같아요"
        else -> "지난 최고 ${c.previousBest}점까지 ${c.previousBest - c.score}점"
    }
    Text(line, style = Type.titleS, color = Palette.text, textAlign = TextAlign.Center)
    if (c.ghostName != null && c.ghostScore != null) {
        val win = c.score > c.ghostScore
        Column(Modifier.fillMaxWidth().card(Palette.stageBg).padding(Dimen.l), verticalArrangement = Arrangement.spacedBy(Dimen.s)) {
            Text(if (win) "고스트 대결 승리!" else "고스트 대결 · 아쉽게 졌어요", style = Type.bodyL.copy(fontWeight = FontWeight.Bold), color = if (win) Palette.karaoke else Palette.text)
            DuelBar("나", c.score, Palette.karaoke)
            DuelBar("${c.ghostName}님(고스트)", c.ghostScore, Palette.spot)
        }
    }
}

@Composable
private fun DuelBar(name: String, score: Int, color: Color) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(name, style = Type.bodyM, color = Palette.text, modifier = Modifier.width(300.dp), maxLines = 1, overflow = TextOverflow.Ellipsis)
        Box(Modifier.weight(1f).height(20.dp).clip(RoundedCornerShape(10.dp)).background(Color(0x22FFFFFF))) {
            Box(Modifier.fillMaxWidth(score / 100f).fillMaxHeight().clip(RoundedCornerShape(10.dp)).background(color))
        }
        Spacer(Modifier.width(Dimen.m))
        Text("${score}점", style = Type.titleS, color = Palette.text, modifier = Modifier.width(120.dp), textAlign = TextAlign.End)
    }
}

@Composable
private fun AwardReveal(c: RevealCard.Award, stage: Int, look: app.sichimi.gate.MascotLook) {
    val frame = rememberFrameClock(true)
    val head = when (c.kind) { "BADGE" -> "새 칭호를 얻었어요"; "MISSION" -> "이번 주 미션 완료"; else -> "주간 투어 완주!" }
    Text(head, style = Type.titleS, color = Palette.sub)
    Row(verticalAlignment = Alignment.CenterVertically) {
        Mascot(stage, MascotPose.SINGING, look, Modifier.size(200.dp), frameMs = frame)
        Spacer(Modifier.width(Dimen.l))
        Column(verticalArrangement = Arrangement.spacedBy(Dimen.xs)) {
            Text(c.title, style = Type.headline, color = Palette.karaoke)
            Text(c.how, style = Type.bodyL, color = Palette.text)
            if (c.points > 0) Text("시치미 성장 +${c.points}점", style = Type.bodyM.copy(fontWeight = FontWeight.SemiBold), color = Palette.spot)
        }
    }
    c.tour?.let { t ->
        Column(Modifier.fillMaxWidth().card(Palette.stageBg).padding(Dimen.l), verticalArrangement = Arrangement.spacedBy(Dimen.xs)) {
            Text("${t.week} 출근길 투어 포스터", style = Type.bodyL.copy(fontWeight = FontWeight.Bold), color = Palette.text)
            Text("셋리스트 · " + t.setlist.joinToString(" / ").ifEmpty { "—" }, style = Type.bodyM, color = Palette.text, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text("혼자 부른 시간 ${minutesText(t.soloMs)} · 최고 점수 ${t.bestScore?.let { "${it}점" } ?: "—"} · 가장 빠른 대피 ${t.fastestMs?.let { secondsText(it) } ?: "—"}",
                style = Type.bodyM, color = Palette.sub)
        }
    }
}

// ---------- 오늘 요약의 들킬 뻔 리플레이 카드 ----------

data class ReplayUi(val cause: String, val reactionMs: Long, val lyric: String?, val songTitle: String?)

fun causeText(cause: String) = when (cause) { "DOOR" -> "문이 열림"; "SEAT" -> "누가 앉음"; "WINDOW" -> "창문이 열림"; else -> cause }

@Composable
fun ReplayCard(r: ReplayUi, stage: Int, modifier: Modifier = Modifier) {
    Row(modifier.card(Palette.stageSurface).padding(Dimen.m), verticalAlignment = Alignment.CenterVertically) {
        Mascot(stage, MascotPose.HIDING, app.sichimi.gate.MascotLook(), Modifier.size(140.dp), hide = { 1f })
        Spacer(Modifier.width(Dimen.m))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("들킬 뻔 리플레이 · ${causeText(r.cause)}", style = Type.bodyM.copy(fontWeight = FontWeight.Bold), color = Palette.text)
            Text("대피 ${secondsText(r.reactionMs)}", style = Type.titleL, color = Palette.karaoke)
            Text((r.songTitle?.let { "$it · " } ?: "") + (r.lyric?.let { "「$it」 부르던 중" } ?: "부르던 중"), style = Type.bodyS, color = Palette.sub, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
    }
}

// ---------- 내 기록 탭 위쪽(시치미, 투어, 미션, 배지) ----------

@Composable
fun StageTop(s: StageUi, mascot: MascotUi, frame: androidx.compose.runtime.State<Long>?) {
    Column(verticalArrangement = Arrangement.spacedBy(Dimen.m)) {
        Row(Modifier.fillMaxWidth().card(Palette.stageSurface).padding(Dimen.l), verticalAlignment = Alignment.CenterVertically) {
            Mascot(s.stage, MascotPose.SINGING, mascot.look, Modifier.size(220.dp), frameMs = frame)
            Spacer(Modifier.width(Dimen.l))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(Dimen.xs)) {
                Text("시치미 ${s.stage}단계 · ${s.stageName}", style = Type.titleM, color = Palette.text)
                Text(s.title?.let { "칭호 「$it」" } ?: "아직 칭호가 없어요", style = Type.bodyM, color = Palette.karaoke)
                Spacer(Modifier.height(Dimen.xs))
                Box(Modifier.fillMaxWidth().height(16.dp).clip(RoundedCornerShape(8.dp)).background(Color(0x33FFFFFF))) {
                    Box(Modifier.fillMaxWidth(s.progress).fillMaxHeight().clip(RoundedCornerShape(8.dp)).background(Brush.horizontalGradient(listOf(Palette.karaoke, Palette.spot))))
                }
                Text("성장 ${s.points}점 · ${s.nextText}", style = Type.bodyS, color = Palette.sub)
                Text("혼자 부른 1분 1점, 완창 5점, 0.5초 안에 숨기 3점(0.3초 5점), 미션 15점. 들킨 횟수로는 깎이지 않아요", style = Type.label, color = Palette.sub)
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(Dimen.m)) {
            Column(Modifier.weight(1f).card(Palette.stageSurface).padding(Dimen.l), verticalArrangement = Arrangement.spacedBy(Dimen.s)) {
                Text("이번 주 출근길 투어", style = Type.bodyL.copy(fontWeight = FontWeight.Bold), color = Palette.text)
                Row(horizontalArrangement = Arrangement.spacedBy(Dimen.s)) {
                    repeat(Tour.GOAL) { i ->
                        val on = i < s.week.shows
                        Box(Modifier.size(56.dp).clip(CircleShape).background(if (on) Palette.karaoke else Color(0x22FFFFFF)), contentAlignment = Alignment.Center) {
                            Text("${i + 1}", style = Type.bodyM.copy(fontWeight = FontWeight.Bold), color = if (on) Palette.onKaraoke else Palette.sub)
                        }
                    }
                }
                Text(if (s.week.shows >= Tour.GOAL) "투어 완주!" else "공연 ${s.week.shows} / ${Tour.GOAL}회 · 혼자 한 곡 이상 부른 주행이 공연 1회", style = Type.label, color = Palette.sub)
            }
            Column(Modifier.weight(1.3f).card(Palette.stageSurface).padding(Dimen.l), verticalArrangement = Arrangement.spacedBy(Dimen.s)) {
                Text("이번 주 미션", style = Type.bodyL.copy(fontWeight = FontWeight.Bold), color = Palette.text)
                s.missions.forEach { m ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(if (m.done) AppIcons.Check else AppIcons.Trophy, null, tint = if (m.done) Palette.karaoke else Palette.sub, modifier = Modifier.size(30.dp))
                        Spacer(Modifier.width(Dimen.s))
                        Text(m.title, style = Type.bodyM.copy(fontWeight = FontWeight.SemiBold), color = Palette.text, modifier = Modifier.weight(1f), maxLines = 1)
                        Text(if (m.done) "완료 +15" else m.progress, style = Type.label, color = if (m.done) Palette.karaoke else Palette.sub)
                    }
                }
            }
        }
        Column(Modifier.fillMaxWidth().card(Palette.stageSurface).padding(Dimen.l), verticalArrangement = Arrangement.spacedBy(Dimen.s)) {
            Text("칭호와 배지 ${s.badges.size} / ${BadgeId.entries.size}", style = Type.bodyL.copy(fontWeight = FontWeight.Bold), color = Palette.text)
            BadgeId.entries.chunked(4).forEach { row ->
                Row(horizontalArrangement = Arrangement.spacedBy(Dimen.s)) {
                    row.forEach { b ->
                        val on = b in s.badges
                        Column(Modifier.weight(1f).heightIn(min = 104.dp).clip(RoundedCornerShape(20.dp))
                            .background(if (on) Color(0x33FFB547) else Color(0x14FFFFFF)).padding(16.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(if (on) AppIcons.Trophy else AppIcons.Lock, null, tint = if (on) Palette.karaoke else Palette.sub, modifier = Modifier.size(28.dp))
                                Spacer(Modifier.width(8.dp))
                                Text(b.title, style = Type.bodyS.copy(fontWeight = FontWeight.Bold), color = if (on) Palette.text else Palette.sub, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                            Text(b.how, style = Type.label, color = Palette.sub, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        }
                    }
                }
            }
        }
    }
}

// ---------- 점수 탭: 고스트와 대피 랭킹 ----------

data class GhostBannerUi(val songId: String, val songTitle: String, val nickname: String, val score: Int)

@Composable
fun GhostBanner(g: GhostBannerUi, onCancel: () -> Unit) {
    Row(Modifier.fillMaxWidth().card(Palette.stageSurface).border(2.dp, Color(0x55FF5FA8), RoundedCornerShape(Dimen.radiusCard)).padding(Dimen.l),
        verticalAlignment = Alignment.CenterVertically) {
        Icon(AppIcons.Headphones, null, tint = Palette.spot, modifier = Modifier.size(Dimen.icon))
        Spacer(Modifier.width(Dimen.m))
        Column(Modifier.weight(1f)) {
            Text("고스트 대결 준비됨 · ${g.nickname}님 ${g.score}점", style = Type.bodyL.copy(fontWeight = FontWeight.Bold), color = Palette.text)
            Text("다음에 「${g.songTitle}」을 혼자 부르면 ${g.nickname}님의 목소리가 아주 작게 깔립니다", style = Type.bodyS, color = Palette.sub)
        }
        AppButton("취소", onCancel, icon = AppIcons.Minus, style = ButtonStyle.TONAL)
    }
}

@Composable
fun EscapeRows(rows: List<EscapeEntry>, myRank: Int?, total: Int, uploading: Boolean) {
    if (rows.isEmpty()) Body("이번 주 이 곡의 대피 기록이 아직 없습니다. 노래방 중에 문이 열리면 가수가 돌아오기까지의 시간이 기록됩니다.", Palette.dim, Type.bodyM, Modifier.padding(Dimen.l))
    rows.forEachIndexed { i, e ->
        if (i > 0) Divider(start = 120.dp)
        Row(Modifier.fillMaxWidth().heightIn(min = 96.dp).background(if (e.mine) Palette.surfaceHi else Palette.surface).padding(horizontal = Dimen.l),
            verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(64.dp).clip(CircleShape).background(if (e.rank == 1) Palette.karaoke else Palette.control), contentAlignment = Alignment.Center) {
                Text("${e.rank}위", style = Type.label.copy(fontWeight = FontWeight.Bold), color = if (e.rank == 1) Palette.onKaraoke else Palette.text)
            }
            Spacer(Modifier.width(Dimen.m))
            Column(Modifier.weight(1f)) {
                Text(e.nickname + if (e.mine) " (나)" else "", style = Type.bodyM.copy(fontWeight = FontWeight.SemiBold), color = Palette.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(causeText(e.cause), style = Type.label, color = Palette.sub)
            }
            Text(secondsText(e.ms), style = Type.titleS, color = Palette.text)
        }
    }
    Divider(start = 0.dp)
    Body(when {
        !uploading -> "설정에서 「전국 랭킹에 올리기」를 켜면 내 가장 빠른 대피도 올라갑니다(별명만 공개)."
        myRank == null -> "들킬 뻔한 순간에 빨리 숨으면 여기에 올라갑니다."
        else -> "이번 주 내 순위 ${myRank}위 / ${total}명"
    }, Palette.sub, Type.bodyS, Modifier.padding(Dimen.l))
}
