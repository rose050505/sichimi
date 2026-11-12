package app.sichimi.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * 시치미 화면의 디자인 체계. Pleos Connect UI 디자인 가이드(Foundations: Colors, Typography)의 다크 테마 값을 따른다.
 *
 * - 색: 배경·표면·버튼은 Pleos 다크 토큰(background_popup #131417, background_secondary #1D1E21,
 *   surface_high #313236, button_filled #44464E/#686A72). 강조색은 Pleos 미디어 토큰 music_primary(#4781FF)로,
 *   평범한 음악 플레이어가 쓰는 자리(재생 버튼, 선택된 것, 켜진 스위치)에만 쓴다.
 * - [karaoke](호박색)는 혼자일 때만 보이는 것(노래방 상태 표시, 점수·내 기록)에만 쓴다. 동승자가 있을 때 보이는 화면에는
 *   이 색이 나올 자리가 없다(설계 5절: 혼자가 아닐 때는 평범한 플레이어와 구분되는 요소가 하나도 없다).
 * - 대비: 글자 색은 놓이는 표면 위에서 4.5:1 이상(tools/check_contrast.py).
 * - 글꼴: 시스템 글꼴(Pleos는 Asta Sans). 크기는 가이드 Headline 56, Title 40/36/32, Body 30/28/26, Label 24.
 *   본문은 24sp 아래로 쓰지 않는다.
 * - 움직임: 애니메이션을 쓰지 않는다. 눌렀을 때는 색만 바뀐다.
 */
object Palette {
    val bg = Color(0xFF131417)
    val surface = Color(0xFF1D1E21)
    val surfaceHi = Color(0xFF313236)
    val control = Color(0xFF44464E)
    val controlPressed = Color(0xFF686A72)
    val divider = Color(0xFF2A2B2F)

    val text = Color(0xFFFFFFFF)
    val sub = Color(0xFFBDBFC6)
    val dim = Color(0xFF9EA0A8)

    /** Pleos music_primary. 평범한 플레이어 요소의 강조(어두운 바탕 위 아이콘·글자). */
    val accent = Color(0xFF4781FF)
    /** 강조색 채움(재생 버튼, 켜진 스위치, 고른 칸). 흰 글자·아이콘이 4.5:1 넘게 한 단계 짙게. */
    val accentFill = Color(0xFF2F66E0)
    val accentPressed = Color(0xFF2552B8)

    /** 노래방 표시. 혼자일 때만 보이는 곳에서만. */
    val karaoke = Color(0xFFFFB547)
    val onKaraoke = Color(0xFF1F1500)
    val karaokeTint = Color(0xFF3A2E1A)

    val danger = Color(0xFFFF7A6B)
    val dangerTint = Color(0xFF3A2422)
}

object Type {
    val headline = TextStyle(fontSize = 56.sp, lineHeight = 72.sp, fontWeight = FontWeight.ExtraBold)
    val display = TextStyle(fontSize = 64.sp, lineHeight = 76.sp, fontWeight = FontWeight.ExtraBold)
    val stat = TextStyle(fontSize = 48.sp, lineHeight = 60.sp, fontWeight = FontWeight.ExtraBold)
    val titleL = TextStyle(fontSize = 40.sp, lineHeight = 52.sp, fontWeight = FontWeight.Bold)
    val titleM = TextStyle(fontSize = 36.sp, lineHeight = 44.sp, fontWeight = FontWeight.Bold)
    val titleS = TextStyle(fontSize = 32.sp, lineHeight = 40.sp, fontWeight = FontWeight.Bold)
    val bodyL = TextStyle(fontSize = 30.sp, lineHeight = 38.sp, fontWeight = FontWeight.Normal)
    val bodyM = TextStyle(fontSize = 28.sp, lineHeight = 36.sp, fontWeight = FontWeight.Normal)
    val bodyS = TextStyle(fontSize = 26.sp, lineHeight = 34.sp, fontWeight = FontWeight.Normal)
    val label = TextStyle(fontSize = 24.sp, lineHeight = 30.sp, fontWeight = FontWeight.Normal)
    val button = TextStyle(fontSize = 26.sp, lineHeight = 32.sp, fontWeight = FontWeight.SemiBold)
}

/** 간격(8dp 단위)과 모서리. */
object Dimen {
    val xs = 8.dp
    val s = 16.dp
    val m = 24.dp
    val l = 32.dp
    val xl = 48.dp

    val radiusCard = 28.dp
    val radiusControl = 20.dp
    val radiusRow = 20.dp

    /** 주행 화면 버튼 높이(설계: 112dp 이상). */
    val driveButton = 136.dp
    val parkButton = 112.dp
    val button = 80.dp
    val icon = 36.dp
}
