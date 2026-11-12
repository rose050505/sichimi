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
import androidx.compose.foundation.layout.offset
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
import androidx.compose.foundation.Canvas
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.text.style.TextAlign as TA
import app.sichimi.gate.Growth

// ---------- 재생 탭 무대(주차·혼자·노래방일 때만) ----------

/**
 * 노래방 무대(v3 한밤 무대). 곡 아트가 무대 배경 전체에 깔리고, 위에서 조명이 내려온다. 지금 가사 줄은 화면 가운데에 크게,
 * 진행에 따라 글자 색이 왼쪽부터 분홍으로 채워진다(LRC 줄 시각으로 추정). 다음 줄은 흐리게. 시치미는 왼쪽 아래 무대 바닥 위.
 * 조작은 아래 가운데 하나로 이어진 조작부, 곡 출처는 오른쪽 위 「곡 정보」 아이콘 뒤. 채움과 흔들림은 그리기 단계에서만 바뀐다.
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
    var pos by remember { mutableLongStateOf(0L) }
    LaunchedEffect(player.songId, player.isPlaying) { while (true) { pos = positionMs(); delay(200) } }
    val starts = remember(lyrics) { lyrics.map { it.atMs } }
    val lengths = remember(lyrics) { lyrics.map { KaraokeWipe.letters(it.text) } }
    val idx by remember(lyrics) { derivedStateOf { KaraokeWipe.lineIndex(starts, pos) } }
    var showInfo by remember { mutableStateOf(false) }
    Box(Modifier.fillMaxSize().clip(RoundedCornerShape(36.dp)).background(Night.bg)) {
        SongArt(songId, Modifier.fillMaxSize())
        // 가사 뒤만 살짝 어둡게(가장자리의 달·능선은 그대로 보이게)
        Box(Modifier.fillMaxSize().background(Brush.radialGradient(listOf(Color(0xB3100B1C), Color(0x40100B1C), Color(0x00100B1C)), radius = 1000f)))
        // 위에서 내려오는 무대 조명(정지)
        Canvas(Modifier.fillMaxSize()) {
            val w = size.width; val h = size.height
            val p = Path().apply { moveTo(w * 0.44f, 0f); lineTo(w * 0.56f, 0f); lineTo(w * 0.78f, h * 0.72f); lineTo(w * 0.22f, h * 0.72f); close() }
            drawPath(p, Brush.verticalGradient(listOf(Color(0x30FF5FA8), Color(0x00FF5FA8)), endY = h * 0.72f))
            drawRect(Brush.verticalGradient(listOf(Color(0x00100B1C), Color(0xB3100B1C)), startY = h * 0.70f, endY = h))
        }
        // 위쪽: 상태 칩, 곡 제목·가수, 곡 정보
        Row(Modifier.fillMaxWidth().padding(start = Dimen.l, end = Dimen.m, top = Dimen.m), verticalAlignment = Alignment.CenterVertically) {
            LiveChip("혼자만의 노래방")
            Spacer(Modifier.width(Dimen.m))
            Text(player.title, style = Type.bodyM.copy(fontWeight = FontWeight.Bold), color = Night.text, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
            Spacer(Modifier.width(Dimen.s))
            Text(player.artist, style = Type.bodyS, color = Night.sub, maxLines = 1)
            Spacer(Modifier.weight(1f))
            if (credit.isNotEmpty()) NightIconButton(AppIcons.Info, "곡 정보", { showInfo = true }, glass = true)
        }
        if (ghostName != null) {
            Row(Modifier.padding(start = Dimen.l, top = 116.dp).clip(RoundedCornerShape(50)).background(Night.glass).border(1.dp, Night.line, RoundedCornerShape(50))
                .padding(horizontal = 20.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(AppIcons.Ghost, null, tint = Night.pink, modifier = Modifier.size(26.dp))
                Spacer(Modifier.width(10.dp))
                Text("고스트 대결 중 · ${ghostName}님의 목소리가 아주 작게 깔립니다", style = Type.label, color = Night.text)
            }
        }
        // 가운데: 가사
        Column(Modifier.align(Alignment.Center).padding(horizontal = 120.dp).padding(bottom = 80.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            if (lyrics.isEmpty()) {
                Text("가사가 없는 곡입니다", style = Type.titleL, color = Night.sub)
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
                Text(next, style = Type.bodyL.copy(shadow = LyricShadow), color = Night.sub, maxLines = 2, overflow = TextOverflow.Ellipsis, textAlign = TA.Center)
            }
        }
        // 왼쪽 아래: 무대 바닥 선과 시치미
        Box(Modifier.align(Alignment.BottomStart).padding(start = Dimen.m, bottom = 146.dp).width(720.dp).height(2.dp)
            .background(Brush.horizontalGradient(listOf(Color(0x00FFC65C), Color(0x80FFC65C), Color(0x00FFC65C)))))
        Box(Modifier.align(Alignment.BottomStart).padding(start = Dimen.l, bottom = 146.dp).size(width = 440.dp, height = 300.dp), contentAlignment = Alignment.BottomStart) {
            Mascot(mascotStage, if (mascot.pose == MascotPose.AWAY) MascotPose.SINGING else mascot.pose, mascot.look,
                Modifier.size(240.dp), frameMs = frame,
                hide = { if (mascot.pose == MascotPose.HIDING) ((android.os.SystemClock.elapsedRealtime() - mascot.since).toFloat() / MascotMood.HIDE_MS).coerceIn(0f, 1f) else 0f })
            if (mascot.pose == MascotPose.RELIEVED) Bubble("휴, 안 들켰다", Modifier.padding(start = 170.dp, bottom = 220.dp))
        }
        // 아래 가운데: 하나로 이어진 조작부
        ControlDock(player.isPlaying, player.durationMs, positionMs, onPrev, onPlayPause, onNext,
            Modifier.align(Alignment.BottomCenter).padding(bottom = Dimen.m).widthIn(max = 1080.dp).fillMaxWidth(0.72f))
    }
    if (showInfo) NightInfoDialog(AppIcons.Info, "곡 정보", "${player.title} · ${player.artist}\n$credit", { showInfo = false })
}

private val LyricShadow = Shadow(Color(0x99000000), Offset(0f, 4f), 24f)

/**
 * 노래방 가사 한 줄: 흰 글자 위로 분홍이 왼쪽부터 채워진다. 줄이 길어 두 줄로 넘어가면 윗줄을 다 채운 뒤 아랫줄을 채운다
 * (글자 폭 합으로 나눠 차례로). 채움은 그리기 단계에서만 바뀐다.
 */
