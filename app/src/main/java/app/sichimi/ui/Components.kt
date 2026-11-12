package app.sichimi.ui

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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties

/** 누르면 색만 바뀐다(물결 애니메이션 없음). */
@Composable
fun Modifier.pressable(onClick: () -> Unit, color: Color, pressed: Color, shape: Shape, role: Role = Role.Button): Modifier {
    val src = remember { MutableInteractionSource() }
    val isPressed by src.collectIsPressedAsState()
    return this.clip(shape).background(if (isPressed) pressed else color).clickable(src, null, role = role, onClick = onClick)
}

/** 카드 표면. */
fun Modifier.card(color: Color = Palette.surface): Modifier = this.clip(RoundedCornerShape(Dimen.radiusCard)).background(color)

enum class ButtonStyle { FILLED, TONAL, DANGER, QUIET }

/** 아이콘과 글자가 있는 버튼(Pleos Basic button). 높이 80dp. */
@Composable
fun AppButton(
    label: String,
    onClick: () -> Unit,
    icon: ImageVector? = null,
    style: ButtonStyle = ButtonStyle.TONAL,
    modifier: Modifier = Modifier,
    height: Dp = Dimen.button,
) {
    val (bg, pressed, fg) = when (style) {
        ButtonStyle.FILLED -> Triple(Palette.accentFill, Palette.accentPressed, Palette.text)
        ButtonStyle.TONAL -> Triple(Palette.control, Palette.controlPressed, Palette.text)
        ButtonStyle.DANGER -> Triple(Palette.dangerTint, Palette.control, Palette.danger)
        ButtonStyle.QUIET -> Triple(Color.Transparent, Palette.surfaceHi, Palette.sub)
    }
    Row(
        modifier.heightIn(min = height).widthIn(min = 140.dp)
            .pressable(onClick, bg, pressed, RoundedCornerShape(Dimen.radiusControl))
            .padding(horizontal = 28.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
    ) {
        if (icon != null) {
            Icon(icon, null, tint = fg, modifier = Modifier.size(32.dp))
            Spacer(Modifier.width(12.dp))
        }
        Text(label, style = Type.button, color = fg, maxLines = 1)
    }
}

/** 이전·재생/일시정지·다음. 재생 버튼만 강조색. 글자 대신 아이콘이고, 이름은 contentDescription으로 둔다. */
enum class Transport { PREVIOUS, PLAY, PAUSE, NEXT }

@Composable
fun TransportButton(kind: Transport, label: String, height: Dp, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val primary = kind == Transport.PLAY || kind == Transport.PAUSE
    Box(
        modifier.heightIn(min = height)
            .pressable(onClick, if (primary) Palette.accentFill else Palette.control,
                if (primary) Palette.accentPressed else Palette.controlPressed, RoundedCornerShape(Dimen.radiusCard))
            .semantics { contentDescription = label },
        contentAlignment = Alignment.Center,
    ) {
        val icon = when (kind) {
            Transport.PREVIOUS -> AppIcons.Previous
            Transport.PLAY -> AppIcons.Play
            Transport.PAUSE -> AppIcons.Pause
            Transport.NEXT -> AppIcons.Next
        }
        Icon(icon, null, tint = Palette.text, modifier = Modifier.size(height * 0.42f))
    }
}

@Composable
fun TransportRow(isPlaying: Boolean, height: Dp, onPrev: () -> Unit, onPlayPause: () -> Unit, onNext: () -> Unit) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(Dimen.m)) {
        TransportButton(Transport.PREVIOUS, "이전 곡", height, onPrev, Modifier.weight(1f))
        TransportButton(if (isPlaying) Transport.PAUSE else Transport.PLAY, if (isPlaying) "일시정지" else "재생", height, onPlayPause, Modifier.weight(1.5f))
        TransportButton(Transport.NEXT, "다음 곡", height, onNext, Modifier.weight(1f))
    }
}

