package ai.camarade.core

private var passed = 0
private fun test(name: String, block: () -> Unit) { block(); passed++; println("PASS $name") }
private fun rejects(block: () -> Unit) {
    try { block() } catch (_: IllegalArgumentException) { return } catch (_: IllegalStateException) { return }
    error("Expected operation to be rejected")
}
private val sea = Place("sea", "강릉 안목해변", 37.7712, 128.9487)
private val rest = Place("rest", "횡성휴게소", 37.4600, 128.1341)
private fun fresh() = Trip.create("주말의 동행", "동행", sea, now = 1000)
private fun ready(): Trip {
    var t = fresh().addMember("me", "ji", "지수", "아이오닉 5")
    t = t.acceptRoute("me", t.route.version).acceptRoute("ji", t.route.version)
    return t
}
fun main() {
    test("new group starts with location sharing off") {
        val t = fresh(); check(t.members.single().sharing == Sharing.OFF); check(!t.members.single().accepted(t.route))
    }
    test("route changes require renewed acceptance") {
        val t = ready().setRoute("me", sea, listOf(rest))
        check(t.route.version == 2); check(t.members.none { it.accepted(t.route) })
        rejects { t.acceptRoute("me", 1) }
        check(t.acceptRoute("me", 2).members.first().accepted(t.route))
    }
    test("host authorization protects route and ending") {
        rejects { ready().setRoute("ji", sea, listOf(rest)) }
        rejects { ready().start("ji", 2000) }
        rejects { ready().start("me", 2000).finish("ji", 5000, 2.4) }
    }
    test("third and duplicate waypoints rejected without changing existing plan") {
        val t = fresh().setRoute("me", sea, listOf(rest))
        rejects { t.setRoute("me", sea, listOf(rest, rest)) }
        rejects { t.setRoute("me", sea, listOf(rest, sea)) }
        rejects { t.setRoute("me", sea, listOf(rest, Place("a","A",37.0,127.0), Place("b","B",36.0,127.0))) }
        check(t.route.waypoints.size == 1)
    }
    test("departure requires all current route acceptances") {
        rejects { fresh().start("me", 2000) }
        check(ready().start("me", 2000).phase == Phase.DRIVING)
    }
    test("trip end stops all sharing and prevents further communication") {
        val t = ready().setSharing("me", Sharing.EXACT).setSharing("ji", Sharing.APPROXIMATE)
            .start("me", 2000).finish("me", 62000, 3.5)
        check(t.phase == Phase.FINISHED); check(t.members.all { it.sharing == Sharing.OFF })
        check(t.record?.distanceKm == 3.5); check(t.record?.durationSeconds == 60L)
        rejects { t.setSharing("me", Sharing.EXACT) }; rejects { t.send("me", "all", "안녕", 63000) }
        rejects { t.finish("me", 65000, 5.0) }
    }
    test("driving prevents text entry and planning edits") {
        val t = ready().start("me", 2000)
        rejects { t.rename("me", "새 이름") }; rejects { t.setRoute("me", sea, listOf(rest)) }
        rejects { t.send("me", "all", "키보드 입력", 3000, InputMode.KEYBOARD) }
        check(t.send("me", "ji", "다음 휴게소에서 만나요", 3000, InputMode.PRESET).messages.size == 1)
    }
    test("validation rejects blank names invalid members and malformed positions") {
        rejects { Trip.create("  ", "동행", sea, 1000) }
        rejects { Place("x", "X", Double.NaN, 127.0) }
        rejects { Place("x", "X", 91.0, 127.0) }
        rejects { fresh().addMember("me", "me", "중복", "차") }
        rejects { fresh().send("unknown", "all", "hi", 2000) }
        rejects { fresh().send("me", "unknown", "hi", 2000) }
        rejects { fresh().send("me", "all", "  ", 2000) }
    }
    test("group limit is enforced") {
        var t = fresh()
        for (i in 1..4) t = t.addMember("me", "m$i", "멤버$i", "차량")
        rejects { t.addMember("me", "six", "6번째", "차량") }
    }
    test("hidden stale and approximate locations cannot expose exact position") {
        val p = Position(37.123456, 127.654321, 1000)
        check(visiblePosition(Sharing.OFF, p, 2000) == null)
        check(visiblePosition(Sharing.EXACT, p, 40001) == null)
        check(visiblePosition(Sharing.EXACT, p, 2000)?.latitude == 37.123456)
        val approx = visiblePosition(Sharing.APPROXIMATE, p, 2000)!!
        check(approx.latitude == 37.12); check(approx.longitude == 127.65)
    }
    test("feedback only saved after finish") {
        rejects { ready().review("좋았어요") }
        check(ready().start("me", 2000).finish("me", 62000, 3.5).review("좋았어요").record?.review == "좋았어요")
    }
    test("restoring a running trip requires fresh sharing consent") {
        val original=ready().setSharing("me",Sharing.EXACT).setSharing("ji",Sharing.APPROXIMATE).start("me",2000)
        val restored=original.restoreSafely()
        check(restored.members.all { it.sharing==Sharing.OFF })
        check(restored.phase==Phase.DRIVING && restored.route==original.route)
    }
    test("storage failure cannot prevent privacy revocation or trip ending") {
        val current=ready().setSharing("me",Sharing.EXACT).start("me",2000)
        val ended=current.finish("me",62000,3.5)
        val result=persistLive(listOf(current),ended) { error("disk full") }
        check(!result.saved && result.error!=null)
        check(result.trips.single().phase==Phase.FINISHED)
        check(result.trips.single().members.all { it.sharing==Sharing.OFF })
        val hidden=persistLive(listOf(current),current.setSharing("me",Sharing.OFF)) { error("disk full") }
        check(hidden.trips.single().members.first().sharing==Sharing.OFF)
    }
    test("storage success records the exact current state") {
        val next=ready();var stored:List<Trip>?=null
        val result=persistLive(listOf(next),next) { stored=it }
        check(result.saved && result.error==null && stored==listOf(next))
    }
    test("online room keeps me first and syncs other members from the server") {
        val t = fresh().copy(roomId = "r_1", roomCode = "ABC234")
        check(t.online && t.inviteCode == "ABC234")
        val synced = t.syncMembers(listOf(Member("u_a", "지수", "IONIQ 5", acceptedVersion = 1), Member("u_b", "하늘", "AVANTE")))
        check(synced.members.map { it.id } == listOf("me", "u_a", "u_b"))
        check(synced.syncMembers(listOf(Member("u_b", "하늘", "EV6"))).members.map { it.car } == listOf("내 차량", "EV6"))
        check(t.syncMembers(List(6) { Member("u_$it", "m$it", "car") }).members.size == 5)
    }
    test("online room starts once I accept, members accept on their own cars") {
        val t = fresh().copy(roomId = "r_1").syncMembers(listOf(Member("u_a", "지수", "IONIQ 5")))
        rejects { t.start("me", 2000) }
        check(t.acceptRoute("me", t.route.version).start("me", 2000).phase == Phase.DRIVING)
        rejects { fresh().addMember("me", "ji", "지수", "car").acceptRoute("me", 1).start("me", 2000) }
    }
    test("host route change from the server needs my acceptance again, ignored after departure") {
        val t = fresh().copy(roomId = "r_1").let { it.acceptRoute("me", it.route.version) }
        check(t.applyRemoteRoute(sea, emptyList()) == t)
        val moved = t.applyRemoteRoute(sea, listOf(rest))
        check(moved.route.version == 2 && moved.route.waypoints == listOf(rest) && !moved.members.first().accepted(moved.route))
        val driving = t.start("me", 2000)
        check(driving.applyRemoteRoute(rest, emptyList()) == driving)
    }
    println("$passed behavior tests passed")
}
