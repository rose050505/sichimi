package app.sichimi.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import kotlinx.coroutines.delay

/**
 * 디자인 체계 v3 「한밤 무대」(docs/design/디자인체계.md). 혼자일 때만 보이는 화면(시치미 모드)에서만 쓴다.
 * 동승자가 있거나 일반 상태인 화면은 [Palette]·Components.kt(1차 Pleos 체계)를 그대로 쓰고, 이 파일을 쓰지 않는다.
 *
 * - 색: 무대 바탕 [Night.bg], 대표 강조 [Night.pink](지금 상태·할 일), 보조 강조 [Night.gold](자랑할 숫자·메달), 상태색 ok·danger·info.
 * - 글자: [Type](Pleos 크기) 그대로. 숫자와 가사 한 줄만 Display(64·88·160).
 * - 카드 무게: 대표([nightHero]) → 기본([nightCard]) → 조용한([nightQuiet]).
 * - 움직임 없음(눌렀을 때 색만 바뀜).
 */
object Night {
    val bg = Color(0xFF100B1C)
    val card = Color(0xFF1A1330)
    val raised = Color(0xFF251C3F)
    val pressed = Color(0xFF33274F)
    val line = Color(0xFF3A2D58)
    val heroTop = Color(0xFF3A1A4A)
    val heroLine = Color(0xFF4A2F66)

    val text = Color(0xFFFFFFFF)
    val sub = Color(0xFFD2C9E3)
    val dim = Color(0xFFABA0C4)

    /** 대표 강조: 시치미 분홍. */
    val pink = Color(0xFFFF5FA8)
    val pinkPressed = Color(0xFFE0478E)
    val onPink = Color(0xFF2A0716)
    val pinkTint = Color(0xFF3A1733)

    /** 보조 강조: 달빛 금색. */
    val gold = Color(0xFFFFC65C)
    val onGold = Color(0xFF2A1A00)
    val goldTint = Color(0xFF3A2C1E)

    val ok = Color(0xFF5FE3B0)
    val okTint = Color(0xFF1B3A35)
    val danger = Color(0xFFFF8A7C)
    val dangerTint = Color(0xFF3A1C24)
    val info = Color(0xFFA9C2FF)

    /** 반투명 조작 바탕(무대 위). */
    val glass = Color(0xD1140D24)

    /** 메달 금·은·동(밝은 쪽, 짙은 쪽, 숫자 색). */
    val medalGold = Triple(Color(0xFFFFE08A), Color(0xFFE8A63A), Color(0xFF3A2400))
    val medalSilver = Triple(Color(0xFFEEF2F8), Color(0xFF9EAABC), Color(0xFF1E2430))
    val medalBronze = Triple(Color(0xFFF2B98F), Color(0xFFB9714A), Color(0xFF2E1608))

    val progress = Brush.horizontalGradient(listOf(gold, pink))
}

private val HeroShape = RoundedCornerShape(36.dp)
private val CardShape = RoundedCornerShape(Dimen.radiusCard)

/** 대표 카드: 왼쪽 위에서 퍼지는 빛. 화면에 하나. */
fun Modifier.nightHero(): Modifier = this.clip(HeroShape)
    .background(Brush.radialGradient(listOf(Night.heroTop, Night.card), center = Offset(0f, 0f), radius = 1400f))
    .border(1.dp, Night.heroLine, HeroShape)

/** 기본 카드. */
fun Modifier.nightCard(color: Color = Night.card): Modifier = this.clip(CardShape).background(color).border(1.dp, Night.line, CardShape)

/** 조용한 카드(테두리만). 아래쪽 덜 중요한 것. */
fun Modifier.nightQuiet(): Modifier = this.clip(CardShape).border(1.dp, Night.line, CardShape)

