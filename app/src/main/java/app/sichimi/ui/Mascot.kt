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
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.translate
import app.sichimi.gate.MascotLook
import app.sichimi.gate.MascotPose
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * 시치미: 이 앱의 자체 캐릭터. 둥근 떡 모양 몸, 머리 위에 차 지붕 상어지느러미 안테나(시치미만의 표식),
 * 몸을 대각선으로 가로지르는 안전벨트. 기본 표정은 "딴청": 눈은 옆으로 흘끗, 입은 휘파람 부는 작은 동그라미와 음표 하나.
 * 귀·꼬리 같은 동물 특징 없이 단순한 도형으로만 그렸고 기존 캐릭터·마스코트를 본뜨지 않았다.
 *
 * 단계(1~4, 4단계 대비 크기 60·73·86·100%): 흥얼 씨앗(새싹) → 콧노래(손에 쥔 마이크) → 골목 가수(+ 반짝이 무대 옷)
 * → 무대의 주인공(+ 조명과 마이크 스탠드). 그림자만 봐도 단계가 구분되게 소품이 하나씩 늘어난다.
 * 자세: SINGING(딴청 휘파람), HIDING(계기판 선 아래로, [hide]=1이면 안테나와 두 눈만 빼꼼), RELIEVED(눈 감고 땀 닦기), AWAY(그리지 않음).
 * 모습: 우비(와이퍼), 밤(달·따뜻한 조명), 바람(창문이 조금 열림: 안테나가 바람 방향으로 휘고 눈을 찡그림).
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
private val Fin = Color(0xFF3A3F4B)
private val FinHi = Color(0xFF6B7385)
private val Belt = Color(0xFF2F3A4A)
private val BeltStitch = Color(0xFF6E7C90)
private val Metal = Color(0xFFC9CED6)
private val MicBody = Color(0xFF232B3D)
private val MicHead = Color(0xFF4A5468)
private val Sequin = Color(0xFF6A2C86)
private val Note = Color(0xFFFF5FA8)

fun DrawScope.drawMascot(stage: Int, pose: MascotPose, look: MascotLook, hide: Float, t: Long) {
    val w = size.width
    val h = size.height
    val unit = minOf(w, h)
    // 단계마다 확실히 자란다(1단계 = 4단계의 60%)
    val scale = when (stage) { 1 -> 0.60f; 2 -> 0.73f; 3 -> 0.86f; else -> 1f }
    val bodyW = unit * 0.56f * scale
    val bodyH = unit * 0.50f * scale
    val ground = h * 0.94f
    val cx = if (stage >= 4) w * 0.44f else w / 2f
    val bob = if (pose == MascotPose.SINGING) sin(t / 380.0).toFloat() * unit * 0.010f else 0f
    val sway = if (pose == MascotPose.SINGING) sin(t / 760.0).toFloat() * 2.5f else 0f

    if (look.night) {
        drawCircle(Brush.radialGradient(listOf(Color(0x66FFB547), Color(0x00FFB547)), Offset(cx, ground - bodyH * 0.6f), unit * 0.55f),
            unit * 0.55f, Offset(cx, ground - bodyH * 0.6f))
        val mc = Offset(w * 0.86f, h * 0.16f)
        drawCircle(Color(0xFFFFF1C9), unit * 0.07f, mc)
        drawCircle(Color(0xFF1E1420), unit * 0.07f, mc + Offset(unit * 0.035f, -unit * 0.02f))
    }
    // 4단계: 위에서 비추는 조명 원뿔
    if (stage >= 4) {
        val p = Path().apply {
            moveTo(cx - unit * 0.07f, 0f); lineTo(cx + unit * 0.07f, 0f)
            lineTo(cx + bodyW * 0.95f, ground); lineTo(cx - bodyW * 0.95f, ground); close()
        }
        drawPath(p, Brush.verticalGradient(listOf(Color(0x66FF5FA8), Color(0x12FF5FA8)), 0f, ground))
        drawCircle(Color(0xFFFFE3F0), unit * 0.03f, Offset(cx, unit * 0.02f))
    }

    // 숨기: 계기판 선(ground) 아래로. hide = 1이면 안테나와 두 눈만 선 위에 남는다.
    val top0 = ground - bodyH
    val eyeY0 = top0 + bodyH * 0.40f
    val peekDrop = (ground - (eyeY0 + bodyH * 0.075f)).coerceAtLeast(0f)
    val drop = hide.coerceIn(0f, 1f) * peekDrop
    clipRect(0f, 0f, w, ground + 1f) {
        translate(0f, drop + bob) {
            rotate(sway, Offset(cx, ground)) {
                drawFigure(stage, pose, look, cx, ground, bodyW, bodyH, unit, t)
            }
        }
    }
    // 계기판 선(무대 끝)
    drawLine(Color(0x66FFFFFF), Offset(w * 0.06f, ground + 1f), Offset(w * 0.94f, ground + 1f), strokeWidth = unit * 0.008f, cap = StrokeCap.Round)

    if (look.raincoat) {
        for (i in 0 until 9) {
            val x = w * (0.08f + i * 0.105f)
            val y0 = ((t / 6 + i * 37) % 100) / 100f * h * 0.5f
            drawLine(Color(0x99BFD9FF), Offset(x, y0), Offset(x - unit * 0.02f, y0 + unit * 0.07f), strokeWidth = unit * 0.008f, cap = StrokeCap.Round)
        }
    }
    // 바람: 왼쪽에서 오른쪽으로 부는 선
    if (look.breeze) {
        for (i in 0 until 3) {
            val y = h * (0.22f + i * 0.11f)
            val x0 = w * 0.04f + (sin(t / 500.0 + i).toFloat() * unit * 0.02f)
            val p = Path().apply { moveTo(x0, y); quadraticTo(x0 + unit * 0.1f, y - unit * 0.04f, x0 + unit * 0.2f, y) }
            drawPath(p, Color(0x88FFFFFF), style = Stroke(unit * 0.01f, cap = StrokeCap.Round))
        }
    }
}

