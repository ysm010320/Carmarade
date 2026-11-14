package ai.camarade.app

import android.content.Context
import android.os.Handler
import android.os.Looper
import ai.camarade.core.*
import ai.pleos.playground.navi.constants.NaviErrorCode
import ai.pleos.playground.navi.constants.WaypointIndex
import ai.pleos.playground.navi.data.DestinationArrivedInfo
import ai.pleos.playground.navi.data.NavigationScreenRequest
import ai.pleos.playground.navi.data.RequestWaypointInfo
import ai.pleos.playground.navi.data.RouteInfo
import ai.pleos.playground.navi.data.RouteOverviewShowInfo
import ai.pleos.playground.navi.data.RouteStartInfo
import ai.pleos.playground.navi.data.RouteWithWaypointsRequest
import ai.pleos.playground.navi.helper.NaviHelper
import ai.pleos.playground.navi.helper.listener.NaviHelperEventListener

/**
 * Pleos 내비 지도 열기.
 *
 * Pleos는 앱 안에 넣는 지도(MapView·지도 타일·마커)를 제공하지 않는다(NaviHelper 3.0.5 소스로 확인).
 * 대신 NaviHelper.requestNavigationScreen으로 Pleos 내비의 지도 화면(경로 전체 보기, 현재 위치)을 띄울 수 있다.
 * 모임 경로를 아직 내비에 보내지 않았으면 같은 목적지·경유지를 먼저 보내고, 경로가 시작되면 전체 보기를 연다.
 * Pleos 기기가 아니거나 연결에 실패하면 [ready]가 false이고 앱 안의 개념도만 쓴다.
 */
class PleosNaviMap(context: Context) {
    var ready=false; private set
    var failed=false; private set
    /** Pleos 내비가 목적지 도착을 알릴 때(메인 스레드). */
    var onArrived: (() -> Unit)?=null
    private val main=Handler(Looper.getMainLooper())
    private var helper: NaviHelper?=null
    // 내비에 보낸 경로(모임 id + 경로 버전). 같은 경로를 다시 보내 안내를 새로 시작하지 않게 함.
    private var sentKey: String?=null
    private var waitingKey: String?=null
    private var waitingDone: ((String) -> Unit)?=null
    private val timeout=Runnable { finishWaiting(null,"내비가 경로를 아직 받지 못했어요. 현재 위치 지도를 열어요.") }

    private val listener=object: NaviHelperEventListener {
        override fun onRouteStarted(routeStartInfo: RouteStartInfo) { main.post { waitingKey?.let { finishWaiting(it,"Pleos 내비에 같은 목적지·경유지를 보냈어요.") } } }
        override fun onDestinationArrived(destinationArrivedInfo: DestinationArrivedInfo) { main.post { onArrived?.invoke() } }
        override fun onError(errorCode: NaviErrorCode) { main.post { if(waitingKey!=null) finishWaiting(null,"내비 경로 요청 오류($errorCode). 현재 위치 지도를 열어요.") } }
    }

    init {
        try {
            val h=NaviHelper(context.applicationContext);helper=h
            h.initialize(
                onConnected={ main.post { runCatching { h.addListener(listener) };ready=true } },
                onFailed={ main.post { ready=false;failed=true } }
            )
        } catch(_: Throwable) { helper=null;failed=true }
    }

    /** 이 모임 경로를 Pleos 내비 지도에서 전체 보기로 연다. 결과 안내 문구를 [done]으로 돌려준다. */
    fun showRoute(tripId: String, route: RoutePlan, done: (String) -> Unit) {
        val h=helper
        if(h==null || !ready) { done(if(failed) "Pleos 내비를 쓸 수 없는 기기예요. 앱 지도만 보여 드려요." else "Pleos 내비에 연결하고 있어요. 잠시 후 다시 눌러 주세요.");return }
        val key="$tripId:${route.version}"
        if(sentKey==key) { done(if(overview(h)) "Pleos 내비에서 경로 전체를 보여 드려요." else "Pleos 내비 지도를 열지 못했어요.");return }
        try {
            waitingKey=key;waitingDone=done
            h.requestRouteWithWaypoints(request(route))
            main.removeCallbacks(timeout);main.postDelayed(timeout,10_000)
        } catch(e: Throwable) { finishWaiting(null,"내비 경로 요청 실패: ${e.javaClass.simpleName}") }
    }

    /** Pleos 내비의 현재 위치 지도를 연다. */
    fun showCurrentLocation(): Boolean=helper?.takeIf { ready }?.let { open(it,NavigationScreenRequest.MoveToCurrentLocationOnMap) } ?: false

    fun release() {
        main.removeCallbacks(timeout);waitingDone=null;waitingKey=null
        runCatching { helper?.removeListener(listener);helper?.release() };helper=null;ready=false
    }

    private fun finishWaiting(key: String?, message: String) {
        main.removeCallbacks(timeout)
        val done=waitingDone;waitingDone=null;waitingKey=null
        val h=helper ?: return
        val opened=if(key!=null) { sentKey=key;overview(h) } else open(h,NavigationScreenRequest.MoveToCurrentLocationOnMap)
        done?.invoke(if(opened) message else "Pleos 내비 지도를 열지 못했어요.")
    }
    private fun overview(h: NaviHelper)=open(h,NavigationScreenRequest.OverviewRoute(RouteOverviewShowInfo(durationMs=10_000)))
    private fun open(h: NaviHelper, screen: NavigationScreenRequest)=runCatching { h.requestNavigationScreen(screen) }.isSuccess

    /** 우리 장소에는 내비 POI ID가 없어 좌표로 요청한다. 경유지는 내비 제한에 맞춰 최대 2개. */
    private fun request(route: RoutePlan)=RouteWithWaypointsRequest(
        destination=RouteInfo(latitude=route.destination.latitude,longitude=route.destination.longitude,
            poiName=route.destination.name,poiId="",address=route.destination.name,poiSubId="0"),
        waypoints=route.waypoints.take(2).mapIndexed { i,p ->
            RequestWaypointInfo(latitude=p.latitude,longitude=p.longitude,poiName=p.name,
                waypointIndex=if(i==0) WaypointIndex.FIRST else WaypointIndex.SECOND,poiId="",address=p.name,poiSubId="0")
        }
    )
}
