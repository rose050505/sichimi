package app.sichimi.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathBuilder
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.unit.dp
import kotlin.math.cos
import kotlin.math.sin

/**
 * 시치미 아이콘 한 벌. 24 격자에 단순한 도형으로 직접 그렸다(외부 아이콘 묶음을 쓰지 않음).
 * 채움(Filled)을 기본으로 하고, 선이 필요한 것은 굵기 2.2, 둥근 끝으로 통일한다. 색은 쓰는 쪽이 tint로 정한다.
 */
object AppIcons {
    private fun icon(name: String, block: ImageVector.Builder.() -> Unit): ImageVector =
        ImageVector.Builder(name, 24.dp, 24.dp, 24f, 24f).apply(block).build()

    private fun ImageVector.Builder.fill(type: PathFillType = PathFillType.NonZero, p: PathBuilder.() -> Unit) =
        path(fill = SolidColor(Color.White), pathFillType = type, pathBuilder = p)

    private fun ImageVector.Builder.line(p: PathBuilder.() -> Unit) =
        path(stroke = SolidColor(Color.White), strokeLineWidth = 2.2f, strokeLineCap = StrokeCap.Round,
            strokeLineJoin = StrokeJoin.Round, pathBuilder = p)

    private fun PathBuilder.circle(cx: Float, cy: Float, r: Float) {
        moveTo(cx - r, cy)
        arcTo(r, r, 0f, false, true, cx + r, cy)
        arcTo(r, r, 0f, false, true, cx - r, cy)
        close()
    }

    private fun PathBuilder.rect(x: Float, y: Float, w: Float, h: Float) {
        moveTo(x, y); lineTo(x + w, y); lineTo(x + w, y + h); lineTo(x, y + h); close()
    }

    private fun PathBuilder.round(x: Float, y: Float, w: Float, h: Float, r: Float) {
        moveTo(x + r, y)
        lineTo(x + w - r, y); arcTo(r, r, 0f, false, true, x + w, y + r)
        lineTo(x + w, y + h - r); arcTo(r, r, 0f, false, true, x + w - r, y + h)
        lineTo(x + r, y + h); arcTo(r, r, 0f, false, true, x, y + h - r)
        lineTo(x, y + r); arcTo(r, r, 0f, false, true, x + r, y)
        close()
    }

    val Play = icon("play") { fill { moveTo(7.5f, 4.5f); lineTo(19.5f, 12f); lineTo(7.5f, 19.5f); close() } }
    val Pause = icon("pause") { fill { round(5.5f, 4.5f, 4.6f, 15f, 1.2f); round(13.9f, 4.5f, 4.6f, 15f, 1.2f) } }
    val Next = icon("next") { fill { moveTo(4.5f, 4.5f); lineTo(15f, 12f); lineTo(4.5f, 19.5f); close(); round(16.4f, 4.5f, 3.1f, 15f, 1f) } }
    val Previous = icon("previous") { fill { moveTo(19.5f, 4.5f); lineTo(9f, 12f); lineTo(19.5f, 19.5f); close(); round(4.5f, 4.5f, 3.1f, 15f, 1f) } }
    val Stop = icon("stop") { fill { round(6f, 6f, 12f, 12f, 2f) } }