private fun DrawScope.drawFigure(stage: Int, pose: MascotPose, look: MascotLook, cx: Float, ground: Float, bw: Float, bh: Float, unit: Float, t: Long) {
    val top = ground - bh
    val lw = unit * 0.012f
    // 숨을 때는 안테나와 얼굴만(팔·마이크·깃·스탠드·새싹은 그리지 않아 선 위로 삐져나오지 않게)
    val hiding = pose == MascotPose.HIDING
    drawOval(Color(0x33000000), Offset(cx - bw * 0.45f, ground - bh * 0.06f), Size(bw * 0.9f, bh * 0.12f))

    // 4단계: 마이크 스탠드(몸 오른쪽), 마이크는 스탠드 위
    val standX = cx + bw * 0.68f
    val standMic = Offset(standX - bw * 0.02f, top + bh * 0.30f)
    if (stage >= 4 && !hiding) {
        drawLine(Metal, Offset(standX, ground - 2f), Offset(standX, standMic.y + bh * 0.06f), strokeWidth = unit * 0.018f, cap = StrokeCap.Round)
        drawLine(Metal, Offset(standX - bw * 0.14f, ground - 2f), Offset(standX + bw * 0.14f, ground - 2f), strokeWidth = unit * 0.02f, cap = StrokeCap.Round)
    }
    // 3단계 이상: 무대 옷의 깃(어깨 밖으로 뾰족하게 뻗어 그림자로도 보임)
    if (stage >= 3 && !look.raincoat && !hiding) {
        for (sx in listOf(-1f, 1f)) {
            val p = Path().apply {
                moveTo(cx + sx * bw * 0.36f, top + bh * 0.52f)
                lineTo(cx + sx * bw * 0.66f, top + bh * 0.40f)
                lineTo(cx + sx * bw * 0.46f, top + bh * 0.68f); close()
            }
            drawPath(p, Sequin)
        }
    }

    val body = Path().apply {
        moveTo(cx - bw * 0.5f, ground - bh * 0.08f)
        cubicTo(cx - bw * 0.56f, top + bh * 0.25f, cx - bw * 0.3f, top, cx, top)
        cubicTo(cx + bw * 0.3f, top, cx + bw * 0.56f, top + bh * 0.25f, cx + bw * 0.5f, ground - bh * 0.08f)
        quadraticTo(cx, ground + bh * 0.04f, cx - bw * 0.5f, ground - bh * 0.08f)
        close()
    }
    drawPath(body, if (look.raincoat) Rain else Body)
    clipPath(body) {
        if (!look.raincoat) drawRect(Brush.verticalGradient(listOf(Color(0x00F2CFA0), BodyShade), top + bh * 0.5f, ground), Offset(cx - bw, top), Size(bw * 2, bh))
        // 3단계 이상: 반짝이 무대 옷(아랫몸)
        if (stage >= 3 && !look.raincoat) {
            drawRect(Sequin, Offset(cx - bw, top + bh * 0.60f), Size(bw * 2, bh))
            for (i in 0 until 14) {
                val sx = cx - bw * 0.45f + (i * 37 % 100) / 100f * bw * 0.9f
                val sy = top + bh * (0.66f + (i * 53 % 100) / 100f * 0.28f)
                val r = unit * (0.006f + 0.004f * ((sin(t / 300.0 + i).toFloat() + 1f) / 2f))
                drawCircle(if (i % 2 == 0) Color(0xFFFFD27A) else Color(0xFFFFFFFF), r, Offset(sx, sy))
            }
        }
        // 안전벨트: 왼쪽 어깨에서 오른쪽 허리로 대각선(차 앱다운 표식)
        val a = Offset(cx - bw * 0.62f, top + bh * 0.30f)
        val b = Offset(cx + bw * 0.62f, ground - bh * 0.02f)
        drawLine(Belt, a, b, strokeWidth = bh * 0.11f)
        drawLine(BeltStitch, a, b, strokeWidth = bh * 0.012f, pathEffect = androidx.compose.ui.graphics.PathEffect.dashPathEffect(floatArrayOf(bh * 0.04f, bh * 0.03f)))
        // 버클
        val bk = Offset(cx + bw * 0.30f, a.y + (b.y - a.y) * ((cx + bw * 0.30f - a.x) / (b.x - a.x)))
        drawRoundRect(Metal, Offset(bk.x - bh * 0.07f, bk.y - bh * 0.06f), Size(bh * 0.14f, bh * 0.12f), androidx.compose.ui.geometry.CornerRadius(bh * 0.03f))
        drawRoundRect(Belt, Offset(bk.x - bh * 0.035f, bk.y - bh * 0.025f), Size(bh * 0.07f, bh * 0.05f), androidx.compose.ui.geometry.CornerRadius(bh * 0.01f))
    }
    // 우비: 얼굴만 살색으로
    if (look.raincoat) {
        drawOval(Body, Offset(cx - bw * 0.34f, top + bh * 0.18f), Size(bw * 0.68f, bh * 0.48f))
        drawArc(Color(0xFFE0B21F), 200f, 140f, false, Offset(cx - bw * 0.40f, top + bh * 0.10f), Size(bw * 0.80f, bh * 0.60f), style = Stroke(unit * 0.016f, cap = StrokeCap.Round))
    }
    // 1단계: 새싹(안테나 옆)
    if (stage == 1 && !hiding) {
        drawLine(Leaf, Offset(cx - bw * 0.22f, top + bh * 0.06f), Offset(cx - bw * 0.26f, top - bh * 0.12f), strokeWidth = unit * 0.012f, cap = StrokeCap.Round)
        drawOval(Leaf, Offset(cx - bw * 0.26f, top - bh * 0.22f), Size(bw * 0.20f, bh * 0.11f))
        drawOval(Leaf, Offset(cx - bw * 0.44f, top - bh * 0.18f), Size(bw * 0.18f, bh * 0.09f))
    }
    // 상어지느러미 안테나(시치미 표식). 앞(오른쪽)은 완만한 경사, 끝은 뒤로. 바람이면 오른쪽(바람 방향)으로 휜다.
    val bend = if (look.breeze) bw * 0.30f + sin(t / 420.0).toFloat() * bw * 0.03f else 0f
    val baseL = Offset(cx - bw * 0.10f, top + bh * 0.05f)
    val baseR = Offset(cx + bw * 0.13f, top + bh * 0.06f)
    val tip = Offset(cx - bw * 0.07f + bend, top - bh * 0.20f)
    val fin = Path().apply {
        moveTo(baseR.x, baseR.y)
        quadraticTo(cx + bw * 0.05f + bend * 0.5f, top - bh * 0.10f, tip.x, tip.y)
        quadraticTo(cx - bw * 0.06f + bend * 0.3f, top - bh * 0.06f, baseL.x, baseL.y)
        close()
    }
    drawPath(fin, Fin)
    drawPath(Path().apply { moveTo(baseR.x - bw * 0.03f, baseR.y - bh * 0.03f); quadraticTo(cx + bw * 0.03f + bend * 0.5f, top - bh * 0.07f, tip.x + bw * 0.01f, tip.y + bh * 0.04f) },
        FinHi, style = Stroke(lw * 0.8f, cap = StrokeCap.Round))

    // 얼굴: 딴청
    val eyeY = top + bh * 0.40f
    val eyeDx = bw * 0.16f
    val faceX = cx + bw * 0.03f
    when {
        pose == MascotPose.RELIEVED -> for (sx in listOf(-1f, 1f)) {
            drawArc(Ink, 200f, 140f, false, Offset(faceX + sx * eyeDx - bw * 0.06f, eyeY - bh * 0.03f), Size(bw * 0.12f, bh * 0.09f), style = Stroke(lw, cap = StrokeCap.Round))
        }
        look.breeze -> for (sx in listOf(-1f, 1f)) {
            // 바람에 눈을 찡그림(> <)
            val c = Offset(faceX + sx * eyeDx, eyeY)
            drawLine(Ink, c + Offset(-bw * 0.05f, -bh * 0.035f), c + Offset(bw * 0.03f * sx, 0f), strokeWidth = lw, cap = StrokeCap.Round)
            drawLine(Ink, c + Offset(-bw * 0.05f, bh * 0.035f), c + Offset(bw * 0.03f * sx, 0f), strokeWidth = lw, cap = StrokeCap.Round)
        }
        else -> for (sx in listOf(-1f, 1f)) {
            // 흰자에 눈동자가 오른쪽 위로 쏠림: 흘끗(눈썹 없음)
            val c = Offset(faceX + sx * eyeDx, eyeY)
            drawOval(Color.White, Offset(c.x - bw * 0.065f, c.y - bh * 0.065f), Size(bw * 0.13f, bh * 0.13f))
            drawCircle(Ink, bw * 0.040f, c + Offset(bw * 0.03f, -bh * 0.018f))
            drawCircle(Color.White, bw * 0.012f, c + Offset(bw * 0.042f, -bh * 0.032f))
        }
    }
    drawOval(Blush.copy(alpha = 0.55f), Offset(faceX - bw * 0.32f, eyeY + bh * 0.09f), Size(bw * 0.12f, bh * 0.055f))
    drawOval(Blush.copy(alpha = 0.55f), Offset(faceX + bw * 0.20f, eyeY + bh * 0.09f), Size(bw * 0.12f, bh * 0.055f))
    // 입: 휘파람(작은 동그라미, 오른쪽으로 비켜 있음) / 안도(물결 한숨)
    val mouth = Offset(faceX + bw * 0.07f, eyeY + bh * 0.17f)
    if (pose == MascotPose.RELIEVED) {
        val p = Path().apply { moveTo(mouth.x - bw * 0.07f, mouth.y); quadraticTo(mouth.x - bw * 0.035f, mouth.y - bh * 0.02f, mouth.x, mouth.y); quadraticTo(mouth.x + bw * 0.035f, mouth.y + bh * 0.02f, mouth.x + bw * 0.07f, mouth.y) }
        drawPath(p, Ink, style = Stroke(lw, cap = StrokeCap.Round))
    } else {
        drawCircle(Ink, bw * 0.032f, mouth, style = Stroke(lw))
        // 휘파람 음표 하나(노래할 때만 떠오름)
        if (pose == MascotPose.SINGING) {
            val ph = ((t / 2000.0) % 1.0).toFloat()
            // 입 오른쪽 아래에서 몸 밖 오른쪽으로 비켜 떠오른다(눈을 가리지 않게)
            val p = mouth + Offset(bw * (0.30f + ph * 0.12f), bh * (0.06f - ph * 0.20f))
            val a = (1f - ph * 0.8f).coerceIn(0f, 1f)
            drawNote(p, unit * 0.022f, Note.copy(alpha = a))
        }
    }
    if (pose == MascotPose.RELIEVED) {
        val d = Offset(cx + bw * 0.40f, top + bh * 0.28f)
        val p = Path().apply { moveTo(d.x, d.y - bh * 0.08f); quadraticTo(d.x + bw * 0.07f, d.y + bh * 0.02f, d.x, d.y + bh * 0.04f); quadraticTo(d.x - bw * 0.07f, d.y + bh * 0.02f, d.x, d.y - bh * 0.08f) }
        drawPath(p, Color(0xFF8FD3FF))
    }

    // 팔과 마이크
    val skin = if (look.raincoat) Rain else Body
    val armW = unit * 0.034f
    val shoulderL = Offset(cx - bw * 0.46f, top + bh * 0.68f)
    val shoulderR = Offset(cx + bw * 0.44f, top + bh * 0.66f)
    // 왼팔: 안도할 때는 이마를 닦고, 아니면 옆으로
    // 안도: 몸 바깥쪽을 따라 올라가 관자놀이를 닦는다(얼굴을 가로지르지 않게)
    val leftHand = if (pose == MascotPose.RELIEVED) Offset(cx - bw * 0.50f, top + bh * 0.26f) else Offset(cx - bw * 0.64f, top + bh * 0.80f)
    if (hiding) return
    if (stage >= 2 || pose == MascotPose.RELIEVED) {
        drawLine(skin, shoulderL, leftHand, strokeWidth = armW, cap = StrokeCap.Round)
    }
    if (stage >= 2) {
        val micAt = if (stage >= 4) standMic else Offset(cx + bw * 0.60f, top + bh * 0.42f)
        val hand = if (stage >= 4) standMic + Offset(-bw * 0.02f, bh * 0.14f) else micAt + Offset(-bw * 0.03f, bh * 0.20f)
        drawLine(skin, shoulderR, hand, strokeWidth = armW, cap = StrokeCap.Round)
        // 마이크: 몸 색과 대비되는 짙은 남색 손잡이와 회색 머리, 크게
        val handleEnd = micAt + Offset(-bw * 0.03f, bh * 0.30f)
        drawLine(MicBody, micAt + Offset(0f, bh * 0.05f), handleEnd, strokeWidth = unit * 0.034f, cap = StrokeCap.Round)
        drawCircle(MicHead, unit * 0.050f, micAt)
        drawCircle(Color(0xFF6E7890), unit * 0.050f, micAt, style = Stroke(unit * 0.006f))
        drawLine(Color(0xFF2B3244), micAt + Offset(-unit * 0.03f, 0f), micAt + Offset(unit * 0.03f, 0f), strokeWidth = unit * 0.004f)
        drawLine(Color(0xFF2B3244), micAt + Offset(0f, -unit * 0.03f), micAt + Offset(0f, unit * 0.03f), strokeWidth = unit * 0.004f)
        drawCircle(Color(0x99FFFFFF), unit * 0.012f, micAt + Offset(-unit * 0.018f, -unit * 0.02f))
        drawCircle(skin, armW * 0.75f, hand) // 쥔 손
    }
}

private fun DrawScope.drawNote(p: Offset, r: Float, color: Color) {
    drawOval(color, Offset(p.x - r, p.y - r * 0.75f), Size(r * 2, r * 1.5f))
    drawLine(color, p + Offset(r * 0.9f, 0f), p + Offset(r * 0.9f, -r * 3.2f), strokeWidth = r * 0.35f)
    drawLine(color, p + Offset(r * 0.9f, -r * 3.2f), p + Offset(r * 2.0f, -r * 2.5f), strokeWidth = r * 0.35f, cap = StrokeCap.Round)
}
