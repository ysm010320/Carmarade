package ai.camarade.core

import java.util.UUID
import kotlin.math.round

enum class Sharing { OFF, EXACT, APPROXIMATE }
enum class Phase { PLANNING, DRIVING, FINISHED }
enum class InputMode { KEYBOARD, PRESET, VOICE }
data class Place(val id: String, val name: String, val latitude: Double, val longitude: Double) {
    init {
        require(id.isNotBlank() && name.isNotBlank()) { "장소 이름을 입력해 주세요." }
        require(latitude.isFinite() && latitude in -90.0..90.0 && longitude.isFinite() && longitude in -180.0..180.0) { "올바르지 않은 위치입니다." }
    }
}
data class Position(val latitude: Double, val longitude: Double, val updatedAt: Long)
data class RoutePlan(val destination: Place, val waypoints: List<Place> = emptyList(), val version: Int = 1) {
    init {
        require(version > 0)
        require(waypoints.size <= 2) { "경유지는 최대 2개까지 설정할 수 있어요." }
        require(waypoints.map { it.id }.distinct().size == waypoints.size && waypoints.none { it.id == destination.id }) { "목적지와 경유지는 서로 다른 장소를 선택해 주세요." }
    }
}
data class Member(val id: String, val name: String, val car: String, val sharing: Sharing = Sharing.OFF, val acceptedVersion: Int = 0) {
    init { require(id.isNotBlank() && name.isNotBlank() && name.length <= 24 && acceptedVersion >= 0) }
    fun accepted(route: RoutePlan) = acceptedVersion == route.version
}
data class Message(val sender: String, val recipient: String, val text: String, val at: Long)
data class TripRecord(val durationSeconds: Long, val distanceKm: Double, val review: String = "")
data class Trip(
    val name: String,
    val type: String,
    val route: RoutePlan,
    val members: List<Member> = listOf(Member("me", "나", "내 차량")),
    val phase: Phase = Phase.PLANNING,
    val messages: List<Message> = emptyList(),
    val record: TripRecord? = null,
    val id: String = UUID.randomUUID().toString(),
    val createdAt: Long = System.currentTimeMillis(),
    val startedAt: Long? = null,
    val endedAt: Long? = null
) {
    init {
        require(name.isNotBlank() && name.length <= 40) { "모임 이름은 1~40자로 입력해 주세요." }
        require(type in listOf("크루", "동행"))
        require(members.size in 1..5 && members.first().id == "me" && members.map { it.id }.distinct().size == members.size)
        require(phase != Phase.DRIVING || startedAt != null)
        require(phase != Phase.FINISHED || (record != null && endedAt != null && members.all { it.sharing == Sharing.OFF }))
    }
    val inviteCode: String get() = id.replace("-", "").take(6).uppercase()
    companion object {
        fun create(name: String, type: String, destination: Place, now: Long = System.currentTimeMillis()) =
            Trip(name.trim(), type, RoutePlan(destination), createdAt = now)
    }
    private fun host(actor: String) { require(actor == "me") { "호스트만 변경할 수 있어요." } }
    private fun active() { check(phase != Phase.FINISHED) { "종료된 동행입니다." } }
    private fun planning() { check(phase == Phase.PLANNING) { "출발 전에 설정해 주세요." } }
    private fun member(actor: String) { require(members.any { it.id == actor }) { "모임 멤버가 아닙니다." } }
    fun addMember(actor: String, id: String, name: String, car: String): Trip {
        host(actor); planning()
        require(members.size < 5) { "첫 버전에서는 최대 5대까지 함께할 수 있어요." }
        require(members.none { it.id == id }) { "이미 참여한 멤버입니다." }
        return copy(members = members + Member(id, name.trim(), car.trim()))
    }
    fun acceptRoute(actor: String, version: Int): Trip {
        active(); member(actor)
        require(version == route.version) { "경로가 변경됐어요. 최신 경로를 확인해 주세요." }
        return copy(members = members.map { if (it.id == actor) it.copy(acceptedVersion = version) else it })
    }
    fun setRoute(actor: String, destination: Place, waypoints: List<Place>): Trip {
        host(actor); planning()
        return copy(route = RoutePlan(destination, waypoints.toList(), route.version + 1))
    }
    fun start(actor: String, at: Long): Trip {
        host(actor); planning()
        require(members.all { it.accepted(route) }) { "모두가 최신 경로를 수락하면 출발할 수 있어요." }
        require(at >= createdAt)
        return copy(phase = Phase.DRIVING, startedAt = at)
    }
    fun finish(actor: String, at: Long, distanceKm: Double): Trip {
        host(actor); check(phase == Phase.DRIVING) { "주행 중인 동행만 종료할 수 있어요." }
        require(distanceKm.isFinite() && distanceKm >= 0 && at >= startedAt!!)
        return copy(phase = Phase.FINISHED, endedAt = at,
            members = members.map { it.copy(sharing = Sharing.OFF) },
            record = TripRecord((at - startedAt) / 1000, distanceKm))
    }
    fun setSharing(actor: String, sharing: Sharing): Trip {
        active(); member(actor)
        return copy(members = members.map { if (it.id == actor) it.copy(sharing = sharing) else it })
    }
    fun rename(actor: String, name: String): Trip { host(actor); planning(); return copy(name = name.trim()) }
    fun send(actor: String, recipient: String, text: String, at: Long, mode: InputMode = InputMode.PRESET): Trip {
        active(); member(actor)
        if (recipient != "all") member(recipient)
        require(text.isNotBlank() && text.trim().length <= 200) { "메시지는 1~200자로 입력해 주세요." }
        require(phase != Phase.DRIVING || mode != InputMode.KEYBOARD) { "주행 중에는 음성이나 빠른 메시지를 사용해 주세요." }
        return copy(messages = (messages + Message(actor, recipient, text.trim(), at)).takeLast(100))
    }
    fun review(text: String): Trip {
        check(phase == Phase.FINISHED); require(text.length <= 500) { "후기는 500자 이내로 입력해 주세요." }
        return copy(record = record!!.copy(review = text.trim()))
    }
    /** Sharing consent must never silently resume after process death. */
    fun restoreSafely(): Trip = copy(members = members.map { it.copy(sharing = Sharing.OFF) })
}
fun visiblePosition(sharing: Sharing, position: Position?, now: Long): Position? {
    if (sharing == Sharing.OFF || position == null || now - position.updatedAt > 30000 || position.updatedAt > now ||
        !position.latitude.isFinite() || !position.longitude.isFinite() || position.latitude !in -90.0..90.0 || position.longitude !in -180.0..180.0) return null
    return if (sharing == Sharing.APPROXIMATE) position.copy(latitude = round(position.latitude * 100) / 100, longitude = round(position.longitude * 100) / 100) else position
}
data class PersistResult(val trips: List<Trip>, val saved: Boolean, val error: String? = null)
fun persistLive(previous: List<Trip>, next: Trip, write: (List<Trip>) -> Unit): PersistResult {
    val updated=if(previous.any { it.id==next.id }) previous.map { if(it.id==next.id) next else it } else previous+next
    return try { write(updated); PersistResult(updated,true) } catch(e: Exception) { PersistResult(updated,false,e.message ?: "저장 실패") }
}
