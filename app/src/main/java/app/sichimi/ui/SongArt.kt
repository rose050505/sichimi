package app.sichimi.ui

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import kotlin.math.sin

/**
 * 곡마다 자체 제작 아트(무대 배경). 단순 도형만 쓴다. 움직이지 않는다.
 * 아리랑 = 겹친 산 능선과 달, Destroy = 깨진 유리 조각, Wade = 물결. 그 밖의 곡은 음표 무늬.
 */
@Composable
fun SongArt(songId: String?, modifier: Modifier) {
    Canvas(modifier) {
        when (songId) {
            "01-arirang" -> arirang()
            "02-destroy" -> shards()
            "03-wade" -> waves()
            else -> drawRect(Brush.verticalGradient(listOf(Color(0xFF2A1C2E), Color(0xFF1E1420))))
        }
    }
}

private fun DrawScope.arirang() {
    val w = size.width; val h = size.height
    drawRect(Brush.verticalGradient(listOf(Color(0xFF1B1A3A), Color(0xFF3A1E3F), Color(0xFF1E1420))))
    drawCircle(Brush.radialGradient(listOf(Color(0x55FFE3A3), Color(0x00FFE3A3)), Offset(w * 0.78f, h * 0.24f), h * 0.32f), h * 0.32f, Offset(w * 0.78f, h * 0.24f))
    drawCircle(Color(0xFFFFE9B8), h * 0.1f, Offset(w * 0.78f, h * 0.24f))
    val ridges = listOf(Color(0xFF4A2E57) to 0.52f, Color(0xFF3A2447) to 0.64f, Color(0xFF2A1A33) to 0.76f)
    ridges.forEachIndexed { i, (c, base) ->
        val p = Path().apply {
            moveTo(0f, h)
            lineTo(0f, h * base)
            var x = 0f
            var k = 0
            while (x <= w) {
                val y = h * (base - 0.08f * sin((x / w) * 6.0 + i * 1.7).toFloat() - 0.04f * sin((x / w) * 13.0 + i).toFloat())
                lineTo(x, y); x += w / 40; k++
            }
            lineTo(w, h); close()
        }
        drawPath(p, c)
    }
}

private fun DrawScope.shards() {
    val w = size.width; val h = size.height
    drawRect(Brush.linearGradient(listOf(Color(0xFF241226), Color(0xFF151018)), Offset(0f, 0f), Offset(w, h)))
    val c = Offset(w * 0.68f, h * 0.38f)
    val pts = listOf(0.0f to 0.05f, 0.2f to 0.0f, 0.42f to 0.12f, 0.6f to 0.0f, 0.85f to 0.08f, 1.0f to 0.3f, 1.0f to 0.62f, 0.9f to 1.0f,
        0.62f to 0.9f, 0.4f to 1.0f, 0.15f to 0.88f, 0.0f to 0.6f)
    pts.forEachIndexed { i, (x, y) ->
        val n = pts[(i + 1) % pts.size]
        val p = Path().apply { moveTo(c.x, c.y); lineTo(w * x, h * y); lineTo(w * n.first, h * n.second); close() }
        val col = if (i % 3 == 0) Color(0x33FF5FA8) else if (i % 3 == 1) Color(0x22FFB547) else Color(0x14FFFFFF)
        drawPath(p, col)
        drawLine(Color(0x55FFFFFF), c, Offset(w * x, h * y), strokeWidth = 2f)
    }
    drawCircle(Color(0x66FFFFFF), 6f, c)
}

private fun DrawScope.waves() {
    val w = size.width; val h = size.height
    drawRect(Brush.verticalGradient(listOf(Color(0xFF10233A), Color(0xFF1A1630), Color(0xFF1E1420))))
    for (i in 0 until 6) {
        val base = h * (0.45f + i * 0.09f)
        val p = Path().apply {
            moveTo(0f, base)
            var x = 0f
            while (x <= w) { lineTo(x, base + h * 0.025f * sin((x / w) * 9.0 + i * 0.9).toFloat()); x += w / 60 }
        }
        drawPath(p, Color(0xFF5FC8D8).copy(alpha = 0.10f + i * 0.05f), style = Stroke(width = 3f + i))
    }
}
