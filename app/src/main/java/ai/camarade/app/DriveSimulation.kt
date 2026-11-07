package ai.camarade.app

import ai.camarade.core.*

sealed class SimEvent {
    /** 일행의 남은 거리가 알림 지점(100km 단위, 50·30·10·5·3·1km, 500m, 곧 도착)을 지남. */
    data class Distance(val memberId: String, val label: String) : SimEvent()
    /** "쉬어가요"를 보낸 차량이 정차함. 그 사람의 쉬어가요 말풍선을 닫는다. */
    data class Stopped(val memberId: String) : SimEvent()
    /** 가상 일행이 보낸 메시지. */
    data class Incoming(val memberId: String, val recipient: String, val text: String) : SimEvent()
}

/**
 * 체험용 가상 주행. 실제 위치·차량 속도가 아니라 출발 후 지난 시간으로 각 차량의 남은 거리를 만든다.
 * 앱을 다시 열면 지난 알림·메시지는 다시 띄우지 않는다.
 */
class DriveSimulation(trip: Trip, now: Long) {
    val tripId=trip.id
    private val startedAt=trip.startedAt ?: now
    val totalKm=demoRouteKm(trip.route)
    private class Car(val id: String, val km: Double) { var paused=0L; var stopAt: Long?=null; var stoppedSince: Long?=null; var last=0.0 }
    private data class Scripted(val at: Long, val memberId: String, val recipient: String, val text: String)
    private val cars=trip.members.mapIndexed { i,m -> Car(m.id,totalKm*(1+OFFSETS[i%OFFSETS.size])) }
    private val farthestKm=cars.maxOf { it.km }
    private val script: List<Scripted>
    private var next=0

    init {
        val ids=trip.members.map { it.id }
        fun at(sec: Int)=startedAt+sec*1000L
        // 시연용 대본: 일행 두 명이 정해진 시각에 메시지를 보낸다. 멤버가 없으면 그 줄은 건너뜀.
        script=listOfNotNull(
            ids.getOrNull(2)?.let { Scripted(at(15),it,"all",OK_TEXT) },
            ids.getOrNull(1)?.let { Scripted(at(40),it,"me",OK_TEXT) },
            ids.getOrNull(1)?.let { Scripted(at(65),it,"all",REST_TEXT) },
            ids.getOrNull(1)?.let { Scripted(at(140),it,"me","다음 휴게소에서 화장실 들렀다 갈게요") }
        )
        cars.forEach { it.last=remaining(it,now) }
        next=script.count { it.at<=now }
    }
    private fun moving(c: Car, now: Long): Double {
        val stopped=c.stoppedSince?.let { now-it } ?: 0L
        return ((now-startedAt-c.paused-stopped)/1000.0).coerceAtLeast(0.0)
    }
    private fun remaining(c: Car, now: Long)=simulatedRemaining(c.km,moving(c,now),DEMO_SECONDS*c.km/totalKm)
    fun remainingKm(id: String, now: Long): Double?=cars.find { it.id==id }?.let { remaining(it,now) }
    /** 지도 위치(0=출발, 1=목적지). 가장 뒤처진 차를 출발점에 두어 차량끼리 겹치지 않게 함. */
    fun progress(id: String, now: Long): Double?=remainingKm(id,now)?.let { (1-it/farthestKm).coerceIn(0.0,1.0) }
    fun isStopped(id: String)=cars.find { it.id==id }?.stoppedSince!=null
    /** 쉬어가요를 보낸 차는 잠시 뒤 휴게소에 정차했다가 다시 출발한다(체험용). */
    fun stopSoon(id: String, now: Long) { cars.find { it.id==id }?.let { if(it.stoppedSince==null && it.stopAt==null) it.stopAt=now+STOP_DELAY_MS } }
    /** 쉬어가기를 취소하면 예정된 정차도 취소(이미 정차 중이면 그대로 둠). */
    fun cancelStop(id: String) { cars.find { it.id==id }?.stopAt=null }
    fun tick(now: Long): List<SimEvent> {
        val out=mutableListOf<SimEvent>()
        cars.forEach { c ->
            c.stopAt?.let { if(now>=it) { c.stopAt=null; c.stoppedSince=now; out+=SimEvent.Stopped(c.id) } }
            c.stoppedSince?.let { if(now-it>=STOP_LENGTH_MS) { c.paused+=now-it; c.stoppedSince=null } }
            val km=remaining(c,now)
            // 내 남은 거리는 내 내비가 보여 주므로 말풍선으로 띄우지 않음.
            if(c.id!="me") crossedAlert(c.last,km,c.km)?.let { out+=SimEvent.Distance(c.id,alertLabel(it)) }
            c.last=km
        }
        while(next<script.size && script[next].at<=now) { val s=script[next++]; out+=SimEvent.Incoming(s.memberId,s.recipient,s.text) }
        return out
    }
    companion object {
        /** 출발부터 도착까지 걸리는 체험 시간(초). */
        const val DEMO_SECONDS=240.0
        const val STOP_DELAY_MS=20_000L
        const val STOP_LENGTH_MS=20_000L
        /** 나, 첫째·둘째… 일행이 남은 거리에서 나와 다른 비율. 음수는 나보다 앞서 있음. */
        val OFFSETS=listOf(0.0,-0.12,0.10,-0.06,0.15)
    }
}