@Composable
private fun Modifier.nightPress(onClick: () -> Unit, color: Color, pressed: Color, shape: Shape, role: Role = Role.Button): Modifier {
    val src = remember { MutableInteractionSource() }
    val isPressed by src.collectIsPressedAsState()
    return this.clip(shape).background(if (isPressed) pressed else color).clickable(src, null, role = role, onClick = onClick)
}

// ---------- 로고 ----------

/** 「시치미」 글자 로고. 둥근 획으로 직접 그린 글자, ㅊ 꼭지는 상어지느러미 안테나, ㅁ 안은 딴청 눈. docs/design/logo.svg와 같다. */
val SichimiWordmark: ImageVector by lazy {
    ImageVector.Builder("sichimi-wordmark", 304.dp, 110.dp, 304f, 110f).apply {
        // viewBox(-6 -10 304 110)를 0,0 기준으로 옮긴다
        fun x(v: Float) = v + 6f
        fun y(v: Float) = v + 10f
        val white = SolidColor(Color.White)
        fun stroke(block: androidx.compose.ui.graphics.vector.PathBuilder.() -> Unit) =
            path(stroke = white, strokeLineWidth = 15f, strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round, pathBuilder = block)
        stroke { moveTo(x(8f), y(84f)); lineTo(x(31f), y(20f)); lineTo(x(54f), y(84f)) }
        stroke { moveTo(x(76f), y(8f)); lineTo(x(76f), y(92f)) }
        stroke { moveTo(x(104f), y(34f)); lineTo(x(158f), y(34f)) }
        stroke { moveTo(x(108f), y(84f)); lineTo(x(131f), y(50f)); lineTo(x(154f), y(84f)) }
        stroke { moveTo(x(180f), y(8f)); lineTo(x(180f), y(92f)) }
        stroke { moveTo(x(204f), y(26f)); lineTo(x(258f), y(26f)); lineTo(x(258f), y(82f)); lineTo(x(204f), y(82f)); close() }
        stroke { moveTo(x(282f), y(8f)); lineTo(x(282f), y(92f)) }
        path(fill = SolidColor(Night.pink)) {
            moveTo(x(114f), y(27f)); curveTo(x(121f), y(18f), x(128f), y(7f), x(143f), y(-3f))
            curveTo(x(139f), y(8f), x(139f), y(18f), x(149f), y(27f)); close()
        }
        fun dot(cx: Float, cy: Float, r: Float) = path(fill = white) {
            moveTo(x(cx - r), y(cy)); arcTo(r, r, 0f, false, true, x(cx + r), y(cy)); arcTo(r, r, 0f, false, true, x(cx - r), y(cy)); close()
        }
        dot(230f, 47f, 5.5f); dot(244f, 47f, 5.5f)
    }.build()
}

@Composable
fun SichimiLogo(height: Dp, modifier: Modifier = Modifier) {
    Image(rememberVectorPainter(SichimiWordmark), "시치미", modifier.size(width = height * (304f / 110f), height = height))
}

// ---------- 탭 줄(로고 + 탭) ----------