@Composable
fun WipeLine(text: String, fraction: () -> Float) {
    val style = Type.display.copy(shadow = LyricShadow, textAlign = TA.Center)
    var layout by remember(text) { mutableStateOf<androidx.compose.ui.text.TextLayoutResult?>(null) }
    Box {
        Text(text, style = style, color = Night.text, maxLines = 2, overflow = TextOverflow.Ellipsis, onTextLayout = { layout = it })
        Text(text, style = style.copy(shadow = null), color = Night.pink, maxLines = 2, overflow = TextOverflow.Ellipsis,
            modifier = Modifier.clearAndSetSemantics { }.drawWithContent {
                val l = layout
                val f = fraction()
                if (l == null || l.lineCount <= 1) {
                    clipRect(right = size.width * f) { this@drawWithContent.drawContent() }
                } else {
                    val widths = (0 until l.lineCount).map { (l.getLineRight(it) - l.getLineLeft(it)).coerceAtLeast(0f) }
                    var left = widths.sum() * f
                    for (i in 0 until l.lineCount) {
                        val take = left.coerceIn(0f, widths[i])
                        left -= take
                        if (take <= 0f) break
                        val x0 = l.getLineLeft(i)
                        clipRect(left = x0, top = l.getLineTop(i), right = x0 + take, bottom = l.getLineBottom(i)) { this@drawWithContent.drawContent() }
                    }
                }
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
        Modifier.fillMaxSize().background(Color(0xEB0A0614))
            .clickable(remember { MutableInteractionSource() }, null) { },
        contentAlignment = Alignment.Center,
    ) {
        Column(
            Modifier.widthIn(max = 1180.dp).fillMaxWidth(0.82f).nightHero().border(2.dp, Color(0x66FF5FA8), RoundedCornerShape(36.dp)).padding(Dimen.xl),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(Dimen.m),
        ) {
            when (card) {
                is RevealCard.Score -> ScoreReveal(card, stage, look)
                is RevealCard.Award -> AwardReveal(card, stage, look)
            }
            val left = when (card) { is RevealCard.Score -> card.left; is RevealCard.Award -> card.left }
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(if (left > 0) "다음 카드 ${left}장" else "", style = Type.label, color = Night.sub, modifier = Modifier.weight(1f))
                NightButton("확인", onDone, icon = AppIcons.Check, style = NightStyle.PRIMARY, modifier = Modifier.widthIn(min = 240.dp))
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
    val best = c.finished && (c.previousBest == null || c.score > c.previousBest)
    Text("오늘의 무대 · ${c.title}", style = Type.titleS, color = Night.sub, maxLines = 1, overflow = TextOverflow.Ellipsis)
    Row(verticalAlignment = Alignment.CenterVertically) {
        Mascot(stage, if (c.previousBest == null || c.score > c.previousBest) MascotPose.RELIEVED else MascotPose.SINGING, look,
            Modifier.size(220.dp), frameMs = frame)
        Spacer(Modifier.width(Dimen.l))
        Box(Modifier.semantics { contentDescription = "${c.score}점" }) {
            Text("$shown", style = Type.display.copy(fontSize = 160.sp, lineHeight = 170.sp), color = Night.gold, modifier = Modifier.clearAndSetSemantics { })
        }
        Text("점", style = Type.titleL, color = Night.gold, modifier = Modifier.padding(top = 60.dp))
    }
    if (best) TagChip(if (c.previousBest == null) "첫 기록" else "새 최고 기록", Night.onGold, Night.gold, AppIcons.Star)
    val line = when {
        !c.finished -> "곡의 절반 넘게 불러야 최고 기록에 들어갑니다"
        c.previousBest == null -> "다음엔 이 점수를 넘어 보세요"
        c.score > c.previousBest -> "지난 최고 ${c.previousBest}점보다 ${c.score - c.previousBest}점 높아요"
        c.score == c.previousBest -> "지난 최고 ${c.previousBest}점과 같아요"
        else -> "지난 최고 ${c.previousBest}점까지 ${c.previousBest - c.score}점"
    }
    Text(line, style = Type.titleS, color = Night.text, textAlign = TextAlign.Center)
    if (c.ghostName != null && c.ghostScore != null) {
        val win = c.score > c.ghostScore
        Column(Modifier.fillMaxWidth().nightCard(Night.bg).padding(Dimen.l), verticalArrangement = Arrangement.spacedBy(Dimen.s)) {
            Text(if (win) "고스트 대결 승리!" else "고스트 대결 · 아쉽게 졌어요", style = Type.bodyL.copy(fontWeight = FontWeight.Bold), color = if (win) Night.gold else Night.text)
            DuelBar("나", c.score, Night.gold)
            DuelBar("${c.ghostName}님(고스트)", c.ghostScore, Night.pink)
        }
    }
}

@Composable
private fun DuelBar(name: String, score: Int, color: Color) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(name, style = Type.bodyM, color = Night.text, modifier = Modifier.width(300.dp), maxLines = 1, overflow = TextOverflow.Ellipsis)
        NightBar(score / 100f, Modifier.weight(1f), 20.dp, androidx.compose.ui.graphics.SolidColor(color))
        Spacer(Modifier.width(Dimen.m))
        Text("${score}점", style = Type.titleS, color = Night.text, modifier = Modifier.width(120.dp), textAlign = TextAlign.End)
    }
}

@Composable
private fun AwardReveal(c: RevealCard.Award, stage: Int, look: app.sichimi.gate.MascotLook) {
    val frame = rememberFrameClock(true)
    val head = when (c.kind) { "BADGE" -> "새 칭호를 얻었어요"; "MISSION" -> "이번 주 미션 완료"; else -> "주간 투어 완주!" }
    Text(head, style = Type.titleS, color = Night.sub)
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(contentAlignment = Alignment.Center) {
            if (c.kind == "BADGE") BadgeMedal(true, badgeIcon(c.title), 200.dp)
            else Mascot(stage, MascotPose.SINGING, look, Modifier.size(200.dp), frameMs = frame)
        }
        Spacer(Modifier.width(Dimen.l))
        Column(verticalArrangement = Arrangement.spacedBy(Dimen.xs)) {
            Text(c.title, style = Type.headline, color = Night.gold)
            Text(c.how, style = Type.bodyL, color = Night.text)
            if (c.points > 0) TagChip("시치미 성장 +${c.points}점", Night.pink, Night.pinkTint, AppIcons.Plus)
        }
    }
    c.tour?.let { t -> TourPosterCard(t) }
}

/** 주간 투어 포스터: 공연 포스터처럼 셋리스트와 숫자 셋. */
@Composable
private fun TourPosterCard(t: TourPoster) {
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(Dimen.radiusCard))
        .background(Brush.verticalGradient(listOf(Color(0xFF2E1640), Night.bg))).border(1.dp, Night.heroLine, RoundedCornerShape(Dimen.radiusCard))
        .padding(Dimen.l), verticalArrangement = Arrangement.spacedBy(Dimen.s)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            SichimiLogo(32.dp)
            Spacer(Modifier.width(Dimen.m))
            Text("${t.week} 출근길 투어 포스터", style = Type.bodyL.copy(fontWeight = FontWeight.Bold), color = Night.text)
        }
        Text("셋리스트 · " + t.setlist.joinToString(" / ").ifEmpty { "—" }, style = Type.bodyM, color = Night.sub, maxLines = 2, overflow = TextOverflow.Ellipsis)
        Row(horizontalArrangement = Arrangement.spacedBy(Dimen.l)) {
            PosterStat("혼자 부른 시간", minutesText(t.soloMs))
            PosterStat("최고 점수", t.bestScore?.let { "${it}점" } ?: "—")
            PosterStat("가장 빠른 대피", t.fastestMs?.let { secondsText(it) } ?: "—")
        }
    }
}

