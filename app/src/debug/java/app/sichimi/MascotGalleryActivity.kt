package app.sichimi

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Text
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.sichimi.gate.Growth
import app.sichimi.gate.MascotLook
import app.sichimi.gate.MascotPose
import app.sichimi.ui.Mascot
import app.sichimi.ui.Palette
import app.sichimi.ui.Type

/** debug 빌드에만 있는 시치미 그림 모음(문서용 캡처). release APK에는 들어가지 않는다. */
class MascotGalleryActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            Column(Modifier.fillMaxSize().background(Palette.stageBg).padding(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    (1..4).forEach { s ->
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Mascot(s, MascotPose.SINGING, MascotLook(), Modifier.size(300.dp))
                            Text("${s}단계 · ${Growth.STAGE_NAMES[s - 1]}", style = Type.bodyM, color = Palette.text)
                        }
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    listOf(
                        Triple("우비(와이퍼)", MascotPose.SINGING, MascotLook(raincoat = true)),
                        Triple("밤", MascotPose.SINGING, MascotLook(night = true)),
                        Triple("바람(창문 조금)", MascotPose.SINGING, MascotLook(breeze = true)),
                        Triple("숨는 중", MascotPose.HIDING, MascotLook()),
                        Triple("휴, 안 들켰다", MascotPose.RELIEVED, MascotLook()),
                    ).forEach { (name, pose, look) ->
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Mascot(3, pose, look, Modifier.size(240.dp), hide = { if (pose == MascotPose.HIDING) 1f else 0f })
                            Text(name, style = Type.bodyM, color = Palette.text)
                        }
                    }
                }
            }
        }
    }
}
