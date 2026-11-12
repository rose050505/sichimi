package app.sichimi

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.sichimi.audio.TakePlayUi
import app.sichimi.core.MascotUi
import app.sichimi.core.MissionUi
import app.sichimi.core.StageUi
import app.sichimi.gate.BadgeId
import app.sichimi.gate.MascotLook
import app.sichimi.gate.MissionId
import app.sichimi.gate.WeekStats
import app.sichimi.rank.EscapeEntry
import app.sichimi.rank.RankEntry
import app.sichimi.rank.TakeMeta
import app.sichimi.ui.EscapeRows
import app.sichimi.ui.LastScoreUi
import app.sichimi.ui.MonthRecords
import app.sichimi.ui.Night
import app.sichimi.ui.NightDialog
import app.sichimi.ui.NightTabs
import app.sichimi.ui.ParkedTab
import app.sichimi.ui.RankUi
import app.sichimi.ui.RecordsTab
import app.sichimi.ui.RevealCard
import app.sichimi.ui.RevealOverlay
import app.sichimi.ui.ScoreRowUi
import app.sichimi.ui.ScoresTab
import app.sichimi.ui.StageTop
import app.sichimi.ui.TakeActions
import app.sichimi.ui.TourPoster
import app.sichimi.ui.AppIcons
import app.sichimi.ui.ChartRow
import app.sichimi.ui.NightStyle

/**
 * debug 빌드에만 있는 디자인 미리보기(문서용 캡처). 실제 화면 부품에 **예시 데이터**를 넣어 그린다.
 * 서버 기록이 적어 실제 앱에서 바로 볼 수 없는 장면(단상 1~3위, 곡 끝 점수, 배지·투어 카드, 동의 창)을 찍는 데만 쓴다. release APK에는 없다.
 *   adb shell am start --user 10 -n app.sichimi/.DesignGalleryActivity --es scene scores
 */
class DesignGalleryActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val scene = intent.getStringExtra("scene") ?: "scores"
        setContent { Gallery(scene) }
    }
}

private val stage = StageUi(
    points = 34, stage = 2, stageName = "콧노래", progress = 0.35f, title = "밤의 가수",
    week = WeekStats(shows = 3, soloMs = 23 * 60_000L, bestScore = 93, fastestEscapeMs = 270),
    missions = listOf(
        MissionUi(MissionId.entries[0], "80점 넘기기", "완료", true, 1f),
        MissionUi(MissionId.entries[1], "한 번도 안 들키고 3곡", "2 / 3곡", false, 0.66f),
        MissionUi(MissionId.entries[2], "비 오는 날 1곡", "0 / 1곡", false, 0f),
    ),
    badges = setOf(BadgeId.FIRST_STAGE, BadgeId.ESCAPE_MASTER, BadgeId.NIGHT_SINGER),
)

@Composable
private fun Frame(tab: ParkedTab, content: @Composable () -> Unit) {
    androidx.compose.foundation.layout.Column(Modifier.fillMaxSize().background(Night.bg).padding(start = 32.dp, end = 32.dp, bottom = 32.dp, top = 24.dp)) {
        NightTabs(ParkedTab.entries, tab, { it.label }, { null }, {})
        androidx.compose.foundation.layout.Spacer(Modifier.padding(top = 16.dp))
        Box(Modifier.weight(1f)) { content() }
    }
}

@Composable
private fun Gallery(scene: String) {
    val nick = listOf("새벽드라이버", "출근요정", "터널가왕", "고속도로솔로", "퇴근길가수", "빗길발라더")
    when (scene) {
        "scores" -> Frame(ParkedTab.SCORES) {
            val top = listOf(97, 95, 94, 91, 88, 86).mapIndexed { i, s ->
                RankEntry(i + 1, nick[i], s, mine = i == 4, uid = "u$i", take = if (i != 4) TakeMeta(s, 0, 221_000) else null)
            }
            ScoresTab(
                scoring = true, last = LastScoreUi("2021 아리랑 응원가", 93, true, 85),
                rows = listOf(ScoreRowUi("2021 아리랑 응원가", 93, 93, 12), ScoreRowUi("I Want To Destroy Something Beautiful", 81, 77, 4), ScoreRowUi("Wade", null, 0, 0)),
                rankSongs = listOf("01-arirang" to "2021 아리랑 응원가", "02-destroy" to "I Want To Destroy Something Beautiful", "03-wade" to "Wade"),
                rankSong = "01-arirang", onRankSong = {}, rank = RankUi.Ready(top, 5, 128), uploading = true,
                play = TakePlayUi(), takeActions = TakeActions({}, { _, _ -> }, {}, {}, {}, { _, _ -> }),
            )
        }
        "escape" -> Frame(ParkedTab.SCORES) {
            EscapeRows(listOf(240L, 262, 270, 301, 355).mapIndexed { i, ms -> EscapeEntry(i + 1, nick[i], ms, listOf("DOOR", "SEAT", "WINDOW")[i % 3], i == 2) }, 3, 41, true)
        }
        "records" -> Frame(ParkedTab.RECORDS) {
            RecordsTab(MonthRecords(12, 47 * 60_000L, listOf(ChartRow("2021 아리랑 응원가", 7, 26 * 60_000L), ChartRow("Wade", 3, 11 * 60_000L)), mapOf("DOOR" to 3, "SEAT" to 1), 240)) {
                StageTop(stage, MascotUi(look = MascotLook()), null)
            }
        }
        "reveal_score" -> Frame(ParkedTab.PLAYER) {
            RevealOverlay(RevealCard.Score(1, "2021 아리랑 응원가", 93, true, 85, "새벽드라이버", 89, 2), 3, MascotLook()) {}
        }
        "reveal_badge" -> Frame(ParkedTab.PLAYER) {
            RevealOverlay(RevealCard.Award(2, "BADGE", "밤의 가수", "밤에 혼자 한 곡", 0, null, 1), 2, MascotLook()) {}
        }
        "reveal_tour" -> Frame(ParkedTab.PLAYER) {
            RevealOverlay(RevealCard.Award(3, "TOUR", "출근길 투어 완주", "이번 주 공연 5회", 15,
                TourPoster("11월 셋째 주", listOf("2021 아리랑 응원가", "Wade", "I Want To Destroy Something Beautiful"), 38 * 60_000L, 93, 240), 0), 3, MascotLook()) {}
        }
        "consent" -> Frame(ParkedTab.SETTINGS) {
            NightDialog(AppIcons.Mic, Night.pink, "녹음도 함께 공개됩니다",
                "켜면 혼자만의 노래방에서 부른 내 목소리를 녹음해 둡니다(반주는 녹음하지 않습니다). 최고 기록을 낸 판의 녹음은 「점수」 탭에서 " +
                    "미리 들어 보고 「녹음 올리기」를 누르면 서버에 공개로 저장되어, 전국 랭킹에서 누구나 들을 수 있습니다. " +
                    "올린 녹음은 「점수」 탭이나 이 설정에서 언제든 지울 수 있습니다.",
                "알겠습니다, 켜기", {}, {}, NightStyle.PRIMARY)
        }
    }
}