    val Note = icon("note") {
        fill {
            circle(8.6f, 17.2f, 3.6f)
            round(10.6f, 3.5f, 2.4f, 14f, 1f)
            moveTo(12.4f, 3.5f); lineTo(18.5f, 5.6f); lineTo(18.5f, 9.4f); lineTo(12.4f, 7.4f); close()
        }
    }
    val Queue = icon("queue") {
        line { moveTo(4f, 6.5f); lineTo(16f, 6.5f); moveTo(4f, 12f); lineTo(16f, 12f); moveTo(4f, 17.5f); lineTo(11f, 17.5f) }
        fill { moveTo(14.5f, 14.5f); lineTo(20.5f, 17.5f); lineTo(14.5f, 20.5f); close() }
    }
    val Chart = icon("chart") { fill { round(4f, 12.5f, 3.6f, 7.5f, 1f); round(10.2f, 8f, 3.6f, 12f, 1f); round(16.4f, 4f, 3.6f, 16f, 1f) } }
    val Trophy = icon("trophy") {
        fill {
            moveTo(7f, 4f); lineTo(17f, 4f); lineTo(17f, 9.5f)
            curveTo(17f, 12.5f, 14.8f, 14.5f, 12f, 14.5f); curveTo(9.2f, 14.5f, 7f, 12.5f, 7f, 9.5f); close()
            rect(10.9f, 14f, 2.2f, 3.5f)
            round(7.5f, 17.3f, 9f, 2.7f, 1f)
        }
        line { moveTo(7f, 6.5f); lineTo(4.5f, 6.5f); lineTo(4.5f, 8f); curveTo(4.5f, 9.6f, 5.6f, 10.6f, 7.2f, 10.8f)
            moveTo(17f, 6.5f); lineTo(19.5f, 6.5f); lineTo(19.5f, 8f); curveTo(19.5f, 9.6f, 18.4f, 10.6f, 16.8f, 10.8f) }
    }
    val Gear = icon("gear") {
        fill(PathFillType.EvenOdd) {
            val n = 32
            for (k in 0..n) {
                val a = Math.toRadians(k * 360.0 / n - 5.6).toFloat()
                val r = if ((k / 2) % 2 == 0) 9.6f else 7.4f
                val x = 12f + r * cos(a); val y = 12f + r * sin(a)
                if (k == 0) moveTo(x, y) else lineTo(x, y)
            }
            close()
            circle(12f, 12f, 3.2f)
        }
    }
    val Mic = icon("mic") {
        fill { round(8.8f, 2.8f, 6.4f, 11.6f, 3.2f) }
        line { moveTo(5.8f, 11f); curveTo(5.8f, 14.8f, 8.6f, 17.5f, 12f, 17.5f); curveTo(15.4f, 17.5f, 18.2f, 14.8f, 18.2f, 11f)
            moveTo(12f, 17.5f); lineTo(12f, 21f) }
    }
    val Headphones = icon("headphones") {
        line { moveTo(4.2f, 14.5f); lineTo(4.2f, 12f); curveTo(4.2f, 7.6f, 7.7f, 4.2f, 12f, 4.2f); curveTo(16.3f, 4.2f, 19.8f, 7.6f, 19.8f, 12f); lineTo(19.8f, 14.5f) }
        fill { round(3f, 13f, 5.2f, 7.6f, 1.8f); round(15.8f, 13f, 5.2f, 7.6f, 1.8f) }
    }
    val Upload = icon("upload") {
        line { moveTo(12f, 15.5f); lineTo(12f, 4.5f); moveTo(7f, 9.5f); lineTo(12f, 4.5f); lineTo(17f, 9.5f); moveTo(5f, 19.5f); lineTo(19f, 19.5f) }
    }
    val Trash = icon("trash") {
        fill { round(4.5f, 5.2f, 15f, 2.4f, 1f); round(9.4f, 3f, 5.2f, 2.8f, 1f)
            moveTo(6.3f, 9f); lineTo(17.7f, 9f); lineTo(16.7f, 20.5f); lineTo(7.3f, 20.5f); close() }
    }
    val Plus = icon("plus") { line { moveTo(12f, 5f); lineTo(12f, 19f); moveTo(5f, 12f); lineTo(19f, 12f) } }
    val Minus = icon("minus") { line { moveTo(5f, 12f); lineTo(19f, 12f) } }
    val Check = icon("check") { line { moveTo(5f, 12.5f); lineTo(10f, 17.5f); lineTo(19f, 7f) } }
    val Edit = icon("edit") {
        fill { moveTo(4f, 20f); lineTo(4.8f, 16f); lineTo(15.6f, 5.2f); lineTo(18.8f, 8.4f); lineTo(8f, 19.2f); close()
            moveTo(16.8f, 4f); lineTo(18.2f, 2.6f); curveTo(18.8f, 2f, 19.6f, 2f, 20.2f, 2.6f); lineTo(21.4f, 3.8f)
            curveTo(22f, 4.4f, 22f, 5.2f, 21.4f, 5.8f); lineTo(20f, 7.2f); close() }
    }
    val Eye = icon("eye") {
        line { moveTo(2.5f, 12f); curveTo(5f, 7.6f, 8.4f, 5.5f, 12f, 5.5f); curveTo(15.6f, 5.5f, 19f, 7.6f, 21.5f, 12f)
            curveTo(19f, 16.4f, 15.6f, 18.5f, 12f, 18.5f); curveTo(8.4f, 18.5f, 5f, 16.4f, 2.5f, 12f); close() }
        fill { circle(12f, 12f, 3.2f) }
    }
    val Bolt = icon("bolt") { fill { moveTo(13.5f, 2f); lineTo(5f, 13.5f); lineTo(11f, 13.5f); lineTo(10f, 22f); lineTo(19f, 10f); lineTo(13f, 10f); close() } }
    val Clock = icon("clock") {
        line { moveTo(3.5f, 12f); arcTo(8.5f, 8.5f, 0f, true, true, 20.5f, 12f); arcTo(8.5f, 8.5f, 0f, true, true, 3.5f, 12f)
            moveTo(12f, 7f); lineTo(12f, 12f); lineTo(15.5f, 14f) }
    }
    val Info = icon("info") {
        fill(PathFillType.EvenOdd) { circle(12f, 12f, 9.5f); round(10.8f, 10.2f, 2.4f, 7.4f, 1f); circle(12f, 7.4f, 1.4f) }
    }
    val Lock = icon("lock") {
        fill { round(5f, 10f, 14f, 11f, 2.5f) }
        line { moveTo(8f, 10f); lineTo(8f, 7.5f); curveTo(8f, 5.2f, 9.8f, 3.5f, 12f, 3.5f); curveTo(14.2f, 3.5f, 16f, 5.2f, 16f, 7.5f); lineTo(16f, 10f) }
    }
    val Car = icon("car") {
        fill { moveTo(5.5f, 10.5f); lineTo(7.2f, 5.8f); curveTo(7.5f, 5f, 8.2f, 4.5f, 9f, 4.5f); lineTo(15f, 4.5f)
            curveTo(15.8f, 4.5f, 16.5f, 5f, 16.8f, 5.8f); lineTo(18.5f, 10.5f); close()
            round(3.5f, 10f, 17f, 7f, 2f); round(5f, 16f, 3.6f, 3.6f, 1.2f); round(15.4f, 16f, 3.6f, 3.6f, 1.2f) }
    }
}
