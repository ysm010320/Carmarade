package ai.camarade.app

import ai.camarade.core.*

/** 위도·경도 한 점. */
data class GeoPoint(val lat: Double, val lon: Double)

/** 출발지 → 경유지 → 목적지를 잇는 꺾은선. 지도에 그리지 않고, 체험용 차량 위치를 정하는 데만 쓴다. */
class RouteLine(val points: List<GeoPoint>) {
    private val cumulative: DoubleArray=DoubleArray(points.size).also { c ->
        for(i in 1 until points.size) c[i]=c[i-1]+haversineKm(points[i-1].lat,points[i-1].lon,points[i].lat,points[i].lon)
    }
    val lengthKm: Double get()=cumulative.lastOrNull() ?: 0.0

    /** 진행률(0=출발, 1=목적지)에 해당하는 좌표. */
    fun pointAt(fraction: Double): GeoPoint {
        if(points.size<2) return points.first()
        val target=fraction.coerceIn(0.0,1.0)*lengthKm
        val i=when(val found=cumulative.indexOfFirst { it>=target }) { -1->points.lastIndex; 0->1; else->found }
        val a=points[i-1]; val b=points[i]
        val seg=cumulative[i]-cumulative[i-1]
        val k=if(seg<=0) 0.0 else (target-cumulative[i-1])/seg
        return GeoPoint(a.lat+(b.lat-a.lat)*k,a.lon+(b.lon-a.lon)*k)
    }
}

/** 체험 경로. 출발지는 서울숲으로 둔다(남은 거리 계산 demoRouteKm과 같은 출발지). */
object RouteGeometry {
    val DEMO_START=Place("start","서울숲",37.5445,127.0374)
    fun stops(route: RoutePlan): List<Place> = listOf(DEMO_START)+route.waypoints+route.destination
    fun line(route: RoutePlan)=RouteLine(stops(route).map { GeoPoint(it.latitude,it.longitude) })
}
