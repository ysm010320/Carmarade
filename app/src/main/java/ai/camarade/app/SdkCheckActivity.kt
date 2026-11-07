package ai.camarade.app

import android.app.Activity
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import ai.pleos.playground.location.fused.FusedLocation
import ai.pleos.playground.location.fused.listener.FusedLocationListener
import ai.pleos.playground.location.fused.model.LocationData
import ai.pleos.playground.navi.constants.NaviErrorCode
import ai.pleos.playground.navi.constants.WaypointIndex
import ai.pleos.playground.navi.data.CurrentLocationInfo
import ai.pleos.playground.navi.data.DestinationArrivedInfo
import ai.pleos.playground.navi.data.RequestWaypointInfo
import ai.pleos.playground.navi.data.RouteInfo
import ai.pleos.playground.navi.data.RouteStartInfo
import ai.pleos.playground.navi.data.RouteWithWaypointsRequest
import ai.pleos.playground.navi.helper.NaviHelper
import ai.pleos.playground.navi.helper.listener.NaviHelperEventListener
import ai.pleos.playground.vehicle.Vehicle
import ai.pleos.playground.vehicle.constant.control.VehicleGear
import ai.pleos.playground.vehicle.listener.GearSelectionListener
import ai.pleos.playground.stt.SpeechToText
import ai.pleos.playground.stt.listener.ResultListener
import ai.pleos.playground.tts.TextToSpeech
import ai.pleos.playground.tts.listener.EventListener
import java.text.SimpleDateFormat
import java.util.*
import ai.pleos.playground.stt.constant.ErrorType as SttError
import ai.pleos.playground.stt.constant.Mode as SttMode
import ai.pleos.playground.stt.listener.OnInitializeListener as SttInit
import ai.pleos.playground.tts.constant.ErrorType as TtsError
import ai.pleos.playground.tts.constant.Mode as TtsMode
import ai.pleos.playground.tts.listener.OnInitializeListener as TtsInit

/**
 * Pleos SDK 점검 화면. 에뮬레이터·실차에서 각 SDK가 실제로 응답하는지 화면과 Logcat(태그 CarmaradeSdk)에 남긴다.
 * 결과 판정: 확인됨(실제 콜백 수신) / 실패(오류 콜백·예외) / 대기(아직 응답 없음).
 */
class SdkCheckActivity : Activity() {
    private enum class State(val label: String, val color: Int) {
        WAIT("대기", Palette.muted), OK("확인됨", Palette.teal), FAIL("실패", android.graphics.Color.rgb(176,48,48))
    }
    private class Check(val title: String, val purpose: String) {
        var state=State.WAIT; var detail="아직 응답이 없습니다."
        lateinit var stateView: TextView; lateinit var detailView: TextView
    }
    private val main=Handler(Looper.getMainLooper())
    private val time=SimpleDateFormat("HH:mm:ss",Locale.KOREA)
    private lateinit var ui: Ui
    private lateinit var logView: TextView
    private val logLines=ArrayDeque<String>()
    private val navi=Check("NaviHelper 3.0.5","경로·경유지 전송, 도착 이벤트, 현재 위치")
    private val vehicleCheck=Check("Vehicle 2.3.2 (기어)","P단 여부로 정차 판단")
    private val fused=Check("FusedLocation 1.1.3","내 차 위치")
    private val stt=Check("SpeechToText 3.3.1 (ON_DEVICE)","음성 메시지 입력")
    private val tts=Check("TextToSpeech 3.3.1 (ON_DEVICE)","받은 메시지 읽어주기")
    private val checks=listOf(navi,vehicleCheck,fused,stt,tts)