/** 앨범 자리. 곡 그림이 없으므로 같은 모양의 음표 타일을 쓴다(모든 상태에서 똑같다). */
@Composable
fun ArtTile(size: Dp, modifier: Modifier = Modifier) {
    Box(
        modifier.size(size).clip(RoundedCornerShape(size * 0.14f))
            .background(Brush.linearGradient(listOf(Color(0xFF3A3C43), Color(0xFF232428)))),
        contentAlignment = Alignment.Center,
    ) { Icon(AppIcons.Note, null, tint = Palette.dim, modifier = Modifier.size(size * 0.4f)) }
}

/**
 * 재생 상태 한 줄. 노래방일 때만 호박색 알약 모양이 되고, 그 밖에는 평범한 플레이어의 회색 글자다.
 * 노래방은 혼자일 때만 되므로, 동승자가 있는 화면에는 언제나 회색 글자만 보인다.
 */
@Composable
fun StatusLine(text: String, karaoke: Boolean, playing: Boolean, big: Boolean = false) {
    val style = if (big) Type.bodyL.copy(fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold)
    else Type.bodyM.copy(fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold)
    if (karaoke) {
        Row(
            Modifier.clip(RoundedCornerShape(50)).background(Palette.karaokeTint).padding(horizontal = 20.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(AppIcons.Mic, null, tint = Palette.karaoke, modifier = Modifier.size(if (big) 34.dp else 30.dp))
            Spacer(Modifier.width(10.dp))
            Text(text, style = style, color = Palette.karaoke)
        }
    } else {
        Row(Modifier.padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(if (playing) AppIcons.Note else AppIcons.Pause, null, tint = Palette.sub, modifier = Modifier.size(if (big) 34.dp else 30.dp))
            Spacer(Modifier.width(10.dp))
            Text(text, style = style, color = Palette.sub)
        }
    }
}

/** 탭(Pleos segmented menu). 아이콘과 글자. */
@Composable
fun <T> SegmentedTabs(items: List<T>, selected: T, label: (T) -> String, icon: (T) -> ImageVector?, onSelect: (T) -> Unit) {
    Row(
        Modifier.clip(RoundedCornerShape(Dimen.radiusCard)).background(Palette.surface).padding(6.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        items.forEach { t ->
            val on = t == selected
            Row(
                Modifier.heightIn(min = 76.dp)
                    .pressable({ onSelect(t) }, if (on) Palette.control else Palette.surface, Palette.surfaceHi, RoundedCornerShape(22.dp), Role.Tab)
                    .padding(horizontal = 28.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                icon(t)?.let { Icon(it, null, tint = if (on) Palette.text else Palette.dim, modifier = Modifier.size(30.dp)); Spacer(Modifier.width(12.dp)) }
                Text(label(t), style = Type.button, color = if (on) Palette.text else Palette.sub, maxLines = 1)
            }
        }
    }
}

/** 고르기(대기 시간 등). 고른 것은 강조색. */
@Composable
fun <T> Segmented(options: List<T>, value: T, label: (T) -> String, onChange: (T) -> Unit) {
    Row(
        Modifier.clip(RoundedCornerShape(Dimen.radiusControl)).background(Palette.bg).padding(6.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        options.forEach { o ->
            val on = o == value
            Box(
                Modifier.heightIn(min = 72.dp).widthIn(min = 116.dp)
                    .pressable({ onChange(o) }, if (on) Palette.accentFill else Palette.bg, if (on) Palette.accentPressed else Palette.surfaceHi, RoundedCornerShape(16.dp))
                    .padding(horizontal = 20.dp),
                contentAlignment = Alignment.Center,
            ) { Text(label(o), style = Type.button, color = if (on) Palette.text else Palette.sub) }
        }
    }
}

/** 큰 스위치(88×48). 움직임 없이 바로 바뀐다. 줄 전체가 눌리는 영역이다. */
@Composable
fun SwitchMark(checked: Boolean) {
    Box(
        Modifier.size(width = 88.dp, height = 48.dp).clip(RoundedCornerShape(50))
            .background(if (checked) Palette.accentFill else Palette.control),
    ) {
        Box(
            Modifier.offset(x = if (checked) 44.dp else 4.dp, y = 4.dp).size(40.dp).clip(CircleShape)
                .background(if (checked) Palette.text else Palette.sub),
        )
    }
}

/** 설정 한 줄: 제목, 설명, 오른쪽 스위치. */
@Composable
fun SwitchRow(title: String, sub: String, checked: Boolean, onChange: (Boolean) -> Unit, icon: ImageVector? = null) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = 112.dp)
            .toggleable(checked, role = Role.Switch, onValueChange = onChange)
            .padding(horizontal = Dimen.l, vertical = 20.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) {
            Icon(icon, null, tint = Palette.sub, modifier = Modifier.size(Dimen.icon)); Spacer(Modifier.width(Dimen.m))
        }
        Column(Modifier.weight(1f)) {
            Text(title, style = Type.bodyM.copy(fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold), color = Palette.text)
            Spacer(Modifier.height(4.dp))
            Text(sub, style = Type.label, color = Palette.sub)
        }
        Spacer(Modifier.width(Dimen.m))
        SwitchMark(checked)
    }
}

/** 카드 안의 줄 사이 구분선. */
@Composable
fun Divider(start: Dp = Dimen.l) {
    Box(Modifier.fillMaxWidth().padding(start = start, end = Dimen.l).height(1.dp).background(Palette.divider))
}

/** 무리(제목 + 카드). */
@Composable
fun Section(title: String, icon: ImageVector? = null, trailing: (@Composable () -> Unit)? = null, content: @Composable ColumnScope.() -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(Dimen.s)) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            if (icon != null) { Icon(icon, null, tint = Palette.sub, modifier = Modifier.size(30.dp)); Spacer(Modifier.width(12.dp)) }
            Text(title, style = Type.bodyM.copy(fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold), color = Palette.sub, modifier = Modifier.weight(1f))
            trailing?.invoke()
        }
        Column(Modifier.fillMaxWidth().card(), content = content)
    }
}

