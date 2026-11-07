package ai.camarade.core

private var passed = 0
private fun test(name: String, block: () -> Unit) { block(); passed++; println("PASS $name") }

fun main() {
    test("100km 이상은 100km 단위로 내림") {
        check(formatRemaining(230.0) == "200km"); check(formatRemaining(150.0) == "100km"); check(formatRemaining(100.0) == "100km")
    }
    test("100km 미만은 km, 1km 미만은 100m 단위, 아주 가까우면 곧 도착") {
        check(formatRemaining(99.4) == "99km"); check(formatRemaining(37.6) == "38km"); check(formatRemaining(1.0) == "1km")
        check(formatRemaining(0.42) == "400m"); check(formatRemaining(0.5) == "500m"); check(formatRemaining(0.1) == "곧 도착")
    }
    test("알림 지점: 100km마다, 그 아래 50·30·10·5·3·1km, 500m, 곧 도착") {
        check(alertThresholds(215.0) == listOf(200.0, 100.0, 50.0, 30.0, 10.0, 5.0, 3.0, 1.0, 0.5, ARRIVING_KM))
        check(alertThresholds(40.0) == listOf(30.0, 10.0, 5.0, 3.0, 1.0, 0.5, ARRIVING_KM))
        check(alertLabel(200.0) == "200km"); check(alertLabel(0.5) == "500m"); check(alertLabel(ARRIVING_KM) == "곧 도착")
    }
    test("지난 알림 지점만 한 번, 여러 개를 지나면 가장 가까운 것") {
        check(crossedAlert(210.0, 195.0, 215.0) == 200.0)
        check(crossedAlert(52.0, 48.0, 215.0) == 50.0)
        check(crossedAlert(0.6, 0.45, 215.0) == 0.5)
        check(crossedAlert(48.0, 47.0, 215.0) == null)
        check(crossedAlert(12.0, 4.0, 215.0) == 5.0)
    }
    test("체험 거리는 시작에 전체, 끝에 0이고 계속 줄어든다") {
        check(simulatedRemaining(100.0, 0.0, 240.0) == 100.0); check(simulatedRemaining(100.0, 240.0, 240.0) == 0.0)
        check(simulatedRemaining(100.0, 100.0, 240.0) > simulatedRemaining(100.0, 101.0, 240.0))
    }
    test("빠른 메시지 문구를 아이콘 종류로 구분") {
        check(quickKind(REST_TEXT) == QuickKind.REST); check(quickKind("다음 휴게소에서 잠깐 쉬어가요.") == QuickKind.REST)
        check(quickKind("잘 따라가고 있어요.") == QuickKind.OK); check(quickKind("화장실 들렀다 갈게요") == QuickKind.TEXT)
    }
    test("서울숲에서 강릉 안목해변까지 체험 거리는 100~300km") {
        val km = demoRouteKm(RoutePlan(Place("sea", "강릉 안목해변", 37.7712, 128.9487)))
        check(km in 100.0..300.0) { "km=$km" }
    }
    println("$passed drive tests passed")
}
