package app.sichimi.ui

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathOperation
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import kotlin.math.min
import kotlin.math.sin

/**
 * 곡마다 자체 제작 아트(v3: 무대 배경). 단순 도형만 쓰고 움직이지 않는다. 혼자·노래방일 때만 보인다(무대, 주행 노래방 화면).
 * 아리랑 = 밤하늘·초승달·별·겹친 산 능선, Destroy = 깨진 유리 조각과 조명, Wade = 밤바다 물결과 달빛 길. 그 밖의 곡은 무대 바탕색.
 * [tile]이면 정사각 곡 그림(주행 화면)으로, 그림 요소를 가운데로 모은다.
 */
@Composable
fun SongArt(songId: String?, modifier: Modifier, tile: Boolean = false) {
    Canvas(modifier) {
        when (songId) {
            "01-arirang" -> arirang(tile)
            "02-destroy" -> shards(tile)
            "03-wade" -> waves(tile)
            else -> drawRect(Brush.verticalGradient(listOf(Night.raised, Night.bg)))
        }
    }
}

private fun DrawScope.stars(count: Int, maxY: Float, seed: Int) {
    val w = size.width
    for (i in 0 until count) {
        val x = ((i * 137 + seed * 53) % 997) / 997f * w
        val y = ((i * 71 + seed * 29) % 991) / 991f * maxY
        val r = (1.4f + (i % 3) * 0.9f) * (size.minDimension / 900f).coerceAtLeast(0.6f)
        drawCircle(Color.White.copy(alpha = 0.25f + (i % 4) * 0.15f), r, Offset(x, y))
    }
}

private fun DrawScope.crescent(c: Offset, r: Float, color: Color) {
    drawCircle(Brush.radialGradient(listOf(Color(0x59FFE3A3), Color(0x00FFE3A3)), c, r * 2.2f), r * 2.2f, c)
    val outer = Path().apply { addOval(Rect(c, r)) }
    val inner = Path().apply { addOval(Rect(Offset(c.x - r * 0.42f, c.y - r * 0.18f), r * 0.86f)) }
    drawPath(Path.combine(PathOperation.Difference, outer, inner), color)
}

private fun DrawScope.ridge(base: Float, amp: Float, phase: Float, color: Color) {
    val w = size.width; val h = size.height
    val p = Path().apply {
        moveTo(0f, h)
        var x = 0f
        while (x <= w + 1f) {
            val y = h * (base - amp * sin((x / w) * 6.0 + phase).toFloat() - amp * 0.5f * sin((x / w) * 13.0 + phase * 0.6).toFloat())
            lineTo(x, y); x += w / 48
        }
        lineTo(w, h); close()
    }
    drawPath(p, color)
}

private fun DrawScope.arirang(tile: Boolean) {
    val w = size.width; val h = size.height
    drawRect(Brush.verticalGradient(listOf(Color(0xFF161335), Color(0xFF2A1638), Color(0xFF140D22))))
    stars(if (tile) 18 else 46, h * 0.5f, 1)
    val r = min(w, h) * (if (tile) 0.16f else 0.11f)
    crescent(if (tile) Offset(w * 0.66f, h * 0.30f) else Offset(w * 0.80f, h * 0.22f), r, Color(0xFFFFF1CF))
    ridge(0.62f, 0.07f, 0f, Color(0xFF3B2552))
    ridge(0.72f, 0.06f, 1.7f, Color(0xFF2C1B40))
    ridge(0.82f, 0.05f, 3.4f, Color(0xFF1C122C))
}

private fun DrawScope.shards(tile: Boolean) {
    val w = size.width; val h = size.height
    drawRect(Brush.linearGradient(listOf(Color(0xFF2A1430), Color(0xFF120C1C)), Offset(0f, 0f), Offset(w, h)))
    val c = if (tile) Offset(w * 0.55f, h * 0.42f) else Offset(w * 0.74f, h * 0.30f)
    drawCircle(Brush.radialGradient(listOf(Color(0x40FF5FA8), Color(0x00FF5FA8)), c, min(w, h) * 0.5f), min(w, h) * 0.5f, c)
    val pts = listOf(0.0f to 0.05f, 0.2f to 0.0f, 0.42f to 0.12f, 0.6f to 0.0f, 0.85f to 0.08f, 1.0f to 0.3f, 1.0f to 0.62f, 0.9f to 1.0f,
        0.62f to 0.9f, 0.4f to 1.0f, 0.15f to 0.88f, 0.0f to 0.6f)
    pts.forEachIndexed { i, (x, y) ->
        val n = pts[(i + 1) % pts.size]
        val p = Path().apply { moveTo(c.x, c.y); lineTo(w * x, h * y); lineTo(w * n.first, h * n.second); close() }
        val col = when (i % 3) { 0 -> Color(0x2EFF5FA8); 1 -> Color(0x24FFC65C); else -> Color(0x12FFFFFF) }
        drawPath(p, col)
        drawLine(Color(0x66FFFFFF), c, Offset(w * x, h * y), strokeWidth = if (tile) 2f else 2.5f)
    }
    drawCircle(Color(0xCCFFFFFF), if (tile) 5f else 8f, c)
    drawRect(Brush.verticalGradient(listOf(Color(0x00100B1C), Color(0xCC100B1C)), startY = h * 0.6f, endY = h))
}

private fun DrawScope.waves(tile: Boolean) {
    val w = size.width; val h = size.height
    drawRect(Brush.verticalGradient(listOf(Color(0xFF0F1E38), Color(0xFF1A1734), Color(0xFF120C1E))))
    stars(if (tile) 12 else 30, h * 0.36f, 3)
    val moon = if (tile) Offset(w * 0.5f, h * 0.26f) else Offset(w * 0.5f, h * 0.16f)
    val r = min(w, h) * (if (tile) 0.11f else 0.07f)
    drawCircle(Brush.radialGradient(listOf(Color(0x4DBFEFFF), Color(0x00BFEFFF)), moon, r * 3f), r * 3f, moon)
    drawCircle(Color(0xFFE6F7FF), r, moon)
    // 달빛 길: 물결 위 짧은 빛 조각(가운데로 갈수록 밝게)
    for (k in 0 until 9) {
        val y = h * (0.47f + k * 0.055f)
        val half = r * (0.5f + k * 0.18f)
        drawLine(Color(0xFFE6F7FF).copy(alpha = 0.32f - k * 0.025f), Offset(moon.x - half, y), Offset(moon.x + half, y), strokeWidth = if (tile) 2f else 3f)
    }
    for (i in 0 until 7) {
        val base = h * (0.45f + i * 0.08f)
        val p = Path().apply {
            moveTo(0f, base)
            var x = 0f
            while (x <= w + 1f) { lineTo(x, base + h * 0.022f * sin((x / w) * 9.0 + i * 0.9).toFloat()); x += w / 60 }
        }
        drawPath(p, Color(0xFF6FD3E3).copy(alpha = 0.10f + i * 0.05f), style = Stroke(width = (if (tile) 2f else 3f) + i))
    }
}