@Composable
fun <T> NightTabs(items: List<T>, selected: T, label: (T) -> String, icon: (T) -> ImageVector?, onSelect: (T) -> Unit) {
    Row(Modifier.fillMaxWidth().heightIn(min = 80.dp), verticalAlignment = Alignment.CenterVertically) {
        SichimiLogo(44.dp, Modifier.padding(start = 4.dp))
        Spacer(Modifier.width(40.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            items.forEach { t ->
                val on = t == selected
                val shape = RoundedCornerShape(36.dp)
                Row(
                    Modifier.heightIn(min = 72.dp)
                        .nightPress({ onSelect(t) }, if (on) Night.raised else Color.Transparent, Night.pressed, shape, Role.Tab)
                        .then(if (on) Modifier.border(2.dp, Night.line, shape) else Modifier)
                        .padding(horizontal = 26.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    icon(t)?.let { Icon(it, null, tint = if (on) Night.text else Night.dim, modifier = Modifier.size(28.dp)); Spacer(Modifier.width(12.dp)) }
                    Text(label(t), style = Type.button.copy(fontWeight = FontWeight.Bold), color = if (on) Night.text else Night.sub, maxLines = 1)
                }
            }
        }
    }
}

// ---------- 버튼 ----------

enum class NightStyle { PRIMARY, TONAL, QUIET, DANGER, GOLD }

@Composable
fun NightButton(
    label: String,
    onClick: () -> Unit,
    icon: ImageVector? = null,
    style: NightStyle = NightStyle.TONAL,
    modifier: Modifier = Modifier,
    height: Dp = Dimen.button,
) {
    val (bg, pressed, fg) = when (style) {
        NightStyle.PRIMARY -> Triple(Night.pink, Night.pinkPressed, Night.onPink)
        NightStyle.GOLD -> Triple(Night.gold, Color(0xFFE6AE48), Night.onGold)
        NightStyle.TONAL -> Triple(Night.raised, Night.pressed, Night.text)
        NightStyle.QUIET -> Triple(Color.Transparent, Night.raised, Night.sub)
        NightStyle.DANGER -> Triple(Night.dangerTint, Night.pressed, Night.danger)
    }
    Row(
        modifier.heightIn(min = height).widthIn(min = 140.dp)
            .nightPress(onClick, bg, pressed, RoundedCornerShape(Dimen.radiusControl))
            .padding(horizontal = 28.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
    ) {
        if (icon != null) {
            Icon(icon, null, tint = fg, modifier = Modifier.size(32.dp))
            Spacer(Modifier.width(12.dp))
        }
        Text(label, style = Type.button.copy(fontWeight = FontWeight.Bold), color = fg, maxLines = 1)
    }
}

/** 동그란 아이콘 버튼(지름 64). 이름은 contentDescription. */
@Composable
fun NightIconButton(icon: ImageVector, description: String, onClick: () -> Unit, modifier: Modifier = Modifier, glass: Boolean = false, active: Boolean = false) {
    Box(
        modifier.size(64.dp).nightPress(onClick, if (active) Night.pink else if (glass) Night.glass else Night.raised, if (active) Night.pinkPressed else Night.pressed, CircleShape)
            .semantics { contentDescription = description },
        contentAlignment = Alignment.Center,
    ) { Icon(icon, null, tint = if (active) Night.onPink else Night.sub, modifier = Modifier.size(34.dp)) }
}

/** 고르는 칩 줄(곡 고르기, 점수/대피 나누기). 고른 칩은 높은 바탕 + 테두리, 안 고른 칩은 테두리만. */
@Composable
fun <T> NightChips(items: List<T>, selected: T, label: (T) -> String, icon: (T) -> ImageVector? = { null }, onSelect: (T) -> Unit, modifier: Modifier = Modifier) {
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        items.forEach { t ->
            val on = t == selected
            val shape = RoundedCornerShape(32.dp)
            Row(
                Modifier.heightIn(min = 64.dp)
                    .nightPress({ onSelect(t) }, if (on) Night.raised else Color.Transparent, Night.pressed, shape, Role.Tab)
                    .border(2.dp, if (on) Night.pink else Night.line, shape)
                    .padding(horizontal = 24.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                icon(t)?.let { Icon(it, null, tint = if (on) Night.pink else Night.dim, modifier = Modifier.size(26.dp)); Spacer(Modifier.width(10.dp)) }
                Text(label(t), style = Type.button.copy(fontWeight = FontWeight.Bold), color = if (on) Night.text else Night.sub, maxLines = 1)
            }
        }
    }
}

/** 상태 칩: 혼자만의 노래방(분홍 점 + 분홍 글자). */
@Composable
fun LiveChip(text: String, big: Boolean = false) {
    Row(
        Modifier.clip(RoundedCornerShape(50)).background(Night.pinkTint).border(2.dp, Color(0x73FF5FA8), RoundedCornerShape(50))
            .padding(horizontal = if (big) 24.dp else 20.dp, vertical = if (big) 10.dp else 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(AppIcons.Mic, null, tint = Night.pink, modifier = Modifier.size(if (big) 34.dp else 28.dp))
        Spacer(Modifier.width(10.dp))
        Text(text, style = (if (big) Type.bodyL else Type.bodyS).copy(fontWeight = FontWeight.Bold), color = Night.pink)
    }
}

/** 작은 알림 칩(색 글자 + 옅은 바탕). */
@Composable
fun TagChip(text: String, color: Color, tint: Color, icon: ImageVector? = null) {
    Row(Modifier.clip(RoundedCornerShape(50)).background(tint).padding(horizontal = 18.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        icon?.let { Icon(it, null, tint = color, modifier = Modifier.size(24.dp)); Spacer(Modifier.width(8.dp)) }
        Text(text, style = Type.label.copy(fontWeight = FontWeight.Bold), color = color, maxLines = 1)
    }
}

// ---------- 무대 조작부 ----------

private fun clockText(ms: Long): String {
    val s = (ms / 1000).coerceAtLeast(0)
    return "%d:%02d".format(s / 60, s % 60)
}

/**
 * 무대 아래 하나로 이어진 조작부: 이전 · 재생/일시정지 · 다음 · 경과 · 진행 막대 · 길이.
 * 버튼 이름(이전 곡, 재생, 일시정지, 다음 곡)은 1차와 같다. 경과 시간은 1초마다 바뀌므로 접근성 트리에서 뺀다.
 */
@Composable
fun ControlDock(isPlaying: Boolean, durationMs: Long, positionMs: () -> Long, onPrev: () -> Unit, onPlayPause: () -> Unit, onNext: () -> Unit, modifier: Modifier = Modifier) {
    var sec by remember { mutableLongStateOf(positionMs() / 1000) }
    LaunchedEffect(isPlaying, durationMs) { while (true) { sec = positionMs() / 1000; delay(250) } }
    Row(
        modifier.height(104.dp).clip(RoundedCornerShape(52.dp)).background(Night.glass).border(1.dp, Night.line, RoundedCornerShape(52.dp))
            .padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        DockKey(AppIcons.Previous, "이전 곡", onPrev)
        Box(
            Modifier.padding(horizontal = 4.dp).size(88.dp).nightPress(onPlayPause, Night.pink, Night.pinkPressed, CircleShape)
                .semantics { contentDescription = if (isPlaying) "일시정지" else "재생" },
            contentAlignment = Alignment.Center,
        ) { Icon(if (isPlaying) AppIcons.Pause else AppIcons.Play, null, tint = Night.onPink, modifier = Modifier.size(42.dp)) }
        DockKey(AppIcons.Next, "다음 곡", onNext)
        Row(Modifier.weight(1f).clearAndSetSemantics { }, verticalAlignment = Alignment.CenterVertically) {
            Text(clockText(sec * 1000), style = Type.label, color = Night.sub, modifier = Modifier.padding(start = 20.dp).widthIn(min = 72.dp), textAlign = TextAlign.End)
            val frac = if (durationMs > 0) (sec * 1000f / durationMs).coerceIn(0f, 1f) else 0f
            Box(Modifier.weight(1f).padding(horizontal = 20.dp).height(24.dp), contentAlignment = Alignment.CenterStart) {
                Box(Modifier.fillMaxWidth().height(8.dp).clip(RoundedCornerShape(4.dp)).background(Color(0x2EFFFFFF)))
                Box(Modifier.fillMaxWidth(frac).height(8.dp).clip(RoundedCornerShape(4.dp)).background(Night.progress))
            }
            Text(if (durationMs > 0) clockText(durationMs) else "", style = Type.label, color = Night.dim, modifier = Modifier.padding(end = 24.dp).widthIn(min = 72.dp))
        }
    }
}

@Composable
private fun DockKey(icon: ImageVector, label: String, onClick: () -> Unit) {
    Box(
        Modifier.size(width = 96.dp, height = 80.dp).nightPress(onClick, Color.Transparent, Night.pressed, RoundedCornerShape(40.dp))
            .semantics { contentDescription = label },
        contentAlignment = Alignment.Center,
    ) { Icon(icon, null, tint = Night.text, modifier = Modifier.size(40.dp)) }
}

// ---------- 메달과 배지 ----------

/** 랭킹 메달(1~3위). 그 밖은 회색 원. */
@Composable
fun Medal(rank: Int, size: Dp, modifier: Modifier = Modifier) {
    val c = when (rank) { 1 -> Night.medalGold; 2 -> Night.medalSilver; 3 -> Night.medalBronze; else -> null }
    Box(
        modifier.size(size).clip(CircleShape)
            .background(if (c != null) Brush.radialGradient(listOf(c.first, c.second), center = Offset.Unspecified) else SolidColor(Night.raised))
            .border(if (c != null) 4.dp else 0.dp, Color(0x1FFFFFFF), CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        Text("$rank", style = Type.titleS.copy(fontSize = Type.titleS.fontSize * (size.value / 72f), fontWeight = FontWeight.ExtraBold),
            color = c?.third ?: Night.text, maxLines = 1)
    }
}

/** 배지: 얻은 것은 밝은 금메달, 못 얻은 것은 흐린 실루엣(점선 대신 옅은 테두리와 흐린 그림). */
@Composable
fun BadgeMedal(on: Boolean, icon: ImageVector, size: Dp) {
    if (on) {
        Box(
            Modifier.size(size).clip(CircleShape)
                .background(Brush.radialGradient(listOf(Color(0xFFFFE6A6), Night.gold, Color(0xFFD98A2B))))
                .border(5.dp, Color(0x29FFC65C), CircleShape),
            contentAlignment = Alignment.Center,
        ) { Icon(icon, null, tint = Night.onGold, modifier = Modifier.size(size * 0.46f)) }
    } else {
        Box(Modifier.size(size).clip(CircleShape).background(Color(0x0FFFFFFF)).border(3.dp, Night.line, CircleShape), contentAlignment = Alignment.Center) {
            Icon(icon, null, tint = Night.line, modifier = Modifier.size(size * 0.44f))
        }
    }
}

// ---------- 막대 ----------

@Composable
fun NightBar(fraction: Float, modifier: Modifier = Modifier, height: Dp = 12.dp, brush: Brush = Night.progress) {
    Box(modifier.fillMaxWidth().height(height).clip(RoundedCornerShape(height / 2)).background(Night.raised)) {
        if (fraction > 0f) Box(Modifier.fillMaxWidth(fraction.coerceIn(0.02f, 1f)).fillMaxHeight().clip(RoundedCornerShape(height / 2)).background(brush))
    }
}

// ---------- 설정 부품 ----------

@Composable
fun NightSection(title: String, icon: ImageVector? = null, content: @Composable ColumnScope.() -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(Dimen.s)) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            if (icon != null) { Icon(icon, null, tint = Night.pink, modifier = Modifier.size(30.dp)); Spacer(Modifier.width(12.dp)) }
            Text(title, style = Type.bodyL.copy(fontWeight = FontWeight.Bold), color = Night.text)
        }
        Column(Modifier.fillMaxWidth().nightCard(), content = content)
    }
}

@Composable
fun NightDivider(start: Dp = Dimen.l) {
    Box(Modifier.fillMaxWidth().padding(start = start, end = Dimen.l).height(1.dp).background(Night.line))
}

@Composable
fun NightSwitch(checked: Boolean) {
    Box(Modifier.size(width = 88.dp, height = 48.dp).clip(RoundedCornerShape(50)).background(if (checked) Night.pink else Night.pressed)) {
        Box(Modifier.offset(x = if (checked) 44.dp else 4.dp, y = 4.dp).size(40.dp).clip(CircleShape).background(if (checked) Night.text else Night.sub))
    }
}

@Composable
fun NightSwitchRow(title: String, sub: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = 112.dp).toggleable(checked, role = Role.Switch, onValueChange = onChange)
            .padding(horizontal = Dimen.l, vertical = 20.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = Type.bodyM.copy(fontWeight = FontWeight.SemiBold), color = Night.text)
            Spacer(Modifier.height(4.dp))
            Text(sub, style = Type.label, color = Night.sub)
        }
        Spacer(Modifier.width(Dimen.m))
        NightSwitch(checked)
    }
}

