package ai.camarade.core

import kotlin.math.*

/** 빠른 메시지 문구. 말풍선은 이 문구를 보고 아이콘(커피·따봉)으로 바꿔 보여 준다. */
const val REST_TEXT = "잠깐 쉬어가요"
const val OK_TEXT = "잘 따라가고 있어요"
enum class QuickKind { REST, OK, TEXT }
fun quickKind(text: String): QuickKind = when (text.trim().trimEnd('.')) {
    REST_TEXT, "다음 휴게소에서 잠깐 쉬어가요" -> QuickKind.REST
    OK_TEXT -> QuickKind.OK
    else -> QuickKind.TEXT
}

/** 이 거리 이하로 남으면 "곧 도착". */
const val ARRIVING_KM = 0.15
const val ARRIVING_LABEL = "곧 도착"

/** 남은 거리 표시. 100km 이상은 100km 단위(내림), 1km 이상은 km, 1km 미만은 100m 단위. */
fun formatRemaining(km: Double): String = when {
    km >= 100 -> "${(floor(km / 100) * 100).toInt()}km"
    km >= 1 -> "${km.roundToInt().coerceIn(1, 99)}km"
    km > ARRIVING_KM -> "${((km * 10).roundToInt().coerceAtLeast(1)) * 100}m"
    else -> ARRIVING_LABEL
}

/** 거리 알림을 띄우는 지점: 100km마다, 그 아래는 50·30·10·5·3·1km, 500m, 곧 도착. */
fun alertThresholds(totalKm: Double): List<Double> {
    val hundreds = (floor((totalKm - 1e-9) / 100).toInt() downTo 1).map { it * 100.0 }
    return hundreds + listOf(50.0, 30.0, 10.0, 5.0, 3.0, 1.0, 0.5, ARRIVING_KM).filter { it < totalKm }
}
fun alertLabel(thresholdKm: Double): String = if (thresholdKm <= ARRIVING_KM) ARRIVING_LABEL else formatRemaining(thresholdKm)
/** prev → now 사이에 지난 알림 지점. 한 번에 여러 개를 지나면 가장 가까운(작은) 것 하나만. */
fun crossedAlert(prevKm: Double, nowKm: Double, totalKm: Double): Double? =
    alertThresholds(totalKm).filter { nowKm <= it && it < prevKm }.minOrNull()

/**
 * 체험용 남은 거리. 끝으로 갈수록 느려져서 짧은 체험 시간 안에 km·500m·곧 도착 알림이 모두 보이게 한다.
 * 실제 차량 속도와는 관계없다.
 */
fun simulatedRemaining(totalKm: Double, movingSeconds: Double, demoSeconds: Double): Double {
    val f = (1 - movingSeconds / demoSeconds).coerceIn(0.0, 1.0)
    return totalKm * f * f * f
}

/** 체험 출발지(서울숲)에서 경유지를 거쳐 목적지까지의 대략적인 도로 거리. 직선거리 × 1.25. */
fun demoRouteKm(route: RoutePlan, startLat: Double = 37.5445, startLon: Double = 127.0374): Double {
    var lat = startLat; var lon = startLon; var sum = 0.0
    (route.waypoints + route.destination).forEach { p -> sum += haversineKm(lat, lon, p.latitude, p.longitude); lat = p.latitude; lon = p.longitude }
    return max(sum * 1.25, 20.0)
}
fun haversineKm(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
    val dLat = Math.toRadians(lat2 - lat1); val dLon = Math.toRadians(lon2 - lon1)
    val a = sin(dLat / 2).pow(2) + cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) * sin(dLon / 2).pow(2)
    return 6371.0 * 2 * atan2(sqrt(a), sqrt(1 - a))
}
