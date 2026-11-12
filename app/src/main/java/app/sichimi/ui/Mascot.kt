package app.sichimi.ui

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.withFrameMillis
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.translate
import app.sichimi.gate.MascotLook
import app.sichimi.gate.MascotPose
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * 시치미: 이 앱의 자체 캐릭터. 둥근 떡 모양 몸에 반쯤 감은 눈을 옆으로 흘기고 입을 오므린, "나 아무것도 안 했는데" 표정.
 * 단순한 도형으로만 그렸고 기존 캐릭터·마스코트를 본뜨지 않았다.
 *
 * 단계(1~4): 흥얼 씨앗(작은 몸 + 새싹) → 콧노래(팔, 음표) → 골목 가수(마이크, 목도리) → 무대의 주인공(별 머리장식, 조명).
 * 자세: SINGING(흥얼), HIDING(무대 끝 아래로 숨는 중, [hide] 0~1), RELIEVED(땀 한 방울, 눈 감고 안도), AWAY(그리지 않음).
 * 모습: 우비(와이퍼), 밤(달·따뜻한 조명), 바람(창문이 조금 열림).
 * 움직임은 [frameMs]를 그리기 단계에서만 읽는다(화면 구성·접근성 트리를 흔들지 않음). 주차 중에만 [frameMs]를 넘긴다.
 */
@Composable
fun Mascot(
    stage: Int,
    pose: MascotPose,
    look: MascotLook,
    modifier: Modifier,
    hide: () -> Float = { 0f },
    frameMs: State<Long>? = null,
) {
    if (pose == MascotPose.AWAY) return
    Canvas(modifier) {
        val t = frameMs?.value ?: 0L
        drawMascot(stage.coerceIn(1, 4), pose, look, hide(), t)
    }
}

/** 주차 화면에서만 쓰는 프레임 시계(ms). 그리기 단계에서만 읽는다. */
@Composable
fun rememberFrameClock(enabled: Boolean): State<Long> {
    val s = remember { mutableLongStateOf(0L) }
    LaunchedEffect(enabled) {
        if (!enabled) return@LaunchedEffect
        val start = withFrameMillis { it }
        while (true) withFrameMillis { s.longValue = it - start }
    }
    return s
}

private val Body = Color(0xFFFFE6C4)
private val BodyShade = Color(0xFFF2CFA0)
private val Ink = Color(0xFF3B2A2A)
private val Blush = Color(0xFFFF9AA8)
private val Leaf = Color(0xFF7ED38B)
private val Rain = Color(0xFFFFD23F)
private val MicGray = Color(0xFF8C8F99)

fun DrawScope.drawMascot(stage: Int, pose: MascotPose, look: MascotLook, hide: Float, t: Long) {
    val w = size.width
    val h = size.height
    val unit = minOf(w, h)
    val scale = when (stage) { 1 -> 0.62f; 2 -> 0.76f; 3 -> 0.88f; else -> 1f }
    val bodyW = unit * 0.62f * scale
    val bodyH = unit * 0.56f * scale
    val ground = h * 0.94f
    val cx = w / 2f
    // 흥얼거리는 작은 흔들림(노래할 때만, 주차 중에만 t가 흐름)
    val bob = if (pose == MascotPose.SINGING) sin(t / 380.0).toFloat() * unit * 0.012f else 0f
    val sway = if (pose == MascotPose.SINGING) sin(t / 760.0).toFloat() * 3f else 0f

    // 밤: 따뜻한 무대 조명과 달
    if (look.night) {
        drawCircle(Brush.radialGradient(listOf(Color(0x66FFB547), Color(0x00FFB547)), Offset(cx, ground - bodyH * 0.6f), unit * 0.55f),
            unit * 0.55f, Offset(cx, ground - bodyH * 0.6f))
        val mc = Offset(w * 0.86f, h * 0.16f)
        drawCircle(Color(0xFFFFF1C9), unit * 0.07f, mc)
        drawCircle(Color(0xFF1E1420), unit * 0.07f, mc + Offset(unit * 0.035f, -unit * 0.02f))
    }
    // 4단계: 뒤에서 비추는 조명 원뿔
    if (stage >= 4) {
        val p = Path().apply {
            moveTo(cx - unit * 0.08f, 0f); lineTo(cx + unit * 0.08f, 0f)
            lineTo(cx + bodyW * 0.95f, ground); lineTo(cx - bodyW * 0.95f, ground); close()
        }
        drawPath(p, Brush.verticalGradient(listOf(Color(0x55FF5FA8), Color(0x10FF5FA8)), 0f, ground))
    }

    // 숨기: 무대 끝(ground) 아래로 내려가며 잘린다
    val drop = hide * (bodyH * 1.6f)
    clipRect(0f, 0f, w, ground + 2f) {
        translate(0f, drop + bob) {
            rotate(sway, Offset(cx, ground)) {
                drawFigure(stage, pose, look, cx, ground, bodyW, bodyH, unit, t)
            }
        }
    }
    // 무대 끝 선
    drawLine(Color(0x55FFFFFF), Offset(w * 0.08f, ground + 1f), Offset(w * 0.92f, ground + 1f), strokeWidth = unit * 0.008f, cap = StrokeCap.Round)

    // 비: 빗줄기
    if (look.raincoat) {
        for (i in 0 until 9) {
            val x = w * (0.08f + i * 0.105f)
            val y0 = ((t / 6 + i * 37) % 100) / 100f * h * 0.5f
            drawLine(Color(0x99BFD9FF), Offset(x, y0), Offset(x - unit * 0.02f, y0 + unit * 0.07f), strokeWidth = unit * 0.008f, cap = StrokeCap.Round)
        }
    }
    // 바람: 흩날리는 선
    if (look.breeze) {
        for (i in 0 until 3) {
            val y = h * (0.25f + i * 0.12f)
            val x0 = w * 0.05f + (sin(t / 500.0 + i).toFloat() * unit * 0.02f)
            val p = Path().apply { moveTo(x0, y); quadraticTo(x0 + unit * 0.1f, y - unit * 0.04f, x0 + unit * 0.2f, y) }
            drawPath(p, Color(0x88FFFFFF), style = Stroke(unit * 0.01f, cap = StrokeCap.Round))
        }
    }
}

