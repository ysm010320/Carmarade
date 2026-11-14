package ai.camarade.app

import ai.camarade.core.*

/** 위도·경도 한 점. */
data class GeoPoint(val lat: Double, val lon: Double)
/** 서버에서 받은 일행 위치. [approximate]면 약 1km 단위로 낮춘 위치. */
data class LivePoint(val point: GeoPoint, val approximate: Boolean)

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

    /** 이 위치에서 목적지까지 남은 직선 경로 거리(km). 위치를 가장 가까운 구간에 맞춰 계산한다. */
    fun remainingKm(p: GeoPoint): Double {
        if(points.size<2) return haversineKm(p.lat,p.lon,points.first().lat,points.first().lon)
        val kx=111.32*Math.cos(Math.toRadians(p.lat)); val ky=110.57
        var best=Double.MAX_VALUE; var along=0.0
        for(i in 1 until points.size) {
            val a=points[i-1]; val b=points[i]
            val ax=(a.lon-p.lon)*kx; val ay=(a.lat-p.lat)*ky; val bx=(b.lon-p.lon)*kx; val by=(b.lat-p.lat)*ky
            val dx=bx-ax; val dy=by-ay; val len2=dx*dx+dy*dy
            val t=if(len2==0.0) 0.0 else (-(ax*dx+ay*dy)/len2).coerceIn(0.0,1.0)
            val cx=ax+dx*t; val cy=ay+dy*t; val d=cx*cx+cy*cy
            if(d<best) { best=d; along=cumulative[i-1]+(cumulative[i]-cumulative[i-1])*t }
        }
        return (lengthKm-along).coerceAtLeast(0.0)+Math.sqrt(best)
    }
}

/** 체험 경로. 출발지는 서울숲으로 둔다(남은 거리 계산 demoRouteKm과 같은 출발지). */
object RouteGeometry {
    val DEMO_START=Place("start","서울숲",37.5445,127.0374)
    fun stops(route: RoutePlan): List<Place> = listOf(DEMO_START)+route.waypoints+route.destination
    fun line(route: RoutePlan)=RouteLine(stops(route).map { GeoPoint(it.latitude,it.longitude) })
}