    private var naviHelper: NaviHelper?=null
    private var vehicle: Vehicle?=null
    private var fusedLocation: FusedLocation?=null
    private var speechToText: SpeechToText?=null
    private var textToSpeech: TextToSpeech?=null
    private var sttReady=false
    private var ttsReady=false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ui=Ui(this)
        render()
        val missing=listOf(PERM_FUSED,android.Manifest.permission.RECORD_AUDIO).filter { checkSelfPermission(it)!=PackageManager.PERMISSION_GRANTED }
        if(missing.isNotEmpty()) requestPermissions(missing.toTypedArray(),1) else startChecks()
    }
    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        permissions.forEachIndexed { i,p -> log("권한 $p: ${if(grantResults.getOrNull(i)==PackageManager.PERMISSION_GRANTED) "허용" else "거부"}") }
        startChecks()
    }
    override fun onDestroy() {
        main.removeCallbacksAndMessages(null)
        runCatching { naviHelper?.removeListener(naviListener);naviHelper?.release() }
        runCatching { vehicle?.getGear()?.unregisterGearSelection(gearListener);vehicle?.release() }
        runCatching { fusedLocation?.unregisterFusedLocationCallback(fusedListener);fusedLocation?.release() }
        runCatching { speechToText?.removeListener(sttListener);speechToText?.release() }
        runCatching { textToSpeech?.removeEventListener(ttsListener);textToSpeech?.release() }
        super.onDestroy()
    }

    private fun render() {
        val root=ui.col(24).apply { setBackgroundColor(Palette.bg) }
        root.addView(ui.text("PLEOS SDK 점검",12f,Palette.teal,true));root.addView(ui.gap(8))
        root.addView(ui.text("차량 SDK가 실제로 응답하는지 확인합니다",24f,Palette.ink,true));root.addView(ui.gap(6))
        root.addView(ui.text("‘확인됨’은 SDK에서 실제 콜백을 받은 경우만 표시합니다. 로그는 Logcat 태그 ${TAG}에도 남습니다.",13f,Palette.muted))
        root.addView(ui.gap(16))
        val actions=ui.row()
        ui.add(actions,ui.button("경로 요청",true) { requestRoute() },1f,margin=4)
        ui.add(actions,ui.button("TTS 말하기") { speak() },1f,margin=4)
        ui.add(actions,ui.button("STT 듣기") { listen() },1f,margin=4)
        ui.add(actions,ui.button("위치 다시") { lastLocation();naviLocation() },1f,margin=4)
        root.addView(actions);root.addView(ui.gap(16))
        checks.forEach { c ->
            val card=ui.card(padding=16)
            val head=ui.row()
            ui.add(head,ui.text(c.title,17f,Palette.ink,true),1f)
            c.stateView=ui.chip(c.state.label);head.addView(c.stateView)
            card.addView(head);card.addView(ui.gap(4))
            card.addView(ui.text(c.purpose,12f,Palette.muted));card.addView(ui.gap(8))
            c.detailView=ui.text(c.detail,14f,Palette.ink);card.addView(c.detailView)
            root.addView(card);root.addView(ui.gap(10))
            paint(c)
        }
        root.addView(ui.text("로그",15f,Palette.ink,true));root.addView(ui.gap(6))
        logView=ui.text("",12f,Palette.muted).apply { setTextIsSelectable(true) }
        root.addView(logView)
        setContentView(ScrollView(this).apply { addView(root,LinearLayout.LayoutParams(-1,-2)) })
    }
    private fun paint(c: Check) {
        c.stateView.text=c.state.label;c.stateView.setTextColor(if(c.state==State.WAIT) Palette.muted else Palette.white)
        c.stateView.background=ui.box(if(c.state==State.WAIT) Palette.soft else c.state.color,9)
        c.detailView.text=c.detail
    }
    /** OK는 한 번 확인되면 이후 실패로 덮지 않는다(초기화 성공 후 개별 기능 오류는 detail에만 남김). */
    private fun mark(c: Check, state: State, detail: String) = main.post {
        log("[${c.title}] ${state.label}: $detail")
        if(c.state==State.OK && state!=State.OK) c.detail="${c.detail}\n추가 오류: $detail" else { c.state=state;c.detail=detail }
        paint(c)
    }
    private fun log(line: String) {
        Log.i(TAG,line)
        main.post {
            logLines.addFirst("${time.format(Date())}  $line");while(logLines.size>60) logLines.removeLast()
            logView.text=logLines.joinToString("\n")
        }
    }
    private fun guard(c: Check, step: String, block: () -> Unit) {
        try { block() } catch(e: Throwable) { mark(c,State.FAIL,"$step 예외: ${e.javaClass.simpleName}: ${e.message}") }
    }

    private fun startChecks() {
        guard(navi,"초기화") {
            val helper=NaviHelper(applicationContext);naviHelper=helper
            helper.initialize(
                onConnected={ helper.addListener(naviListener);mark(navi,State.OK,"서비스 연결 성공. ‘경로+경유지 요청’으로 경로 적용을 확인하세요.");naviLocation() },
                onFailed={ e -> mark(navi,State.FAIL,"서비스 연결 실패: ${e.javaClass.simpleName}: ${e.message}") }
            )
        }
        guard(vehicleCheck,"초기화") {
            val v=Vehicle(applicationContext);vehicle=v
            v.initialize()
            val gear=v.getGear()
            gear.getCurrentGearSelectionState(
                onComplete={ value -> mark(vehicleCheck,State.OK,"현재 기어: $value") },
                onFailed={ e -> mark(vehicleCheck,State.FAIL,"기어 조회 실패: ${e.javaClass.simpleName}: ${e.message}") }
            )
            gear.registerGearSelection(gearListener)
        }
        guard(fused,"초기화") {
            val f=FusedLocation(applicationContext);fusedLocation=f
            val supported=f.isSupported()
            log("FusedLocation.isSupported() = $supported")
            if(supported!=true) { mark(fused,State.FAIL,"isSupported()=$supported. 차량에 위치 서비스 앱이 없습니다.");return@guard }
            f.initialize()
            f.registerFusedLocationCallback(fusedListener)
            lastLocation()
        }
        guard(stt,"초기화") {
            val s=SpeechToText(applicationContext,SttMode.ON_DEVICE);speechToText=s
            s.initialize(object: SttInit {
                override fun onSuccess() { sttReady=true;s.addListener(sttListener);mark(stt,State.OK,"ON_DEVICE 초기화 성공. ‘STT 듣기’로 인식을 확인하세요.") }
                override fun onFailure(errorType: SttError) { mark(stt,State.FAIL,"초기화 실패: $errorType") }
            })
        }
        guard(tts,"초기화") {
            val t=TextToSpeech(applicationContext,TtsMode.ON_DEVICE);textToSpeech=t
            t.initialize(object: TtsInit {
                override fun onSuccess() { ttsReady=true;t.addEventListener(ttsListener);mark(tts,State.OK,"ON_DEVICE 초기화 성공. ‘TTS 말하기’로 발화를 확인하세요.") }
                override fun onFailure(errorType: TtsError) { mark(tts,State.FAIL,"초기화 실패: $errorType") }
            })
        }
        main.postDelayed({ checks.filter { it.state==State.WAIT }.forEach { mark(it,State.FAIL,"10초 동안 응답이 없습니다(서비스 미설치·미승인 가능).") } },10_000)
    }

    private fun requestRoute() = guard(navi,"경로 요청") {
        val helper=naviHelper ?: return@guard log("NaviHelper가 아직 없습니다.")
        log("requestRouteWithWaypoints 전송: 42dot(목적지), 판교 현대백화점(경유지 1)")
        helper.requestRouteWithWaypoints(RouteWithWaypointsRequest(
            destination=RouteInfo(latitude=37.48544722,longitude=127.03636666,poiName="42dot",poiId="36khp37hh8gqcm5nqhe5",address="서울시 강남구 남부순환로 2621",poiSubId="0"),
            waypoints=listOf(RequestWaypointInfo(latitude=37.41275833,longitude=127.09481944,poiName="판교 현대백화점",waypointIndex=WaypointIndex.FIRST,poiId="36khp2hdj2cfcm5nqhe3",address="경기도 성남시 수정구 창업로 0번길 6",poiSubId="0"))
        ))
    }
    private fun speak() = guard(tts,"발화") {
        val t=textToSpeech
        if(t==null || !ttsReady) { log("TTS가 아직 준비되지 않았습니다.");return@guard }
        t.speak("민지님이 보낸 메시지입니다. 다음 휴게소에서 잠깐 쉬어 가요.")
    }
    private fun listen() = guard(stt,"인식 시작") {
        val s=speechToText
        if(s==null || !sttReady) { log("STT가 아직 준비되지 않았습니다.");return@guard }
        s.request()
    }
    private fun lastLocation() = guard(fused,"마지막 위치") {
        val f=fusedLocation ?: return@guard log("FusedLocation이 아직 없습니다.")
        f.getLastKnownLocation(object: FusedLocationListener {
            override fun onChanged(location: LocationData?) { if(location==null) log("FusedLocation 마지막 위치 없음(null)") else mark(fused,State.OK,"마지막 위치: ${describe(location)}") }
            override fun onError(exception: Exception) { mark(fused,State.FAIL,"마지막 위치 오류: ${exception.javaClass.simpleName}: ${exception.message}") }
        })
    }
    /** 결과는 NaviHelperEventListener.onCurrentLocationInfo로 온다. */
    private fun naviLocation() = guard(navi,"내비 현재 위치") {
        val helper=naviHelper ?: return@guard log("NaviHelper가 아직 없습니다.")
        log("NaviHelper.getCurrentLocationInfo() 요청");helper.getCurrentLocationInfo()
    }
    private fun describe(l: LocationData) = l.toString()

    private val naviListener=object: NaviHelperEventListener {
        override fun onRouteStarted(routeStartInfo: RouteStartInfo) { mark(navi,State.OK,"onRouteStarted: $routeStartInfo") }
        override fun onRoutingCompleted() { mark(navi,State.OK,"onRoutingCompleted 수신(경로 적용 완료)") }
        override fun onCurrentLocationInfo(currentLocationInfo: CurrentLocationInfo) { mark(navi,State.OK,"onCurrentLocationInfo: $currentLocationInfo") }
        override fun onDestinationArrived(destinationArrivedInfo: DestinationArrivedInfo) { log("NaviHelper onDestinationArrived: $destinationArrivedInfo") }
        override fun onError(errorCode: NaviErrorCode) { mark(navi,State.FAIL,"onError: $errorCode") }
    }
    private val gearListener=object: GearSelectionListener {
        override fun onSelected(gear: VehicleGear?) { mark(vehicleCheck,State.OK,"기어 변경 이벤트: $gear") }
        override fun onFailed(e: Exception) { mark(vehicleCheck,State.FAIL,"기어 리스너 오류: ${e.javaClass.simpleName}: ${e.message}") }
    }
    private val fusedListener=object: FusedLocationListener {
        private var count=0
        override fun onChanged(location: LocationData?) {
            location ?: return
            count++
            // 위치는 약 1초마다 오므로 첫 수신과 10번째마다만 로그에 남기고, 카드는 매번 갱신한다.
            if(count==1 || count%10==0) mark(fused,State.OK,"위치 수신 ${count}회째: ${describe(location)}")
            else main.post { fused.detail="위치 수신 ${count}회째: ${describe(location)}";paint(fused) }
        }
        override fun onError(exception: Exception) { mark(fused,State.FAIL,"위치 오류: ${exception.javaClass.simpleName}: ${exception.message}") }
    }
    private val sttListener=object: ResultListener {
        override fun onReady() { log("STT onReady") }
        override fun onStartedRecognition() { log("STT onStartedRecognition") }
        override fun onUpdated(stt: String, completed: Boolean) { if(completed) mark(this@SdkCheckActivity.stt,State.OK,"인식 결과: $stt") else log("STT 중간 결과: $stt") }
        override fun onUpdatedEpdData(on: Long, off: Long) = Unit
        override fun onUpdatedRms(rms: Double) = Unit
        override fun onEndedRecognition() { log("STT onEndedRecognition") }
        override fun onError(errorType: SttError) { mark(stt,State.FAIL,"인식 오류: $errorType") }
    }
    private val ttsListener=object: EventListener {
        override fun onReady() { log("TTS onReady") }
        override fun onStart() { log("TTS onStart") }
        override fun onUpdatedRms(rms: Double) = Unit
        override fun onDone() { mark(tts,State.OK,"발화 완료(onDone)") }
        override fun onStop() { log("TTS onStop") }
        override fun onError(errorType: TtsError) { mark(tts,State.FAIL,"발화 오류: $errorType") }
    }

    companion object {
        const val TAG="CarmaradeSdk"
        const val PERM_FUSED="pleos.car.permission.FUSED_LOCATION"
    }
}