@Composable
fun <T> NightSegmented(options: List<T>, value: T, label: (T) -> String, onChange: (T) -> Unit) {
    Row(Modifier.clip(RoundedCornerShape(Dimen.radiusControl)).background(Night.bg).padding(6.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        options.forEach { o ->
            val on = o == value
            Box(
                Modifier.heightIn(min = 72.dp).widthIn(min = 116.dp)
                    .nightPress({ onChange(o) }, if (on) Night.pink else Night.bg, if (on) Night.pinkPressed else Night.raised, RoundedCornerShape(16.dp))
                    .padding(horizontal = 20.dp),
                contentAlignment = Alignment.Center,
            ) { Text(label(o), style = Type.button.copy(fontWeight = FontWeight.Bold), color = if (on) Night.onPink else Night.sub) }
        }
    }
}

// ---------- 창 ----------

/** 확인 창(한밤 무대). 오른쪽이 하려는 일, 왼쪽이 취소. 1차 [ConfirmDialog]와 버튼 이름·순서가 같다. */
@Composable
fun NightDialog(
    icon: ImageVector,
    iconTint: Color,
    title: String,
    body: String,
    confirmLabel: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
    confirmStyle: NightStyle = NightStyle.PRIMARY,
    dismissLabel: String = "취소",
) {
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Column(
            Modifier.width(920.dp).nightHero().padding(Dimen.xl),
            verticalArrangement = Arrangement.spacedBy(Dimen.m),
        ) {
            Box(Modifier.size(80.dp).clip(CircleShape).background(Night.raised), contentAlignment = Alignment.Center) {
                Icon(icon, null, tint = iconTint, modifier = Modifier.size(44.dp))
            }
            Text(title, style = Type.titleM, color = Night.text)
            Text(body, style = Type.bodyM.copy(lineHeight = androidx.compose.ui.unit.TextUnit(40f, androidx.compose.ui.unit.TextUnitType.Sp)), color = Night.sub)
            Spacer(Modifier.height(8.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(Dimen.s, Alignment.End)) {
                NightButton(dismissLabel, onDismiss, style = NightStyle.TONAL, modifier = Modifier.widthIn(min = 200.dp))
                if (confirmLabel.isNotEmpty()) NightButton(confirmLabel, onConfirm, style = confirmStyle, modifier = Modifier.widthIn(min = 260.dp))
            }
        }
    }
}

/** 정보 창(곡 출처, 성장 점수 계산법). 닫기만 있다. */
@Composable
fun NightInfoDialog(icon: ImageVector, title: String, body: String, onClose: () -> Unit) =
    NightDialog(icon, Night.info, title, body, "", {}, onClose, dismissLabel = "닫기")

/** 빈 상태(시치미 + 한 줄). */
@Composable
fun NightEmpty(text: String, stage: Int, modifier: Modifier = Modifier) {
    Row(modifier.fillMaxWidth().nightCard().padding(horizontal = Dimen.l, vertical = Dimen.m), verticalAlignment = Alignment.CenterVertically) {
        Mascot(stage, app.sichimi.gate.MascotPose.SINGING, app.sichimi.gate.MascotLook(), Modifier.size(140.dp))
        Spacer(Modifier.width(Dimen.m))
        Text(text, style = Type.bodyM, color = Night.sub, modifier = Modifier.weight(1f), maxLines = 3, overflow = TextOverflow.Ellipsis)
    }
}