@Composable
private fun PosterStat(label: String, value: String) {
    Column {
        Text(label, style = Type.label, color = Night.sub)
        Text(value, style = Type.titleM, color = Night.gold)
    }
}

// ---------- 오늘 요약의 들킬 뻔 리플레이 카드 ----------

data class ReplayUi(val cause: String, val reactionMs: Long, val lyric: String?, val songTitle: String?)

fun causeText(cause: String) = when (cause) { "DOOR" -> "문이 열림"; "SEAT" -> "누가 앉음"; "WINDOW" -> "창문이 열림"; else -> cause }

@Composable
fun ReplayCard(r: ReplayUi, stage: Int, modifier: Modifier = Modifier) {
    Row(modifier.fillMaxWidth().nightCard().padding(horizontal = Dimen.l, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        // 숨는 중 끝 모습(안테나와 두 눈)은 그림 칸 아래쪽에만 그려지므로, 위를 잘라 낮은 띠로 둔다
        Box(Modifier.size(width = 140.dp, height = 88.dp).clip(RoundedCornerShape(0.dp)), contentAlignment = Alignment.BottomCenter) {
            Mascot(stage, MascotPose.HIDING, app.sichimi.gate.MascotLook(), Modifier.size(140.dp), hide = { 1f })
        }
        Spacer(Modifier.width(Dimen.m))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("들킬 뻔 리플레이 · ${causeText(r.cause)}", style = Type.bodyM.copy(fontWeight = FontWeight.Bold), color = Night.text)
            Text((r.songTitle?.let { "$it · " } ?: "") + (r.lyric?.let { "「$it」 부르던 중" } ?: "부르던 중"), style = Type.bodyS, color = Night.sub, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
        Spacer(Modifier.width(Dimen.m))
        Column(horizontalAlignment = Alignment.End) {
            Text("대피", style = Type.label, color = Night.sub)
            Text(secondsText(r.reactionMs), style = Type.titleL, color = Night.gold)
        }
    }
}

// ---------- 내 기록 탭 위쪽(시치미, 투어, 미션, 배지) ----------

fun badgeIcon(b: BadgeId): ImageVector = when (b) {
    BadgeId.FIRST_STAGE -> AppIcons.Star
    BadgeId.ESCAPE_MASTER -> AppIcons.Bolt
    BadgeId.TRIPLE_SICHIMI -> AppIcons.Eye
    BadgeId.CLEAN_WEEK -> AppIcons.Check
    BadgeId.RAIN_SINGER -> AppIcons.Drop
    BadgeId.NIGHT_SINGER -> AppIcons.Moon
    BadgeId.COMMUTE_10 -> AppIcons.Note
    BadgeId.GHOST_BUSTER -> AppIcons.Ghost
}

private fun badgeIcon(title: String): ImageVector = BadgeId.entries.firstOrNull { it.title == title }?.let { badgeIcon(it) } ?: AppIcons.Trophy

private const val GROWTH_HELP = "혼자 부른 1분 = 1점, 완창 1곡 = 5점, 들킬 뻔한 순간 0.5초 안에 숨기 = 3점(0.3초 안이면 5점), 이번 주 미션 하나 = 15점.\n" +
    "단계: 1단계 흥얼 씨앗 0점 → 2단계 콧노래 20점 → 3단계 골목 가수 60점 → 4단계 무대의 주인공 150점.\n들킨 횟수로는 깎이지 않아요."

@Composable
fun StageTop(s: StageUi, mascot: MascotUi, frame: androidx.compose.runtime.State<Long>?) {
    var help by remember { mutableStateOf(false) }
    var badgeInfo by remember { mutableStateOf<BadgeId?>(null) }
    Column(verticalArrangement = Arrangement.spacedBy(Dimen.m)) {
        // 대표 카드: 지금 단계와 다음 단계까지 남은 양
        Box(Modifier.fillMaxWidth().nightHero()) {
            Row(Modifier.fillMaxWidth().padding(horizontal = Dimen.xl, vertical = Dimen.l), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(width = 280.dp, height = 260.dp)
                    .background(Brush.radialGradient(listOf(Color(0x38FF5FA8), Color(0x00FF5FA8)))), contentAlignment = Alignment.BottomCenter) {
                    Mascot(s.stage, MascotPose.SINGING, mascot.look, Modifier.size(250.dp), frameMs = frame)
                }
                Spacer(Modifier.width(Dimen.xl))
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(Dimen.xs)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("시치미 ${s.stage}단계 · ${s.stageName}", style = Type.titleM, color = Night.text)
                        Spacer(Modifier.width(Dimen.m))
                        s.title?.let { TagChip("칭호 「$it」", Night.gold, Night.goldTint) }
                    }
                    val left = Growth.toNext(s.points)
                    if (left == null) {
                        Text("무대의 주인공이 되었어요", style = Type.display.copy(fontSize = 64.sp), color = Night.gold)
                    } else {
                        Row(verticalAlignment = Alignment.Bottom) {
                            Text("다음 단계 「${Growth.STAGE_NAMES[s.stage]}」까지", style = Type.bodyS, color = Night.sub, modifier = Modifier.padding(bottom = 14.dp))
                            Spacer(Modifier.width(Dimen.s))
                            Text("${left}점", style = Type.display.copy(fontSize = 88.sp, lineHeight = 96.sp), color = Night.gold)
                        }
                        val songs = (left + Growth.FULL_SONG_POINTS - 1) / Growth.FULL_SONG_POINTS
                        Text("혼자 ${left}분 부르거나 완창 ${songs}곡이면 돼요", style = Type.bodyS, color = Night.sub)
                    }
                    Spacer(Modifier.height(Dimen.s))
                    StageTrack(s.points, s.stage)
                }
            }
            NightIconButton(AppIcons.Help, "성장 점수 계산법", { help = true }, Modifier.align(Alignment.TopEnd).padding(Dimen.m))
        }
        Row(horizontalArrangement = Arrangement.spacedBy(Dimen.m)) {
            // 기본 카드: 이번 주 투어
            Column(Modifier.weight(1f).nightCard().padding(Dimen.l), verticalArrangement = Arrangement.spacedBy(Dimen.m)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("이번 주 출근길 투어", style = Type.bodyL.copy(fontWeight = FontWeight.Bold), color = Night.text, modifier = Modifier.weight(1f))
                    Text(if (s.week.shows >= Tour.GOAL) "투어 완주!" else "공연 ${s.week.shows} / ${Tour.GOAL}", style = Type.bodyS, color = if (s.week.shows >= Tour.GOAL) Night.gold else Night.sub)
                }
                Row(horizontalArrangement = Arrangement.spacedBy(Dimen.s)) {
                    repeat(Tour.GOAL) { i ->
                        val on = i < s.week.shows
                        Box(Modifier.size(72.dp).clip(CircleShape).background(if (on) Night.pink else Color.Transparent)
                            .border(if (on) 0.dp else 3.dp, Night.line, CircleShape), contentAlignment = Alignment.Center) {
                            if (on) Icon(AppIcons.Check, null, tint = Night.onPink, modifier = Modifier.size(36.dp))
                            else Text("${i + 1}", style = Type.bodyM.copy(fontWeight = FontWeight.Bold), color = Night.dim)
                        }
                    }
                }
                Text("혼자 한 곡 이상 부른 주행이 공연 1회", style = Type.label, color = Night.dim)
            }
            // 기본 카드: 이번 주 미션
            Column(Modifier.weight(1.3f).nightCard().padding(Dimen.l), verticalArrangement = Arrangement.spacedBy(Dimen.s)) {
                Text("이번 주 미션", style = Type.bodyL.copy(fontWeight = FontWeight.Bold), color = Night.text)
                s.missions.forEach { m ->
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(m.title, style = Type.bodyM.copy(fontWeight = FontWeight.SemiBold), color = Night.text, modifier = Modifier.weight(1f), maxLines = 1)
                            Text(if (m.done) "완료 +15" else m.progress, style = Type.label.copy(fontWeight = FontWeight.Bold), color = if (m.done) Night.ok else Night.sub)
                        }
                        NightBar(if (m.done) 1f else m.fraction, height = 10.dp, brush = androidx.compose.ui.graphics.SolidColor(if (m.done) Night.ok else Night.sub))
                    }
                }
            }
        }
        // 조용한 카드: 배지(얻은 것은 밝은 메달, 못 얻은 것은 흐린 실루엣). 누르면 얻는 법.
        Column(Modifier.fillMaxWidth().nightQuiet().padding(Dimen.l), verticalArrangement = Arrangement.spacedBy(Dimen.m)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("칭호와 배지", style = Type.bodyL.copy(fontWeight = FontWeight.Bold), color = Night.text, modifier = Modifier.weight(1f))
                Text("${s.badges.size} / ${BadgeId.entries.size}", style = Type.bodyS, color = Night.sub)
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                BadgeId.entries.forEach { b ->
                    val on = b in s.badges
                    Column(Modifier.weight(1f).clip(RoundedCornerShape(20.dp)).clickable { badgeInfo = b }.padding(vertical = 8.dp),
                        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        BadgeMedal(on, badgeIcon(b), 104.dp)
                        Text(b.title, style = Type.label.copy(fontWeight = if (on) FontWeight.Bold else FontWeight.Normal), color = if (on) Night.text else Night.dim,
                            textAlign = TextAlign.Center, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
        }
    }
    if (help) NightInfoDialog(AppIcons.Help, "성장 점수 계산법", GROWTH_HELP, { help = false })
    badgeInfo?.let { b ->
        NightInfoDialog(badgeIcon(b), b.title + if (b in s.badges) " · 얻음" else " · 아직", b.how, { badgeInfo = null })
    }
}

/** 단계 길: 0·20·60·150점 자리에 단계 점 네 개, 지금 점수만큼 금→분홍 막대. */
@Composable
private fun StageTrack(points: Int, stage: Int) {
    val max = Growth.THRESHOLDS.last().toFloat()
    androidx.compose.foundation.layout.BoxWithConstraints(Modifier.fillMaxWidth().height(40.dp).padding(horizontal = 20.dp)) {
        val w = maxWidth
        Box(Modifier.align(Alignment.CenterStart)) { Box(Modifier.width(w).height(16.dp).clip(RoundedCornerShape(8.dp)).background(Night.raised)) }
        Box(Modifier.align(Alignment.CenterStart).width(w * (points / max).coerceIn(0f, 1f)).height(16.dp).clip(RoundedCornerShape(8.dp)).background(Night.progress))
        Growth.THRESHOLDS.forEachIndexed { i, t ->
            val reached = i < stage
            Box(Modifier.offset(x = w * (t / max) - 20.dp).size(40.dp).clip(CircleShape).background(if (reached) Night.text else Night.raised)
                .border(4.dp, Night.card, CircleShape), contentAlignment = Alignment.Center) {
                Text("${i + 1}", style = Type.label.copy(fontWeight = FontWeight.ExtraBold), color = if (reached) Night.bg else Night.dim)
            }
        }
    }
}

// ---------- 점수 탭: 고스트와 대피 랭킹 ----------

data class GhostBannerUi(val songId: String, val songTitle: String, val nickname: String, val score: Int)

@Composable
fun GhostBanner(g: GhostBannerUi, onCancel: () -> Unit) {
    Row(Modifier.fillMaxWidth().nightCard(Night.pinkTint).border(2.dp, Color(0x73FF5FA8), RoundedCornerShape(Dimen.radiusCard)).padding(Dimen.l),
        verticalAlignment = Alignment.CenterVertically) {
        Icon(AppIcons.Ghost, null, tint = Night.pink, modifier = Modifier.size(Dimen.icon))
        Spacer(Modifier.width(Dimen.m))
        Column(Modifier.weight(1f)) {
            Text("고스트 대결 준비됨 · ${g.nickname}님 ${g.score}점", style = Type.bodyL.copy(fontWeight = FontWeight.Bold), color = Night.text)
            Text("다음에 「${g.songTitle}」을 혼자 부르면 ${g.nickname}님의 목소리가 아주 작게 깔립니다", style = Type.bodyS, color = Night.sub)
        }
        NightButton("취소", onCancel, icon = AppIcons.Minus, style = NightStyle.TONAL)
    }
}

/** 단상에 올릴 사람 한 명. */
data class PodiumEntry(val rank: Int, val name: String, val value: String, val mine: Boolean)

/** 1~3위 단상: 2위 왼쪽, 1위 가운데(가장 높음), 3위 오른쪽. 메달·별명·기록. */
@Composable
fun Podium(top: List<PodiumEntry>, actions: (@Composable (rank: Int) -> Unit)? = null) {
    if (top.isEmpty()) return
    Row(Modifier.fillMaxWidth().padding(horizontal = Dimen.m, vertical = Dimen.s), horizontalArrangement = Arrangement.spacedBy(Dimen.m), verticalAlignment = Alignment.Bottom) {
        listOf(2 to 150.dp, 1 to 200.dp, 3 to 116.dp).forEach { (r, h) ->
            val e = top.firstOrNull { it.rank == r }
            Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(10.dp, Alignment.Bottom)) {
                if (e != null) {
                    Medal(r, if (r == 1) 104.dp else 84.dp)
                    Text(e.name + if (e.mine) " (나)" else "", style = Type.bodyM.copy(fontWeight = FontWeight.Bold), color = if (e.mine) Night.pink else Night.text,
                        maxLines = 1, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center)
                    Text(e.value, style = Type.titleM, color = if (r == 1) Night.gold else Night.text)
                    actions?.invoke(r)
                }
                Box(Modifier.fillMaxWidth().height(h).clip(RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp))
                    .background(Brush.verticalGradient(listOf(Night.raised, Night.card))).border(1.dp, if (e?.mine == true) Night.pink else Night.line, RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp)),
                    contentAlignment = Alignment.TopCenter) {
                    Text("${r}위", style = Type.label.copy(fontWeight = FontWeight.Bold), color = Night.dim, modifier = Modifier.padding(top = 14.dp))
                }
            }
        }
    }
}

