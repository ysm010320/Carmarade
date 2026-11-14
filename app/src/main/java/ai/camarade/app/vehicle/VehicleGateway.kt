package ai.camarade.app.vehicle

import ai.camarade.core.*

/** The UI cannot interpret a request as a successful real navigation session. */
sealed class NavigationResult {
    data class Simulated(val version: Int) : NavigationResult()
    data class Applied(val version: Int) : NavigationResult()
    data class Unavailable(val reason: String) : NavigationResult()
}
interface VehicleGateway {
    val label: String
    fun acceptRoute(route: RoutePlan, completion: (NavigationResult) -> Unit)
    fun position(memberIndex: Int, now: Long): Position?
    fun close()
}
class DemoVehicleGateway : VehicleGateway {
    override val label = "체험 모드 · 실제 길 안내 아님"
    override fun acceptRoute(route: RoutePlan, completion: (NavigationResult) -> Unit) { completion(NavigationResult.Simulated(route.version)) }
    override fun position(memberIndex: Int, now: Long) = Position(37.56 + memberIndex * 0.012,128.33 + memberIndex * 0.019,now)
    override fun close() = Unit
}