@Composable
fun Body(text: String, color: Color = Palette.sub, style: TextStyle = Type.bodyS, modifier: Modifier = Modifier, maxLines: Int = Int.MAX_VALUE) =
    Text(text, style = style, color = color, modifier = modifier, maxLines = maxLines, overflow = TextOverflow.Ellipsis)

/**
 * 확인 창(Pleos system popup). 화면을 어둡게 덮고 가운데에 뜬다. 오른쪽이 하려는 일, 왼쪽이 취소.
 */
@Composable
fun ConfirmDialog(
    icon: ImageVector,
    iconTint: Color,
    title: String,
    body: String,
    confirmLabel: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
    confirmStyle: ButtonStyle = ButtonStyle.FILLED,
    titleColor: Color = Palette.text,
) {
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Column(
            Modifier.width(920.dp).clip(RoundedCornerShape(36.dp)).background(Palette.surface)
                .border(1.dp, Palette.surfaceHi, RoundedCornerShape(36.dp)).padding(Dimen.xl),
            verticalArrangement = Arrangement.spacedBy(Dimen.m),
        ) {
            Box(Modifier.size(72.dp).clip(CircleShape).background(Palette.bg), contentAlignment = Alignment.Center) {
                Icon(icon, null, tint = iconTint, modifier = Modifier.size(40.dp))
            }
            Text(title, style = Type.titleM, color = titleColor)
            Text(body, style = Type.bodyM.copy(lineHeight = androidx.compose.ui.unit.TextUnit(40f, androidx.compose.ui.unit.TextUnitType.Sp)), color = Palette.sub)
            Spacer(Modifier.height(8.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(Dimen.s, Alignment.End)) {
                AppButton("취소", onDismiss, style = ButtonStyle.TONAL, modifier = Modifier.widthIn(min = 200.dp))
                AppButton(confirmLabel, onConfirm, style = confirmStyle, modifier = Modifier.widthIn(min = 260.dp))
            }
        }
    }
}
