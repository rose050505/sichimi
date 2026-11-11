package app.sichimi.navi

import ai.pleos.playground.navi.constants.NaviErrorCode
import ai.pleos.playground.navi.constants.NavigationStatus
import ai.pleos.playground.navi.data.DestinationArrivedInfo
import ai.pleos.playground.navi.data.DestinationInfo
import ai.pleos.playground.navi.data.DrivingInfo
import ai.pleos.playground.navi.data.RouteStartInfo
import ai.pleos.playground.navi.helper.NaviHelper
import ai.pleos.playground.navi.helper.listener.NaviHelperEventListener
import android.content.Context
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 내비 목적지까지 남은 시간(초). 경로가 없으면 null.
 *
 * NaviHelper 3.0.5. 남은 시간은 `DestinationInfo.remainTime`과 `DrivingInfo.destination.duration`에 온다.
 * 값을 묻는 것은 경로가 시작되거나 목적지가 바뀌는 이벤트를 받았을 때뿐이고, 되풀이해 묻지 않는다.
 */
class NaviRemaining(context: Context) {
    private val tag = "SC.Navi"
    private val navi = NaviHelper(context.applicationContext)
    private val _remainSec = MutableStateFlow<Int?>(null)
    val remainSec: StateFlow<Int?> = _remainSec.asStateFlow()

    private fun set(sec: Int?, how: String) {
        Log.i(tag, "남은 시간 $how: ${sec?.let { "${it}초" } ?: "경로 없음"}")
        _remainSec.value = sec?.takeIf { it > 0 }
    }

    private val listener = object : NaviHelperEventListener {
        override fun onRouteStarted(routeStartInfo: RouteStartInfo) { Log.i(tag, "경로 시작"); navi.getDestinationInfo() }
        override fun onRoutingCompleted() { Log.i(tag, "경로 탐색 끝"); navi.getDestinationInfo() }
        override fun onReRouteEnded() { navi.getDestinationInfo() }
        override fun onDestinationChanged() { Log.i(tag, "목적지 바뀜"); navi.getDestinationInfo() }
        override fun onDestinationInfo(info: DestinationInfo) {
            Log.i(tag, "목적지 정보 remainTime=${info.remainTime} remainDistance=${info.remainDistance}")
            set(info.remainTime, "목적지 정보")
        }
        override fun onDrivingInfo(info: DrivingInfo) {
            val d = info.destination
            Log.i(tag, "주행 정보 duration=${d?.duration} distance=${d?.distance}")
            if (d != null) set(d.duration, "주행 정보")
        }
        override fun onNavigationStatus(status: NavigationStatus) { Log.i(tag, "내비 상태 $status") }
        // 이 에뮬레이터는 경로를 시작하자마자 onRouteEnded를 함께 보낸다(2026-11-12 확인). 그래서 경로 없음으로 보지 않는다.
        override fun onRouteEnded() { Log.i(tag, "onRouteEnded(무시)") }
        override fun onRouteCancelled() = set(null, "경로 취소")
        override fun onDestinationArrived(info: DestinationArrivedInfo) = set(null, "도착")
        override fun onError(errorCode: NaviErrorCode) { Log.w(tag, "내비 오류 $errorCode") }
    }

    fun start() {
        runCatching {
            navi.initialize(
                onConnected = {
                    Log.i(tag, "NaviHelper 연결됨")
                    navi.addListener(listener)
                    navi.getDestinationInfo()
                },
                onFailed = { e -> Log.w(tag, "NaviHelper 연결 실패: $e") },
            )
        }.onFailure { Log.w(tag, "NaviHelper 초기화 실패: $it") }
    }
}