private fun DrawScope.drawFigure(stage: Int, pose: MascotPose, look: MascotLook, cx: Float, ground: Float, bw: Float, bh: Float, unit: Float, t: Long) {
    val top = ground - bh
    val headC = Offset(cx, top + bh * 0.48f)
    // 그림자
    drawOval(Color(0x33000000), Offset(cx - bw * 0.45f, ground - bh * 0.06f), Size(bw * 0.9f, bh * 0.12f))
    // 몸(떡 모양: 아래가 넓은 둥근 사각)
    val body = Path().apply {
        moveTo(cx - bw * 0.5f, ground - bh * 0.08f)
        cubicTo(cx - bw * 0.56f, top + bh * 0.25f, cx - bw * 0.3f, top, cx, top)
        cubicTo(cx + bw * 0.3f, top, cx + bw * 0.56f, top + bh * 0.25f, cx + bw * 0.5f, ground - bh * 0.08f)
        quadraticTo(cx, ground + bh * 0.04f, cx - bw * 0.5f, ground - bh * 0.08f)
        close()
    }
    drawPath(body, if (look.raincoat) Rain else Body)
    if (!look.raincoat) drawPath(body, Brush.verticalGradient(listOf(Color(0x00F2CFA0), BodyShade), top + bh * 0.5f, ground))
    // 우비: 얼굴 둘레만 살색으로 남기고 모자 테두리
    if (look.raincoat) {
        drawOval(Body, Offset(cx - bw * 0.36f, top + bh * 0.2f), Size(bw * 0.72f, bh * 0.56f))
        drawArc(Color(0xFFE0B21F), 200f, 140f, false, Offset(cx - bw * 0.42f, top + bh * 0.12f), Size(bw * 0.84f, bh * 0.66f), style = Stroke(unit * 0.018f, cap = StrokeCap.Round))
    }
    // 새싹(1단계) / 머리 한 가닥(2~3단계, 바람이면 기울어짐) / 별 장식(4단계)
    when {
        stage == 1 -> {
            drawLine(Leaf, Offset(cx, top), Offset(cx, top - bh * 0.16f), strokeWidth = unit * 0.014f, cap = StrokeCap.Round)
            drawOval(Leaf, Offset(cx, top - bh * 0.24f), Size(bw * 0.22f, bh * 0.12f))
            drawOval(Leaf, Offset(cx - bw * 0.2f, top - bh * 0.2f), Size(bw * 0.2f, bh * 0.1f))
        }
        stage >= 4 -> drawStar(Offset(cx + bw * 0.06f, top - bh * 0.02f), unit * 0.06f, Color(0xFFFFB547))
        !look.raincoat -> {
            val lean = if (look.breeze) bw * 0.22f else bw * 0.08f
            val p = Path().apply { moveTo(cx, top + 2f); quadraticTo(cx + lean * 0.4f, top - bh * 0.18f, cx + lean, top - bh * 0.14f) }
            drawPath(p, Ink, style = Stroke(unit * 0.012f, cap = StrokeCap.Round))
        }
    }
    // 목도리(3단계 이상)
    if (stage >= 3 && !look.raincoat) {
        drawRoundRect(Color(0xFFFF5FA8), Offset(cx - bw * 0.44f, top + bh * 0.80f), Size(bw * 0.88f, bh * 0.06f), androidx.compose.ui.geometry.CornerRadius(bh * 0.03f))
    }

    // 얼굴: 시치미 떼는 표정
    val eyeY = headC.y - bh * 0.04f
    val eyeDx = bw * 0.17f
    val look2 = bw * 0.035f // 눈동자가 옆(오른쪽)을 본다
    if (pose == MascotPose.RELIEVED) {
        // 눈 감고 안도(^ ^)
        for (sx in listOf(-1f, 1f)) {
            drawArc(Ink, 200f, 140f, false, Offset(cx + sx * eyeDx - bw * 0.06f, eyeY - bh * 0.02f), Size(bw * 0.12f, bh * 0.1f), style = Stroke(unit * 0.012f, cap = StrokeCap.Round))
        }
    } else {
        for (sx in listOf(-1f, 1f)) {
            val c = Offset(cx + sx * eyeDx, eyeY)
            drawCircle(Ink, bw * 0.045f, c + Offset(look2, 0f))
            // 반쯤 내려온 눈꺼풀(무심한 척)
            drawLine(Ink, c + Offset(-bw * 0.07f, -bh * 0.035f), c + Offset(bw * 0.07f, -bh * 0.045f), strokeWidth = unit * 0.012f, cap = StrokeCap.Round)
        }
    }
    // 볼
    drawOval(Blush.copy(alpha = 0.55f), Offset(cx - bw * 0.33f, eyeY + bh * 0.06f), Size(bw * 0.13f, bh * 0.06f))
    drawOval(Blush.copy(alpha = 0.55f), Offset(cx + bw * 0.2f, eyeY + bh * 0.06f), Size(bw * 0.13f, bh * 0.06f))
    // 입: 노래할 때 오므린 동그라미(흥얼), 숨을 때·안도할 때는 옆으로 다문 입
    val mouth = Offset(cx + bw * 0.04f, eyeY + bh * 0.16f)
    if (pose == MascotPose.SINGING) {
        val open = 0.8f + 0.2f * sin(t / 240.0).toFloat()
        drawOval(Ink, Offset(mouth.x - bw * 0.035f, mouth.y - bh * 0.03f * open), Size(bw * 0.07f, bh * 0.06f * open))
    } else {
        drawLine(Ink, mouth + Offset(-bw * 0.06f, 0f), mouth + Offset(bw * 0.05f, -bh * 0.01f), strokeWidth = unit * 0.012f, cap = StrokeCap.Round)
    }
    // 땀 한 방울(안도)
    if (pose == MascotPose.RELIEVED) {
        val d = Offset(cx + bw * 0.38f, top + bh * 0.3f)
        val p = Path().apply { moveTo(d.x, d.y - bh * 0.08f); quadraticTo(d.x + bw * 0.07f, d.y + bh * 0.02f, d.x, d.y + bh * 0.04f); quadraticTo(d.x - bw * 0.07f, d.y + bh * 0.02f, d.x, d.y - bh * 0.08f) }
        drawPath(p, Color(0xFF8FD3FF))
    }
    // 팔(2단계 이상): 노래할 땐 한 손을 들고, 안도할 땐 이마를 닦는다
    if (stage >= 2) {
        val armY = top + bh * 0.7f
        drawLine(if (look.raincoat) Rain else Body, Offset(cx - bw * 0.48f, armY), Offset(cx - bw * 0.62f, armY + bh * 0.1f), strokeWidth = unit * 0.035f, cap = StrokeCap.Round)
        val handEnd = when {
            pose == MascotPose.RELIEVED -> Offset(cx + bw * 0.28f, top + bh * 0.22f)
            stage >= 3 -> Offset(cx + bw * 0.36f, armY - bh * 0.22f)
            else -> Offset(cx + bw * 0.62f, armY + bh * 0.08f)
        }
        drawLine(if (look.raincoat) Rain else Body, Offset(cx + bw * 0.46f, armY), handEnd, strokeWidth = unit * 0.035f, cap = StrokeCap.Round)
        // 마이크(3단계 이상, 노래할 때)
        if (stage >= 3 && pose == MascotPose.SINGING) {
            drawLine(MicGray, handEnd, handEnd + Offset(-bw * 0.06f, -bh * 0.14f), strokeWidth = unit * 0.022f, cap = StrokeCap.Round)
            drawCircle(Color(0xFFD9DBE1), unit * 0.03f, handEnd + Offset(-bw * 0.08f, -bh * 0.19f))
        }
    }
    // 음표(2단계 이상, 노래할 때): 위로 떠오른다
    if (stage >= 2 && pose == MascotPose.SINGING) {
        for (i in 0 until 2) {
            val ph = ((t / 1800.0 + i * 0.5) % 1.0).toFloat()
            val p = Offset(cx + bw * (0.55f + i * 0.12f), top + bh * 0.2f - ph * bh * 0.5f)
            val a = (1f - ph).coerceIn(0f, 1f)
            drawCircle(Color(0xFFFF5FA8).copy(alpha = a), unit * 0.022f, p)
            drawLine(Color(0xFFFF5FA8).copy(alpha = a), p + Offset(unit * 0.02f, 0f), p + Offset(unit * 0.02f, -unit * 0.07f), strokeWidth = unit * 0.008f)
        }
    }
}

private fun DrawScope.drawStar(c: Offset, r: Float, color: Color) {
    val p = Path()
    for (i in 0 until 10) {
        val a = -PI / 2 + i * PI / 5
        val rr = if (i % 2 == 0) r else r * 0.45f
        val x = c.x + rr * cos(a).toFloat(); val y = c.y + rr * sin(a).toFloat()
        if (i == 0) p.moveTo(x, y) else p.lineTo(x, y)
    }
    p.close()
    drawPath(p, color)
}