/** 4위부터의 한 줄. 내 줄은 분홍 테두리. */
@Composable
fun RankRow(rank: Int, name: String, mine: Boolean, value: String, sub: String? = null, trailing: (@Composable () -> Unit)? = null) {
    val shape = RoundedCornerShape(20.dp)
    Row(Modifier.fillMaxWidth().heightIn(min = 96.dp)
        .then(if (mine) Modifier.clip(shape).background(Night.pinkTint).border(2.dp, Night.pink, shape) else Modifier)
        .padding(horizontal = Dimen.l), verticalAlignment = Alignment.CenterVertically) {
        Text("$rank", style = Type.titleS, color = if (mine) Night.text else Night.dim, modifier = Modifier.width(72.dp))
        Column(Modifier.weight(1f)) {
            Text(name + if (mine) " (나)" else "", style = Type.bodyM.copy(fontWeight = FontWeight.SemiBold), color = Night.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
            sub?.let { Text(it, style = Type.label, color = Night.sub) }
        }
        trailing?.invoke()
        Spacer(Modifier.width(Dimen.m))
        Text(value, style = Type.titleS, color = Night.text)
    }
}

@Composable
fun EscapeRows(rows: List<EscapeEntry>, myRank: Int?, total: Int, uploading: Boolean) {
    Column(Modifier.fillMaxWidth().padding(vertical = Dimen.m), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        if (rows.isEmpty()) Body("이번 주 이 곡의 대피 기록이 아직 없습니다. 노래방 중에 문이 열리면 가수가 돌아오기까지의 시간이 기록됩니다.", Night.sub, Type.bodyM, Modifier.padding(Dimen.l))
        Podium(rows.filter { it.rank <= 3 }.map { PodiumEntry(it.rank, it.nickname, secondsText(it.ms), it.mine) })
        rows.filter { it.rank > 3 }.forEach { e -> RankRow(e.rank, e.nickname, e.mine, secondsText(e.ms), causeText(e.cause)) }
        Body(when {
            !uploading -> "설정에서 「전국 랭킹에 올리기」를 켜면 내 가장 빠른 대피도 올라갑니다(별명만 공개)."
            myRank == null -> "들킬 뻔한 순간에 빨리 숨으면 여기에 올라갑니다."
            else -> "이번 주 내 순위 ${myRank}위 / ${total}명"
        }, Night.sub, Type.bodyS, Modifier.padding(horizontal = Dimen.l, vertical = Dimen.s))
    }
}
