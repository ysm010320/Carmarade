package ai.camarade.app

import android.app.Activity
import android.app.AlertDialog
import android.content.*
import android.content.res.Configuration
import android.graphics.Color
import android.os.Bundle
import android.speech.RecognizerIntent
import android.text.Editable
import android.text.TextWatcher
import android.view.*
import android.widget.*
import ai.camarade.core.*
import ai.camarade.app.vehicle.*
import ai.camarade.app.net.*
import java.text.SimpleDateFormat
import java.util.*

class MainActivity : Activity() {
    private lateinit var ui: Ui
    private lateinit var store: TripStore
    private val gateway: VehicleGateway = DemoVehicleGateway()
    private var trips=listOf<Trip>()
    private var selectedId=""
    private var screen="home"
    private var target="all"
    private var loadError: String?=null
    private var saveError: String?=null
    private var draftName=""
    private var draftType=100
    private var draftDestination=Catalog.places.first()
    private val reviewDrafts=mutableMapOf<String,String>()
    // 음성 읽어주기(Pleos TTS 우선, 안 되면 Android TTS)와 주행 중 제한 상태.
    private var speech: SpeechOut?=null
    private var drivingState: DrivingState?=null
    // Pleos 내비 지도 화면 열기(앱 안에 넣는 지도 API는 Pleos에 없음).
    private var naviMap: PleosNaviMap?=null
    private var moving=false
    private var sound=false
    private lateinit var content: LinearLayout
    // 같은 화면을 다시 그릴 때 스크롤 위치를 유지하기 위해 기억해 둠(버튼을 누를 때마다 맨 위로 튀지 않게).
    private var scrollView: ScrollView?=null
    private var shownScreen=""
    private val current: Trip? get()=trips.find { it.id==selectedId }
    private val driving: Boolean get()=trips.any { it.phase==Phase.DRIVING }
    private var voiceTripId: String?=null
    private var voiceRecipient="all"
    // 주행 체험 전체 화면. 말풍선이 유지되도록 주행 화면에 있는 동안 같은 화면을 계속 씀.
    private var driveView: DriveScreen?=null
    private var sim: DriveSimulation?=null
    // 온라인 방: 서버 연결, 실시간 연결, 내 위치, 받은 일행 위치.
    private lateinit var server: RoomServer
    private var link: RoomServer.LiveLink?=null
    private var linkTripId: String?=null
    private var linkConnected=false
    private var myLocation: MyLocation?=null
    private var myFix: GeoPoint?=null
    private var myHeading: Double?=null
    private val livePositions=mutableMapOf<String,LivePoint>()
    private val liveLastKm=mutableMapOf<String,Double>()
    private var lastSentAt=0L
    private var lastSentMode: String?=null
    private var draftOnline=true
    private var draftPublic=true
    // 모집 글·출발 시간(공개 온라인 방)
    private var draftNote=""
    private var draftDepart: Long?=null
    // 커뮤니티 목록
    private var communityRooms: List<RemoteRoom>?=null
    private var communityQuery=""
    private var communityLoading=false
    private var communityError: String?=null
    private val ticker=android.os.Handler(android.os.Looper.getMainLooper())
    private var ticking=false
    private val tick=object: Runnable { override fun run() { if(onTick()) ticker.postDelayed(this,1000) else ticking=false } }
    private val driveHost=object: DriveScreen.Host {
        override fun quick(text: String, recipient: String)=send(text,recipient)
        override fun voiceTo(recipient: String) { target=recipient;voice() }
        override fun call(recipient: String)=toast("통화 기능은 준비 중이에요.")
        override fun openPrivacy()=navigate("privacy")
        override fun openNaviMap() {
            val t=trips.find { it.phase==Phase.DRIVING } ?: return
            val m=naviMap ?: return toast("Pleos 내비를 쓸 수 없는 기기예요.")
            m.showRoute(t.id,t.route) { toast(it) }
        }
        override fun toggleSound() {
            if(speech?.ready!=true) { toast("음성 엔진을 준비하고 있어요. 잠시 후 다시 눌러 주세요.");return }
            // 켜고 끌 때 무엇이 바뀌는지 소리와 화면 알림으로 함께 설명함(끌 때는 꺼지기 전에 마지막으로 읽어 줌).
            val explain=if(!sound) "음성 읽어주기를 켰어요. 일행이 보낸 메시지와 남은 거리를 소리로 읽어 드릴게요."
                else "음성 읽어주기를 껐어요. 메시지는 화면 말풍선과 메시지 기록에서 볼 수 있어요."
            speech?.stop();speech?.say(explain)
            sound=!sound;render();toast(explain)
        }
        override fun finishTrip()=confirmFinish()
        override fun showHistory()=history()
        override fun cancelRest() { sim?.cancelStop("me") }
        override fun remainingKm(memberId: String)=if(onlineDriving!=null) onlineRemaining(memberId) else sim?.remainingKm(memberId,System.currentTimeMillis())
        override fun progress(memberId: String)=if(onlineDriving!=null) null else sim?.progress(memberId,System.currentTimeMillis())
        override fun livePosition(memberId: String): LivePoint?=if(onlineDriving==null) null
            else if(memberId=="me") myFix?.let { LivePoint(it,false) } else livePositions[memberId]
    }
    /**
     * 큰 차량 화면에서 앱 전체(글자·버튼·아이콘·말풍선·지도 표시)를 같은 비율로 키움.
     * 화면 밀도(densityDpi)만 올리므로 dp·sp로 정한 모든 크기가 함께 커지고, 요소 사이 비율은 그대로 유지된다.
     */
    override fun attachBaseContext(base: Context) {
        super.attachBaseContext(base)
        val c=base.resources.configuration
        val scale=uiScale(c.screenWidthDp,c.screenHeightDp)
        if(scale>1.01f) applyOverrideConfiguration(Configuration().apply { densityDpi=(c.densityDpi*scale).toInt() })
    }

    private val onlineDriving: Trip? get()=trips.find { it.phase==Phase.DRIVING && it.online }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Palette.apply(isNight(resources.configuration))
        window.decorView.addOnLayoutChangeListener { v,l,_,r,_,_,_,_,_ ->
            val w=r-l
            if(w>0 && w!=windowWidthPx) { val was=narrowWindow();windowWidthPx=w;if(narrowWindow()!=was) v.post { render() } }
        }
        ui=Ui(this);store=TripStore(this);server=RoomServer(this)
        val loaded=store.load();trips=loaded.trips;loadError=loaded.error
        selectedId=savedInstanceState?.getString("selected") ?: trips.firstOrNull()?.id.orEmpty()
        screen=savedInstanceState?.getString("screen") ?: "home"
        trips.find { it.phase==Phase.DRIVING }?.let { selectedId=it.id;screen="drive" }
        speech=SpeechOut(this) { }
        naviMap=PleosNaviMap(this).also { it.onArrived={ trips.find { t -> t.phase==Phase.DRIVING }?.let { t -> arrive(t) } } }
        // 차가 움직이면(주행 최적화 필요) 주행 화면·위치공유 설정만 보여 줌.
        drivingState=DrivingState.attach(this) { m -> moving=m;render() }
        moving=drivingState?.restricted ?: false
        render()
    }
    override fun onSaveInstanceState(out: Bundle) { out.putString("selected",selectedId);out.putString("screen",screen);super.onSaveInstanceState(out) }
    override fun onConfigurationChanged(config: Configuration) { super.onConfigurationChanged(config);Palette.apply(isNight(config));render() }
    private fun isNight(c: Configuration)=(c.uiMode and Configuration.UI_MODE_NIGHT_MASK)==Configuration.UI_MODE_NIGHT_YES
    override fun onDestroy() { stopLive();ticker.removeCallbacks(tick);speech?.release();naviMap?.release();driveView?.release();drivingState?.release();gateway.close();super.onDestroy() }
    @Deprecated("Legacy Android activity back handling")
    override fun onBackPressed() { if(driving) navigate("drive") else if(screen!="home") navigate("home") else super.onBackPressed() }
    /**
     * 화면 아래 알림. Pleos는 앱의 Toast를 화면에 띄우지 않아서(에뮬레이터에서 확인)
     * "후기를 저장했어요" 같은 안내가 보이지 않았음. 앱이 직접 창 맨 위 층에 그린다.
     * setContentView로 화면을 다시 그려도 남도록 decorView에 붙인다.
     */
    private var notice: TextView?=null
    // 창 너비(px). 앱이 만든 설정값(screenWidthDp)은 창 크기를 바꿔도 갱신되지 않아서 실제 창 너비를 직접 본다.
    private var windowWidthPx=0
    /** 절반 폭 창(Pleos 2칸 분할)인지. 배율을 적용한 dp 기준 720 미만이면 좁은 레이아웃을 쓴다. */
    private fun narrowWindow(): Boolean {
        val widthDp=if(windowWidthPx>0) windowWidthPx/resources.displayMetrics.density else resources.configuration.screenWidthDp.toFloat()
        return widthDp<720f
    }
    private val hideNotice=Runnable { notice?.animate()?.alpha(0f)?.translationY(ui.d(12).toFloat())?.setDuration(220)?.withEndAction { notice?.visibility=View.GONE }?.start() }
    private fun toast(message: String) {
        val v=notice ?: TextView(this).apply {
            textSize=17f;setTextColor(Palette.bg);gravity=Gravity.CENTER;background=ui.box(Palette.ink,16)
            setPadding(ui.d(24),ui.d(14),ui.d(24),ui.d(14));maxWidth=ui.d(620);elevation=ui.d(8).toFloat()
            accessibilityLiveRegion=View.ACCESSIBILITY_LIVE_REGION_POLITE
            (window.decorView as FrameLayout).addView(this,FrameLayout.LayoutParams(-2,-2,Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL).apply { bottomMargin=ui.d(28) })
        }.also { notice=it }
        v.text=message;v.visibility=View.VISIBLE;v.bringToFront()
        v.animate().cancel();v.alpha=0f;v.translationY=ui.d(12).toFloat()
        v.animate().alpha(1f).translationY(0f).setDuration(220).start()
        v.removeCallbacks(hideNotice);v.postDelayed(hideNotice,3500)
    }
    private fun say(message: String) { if(sound) speech?.say(message) }
    private fun update(next: Trip, after: String?=null): Boolean {
        val result=persistLive(trips,next,store::save)
        trips=result.trips;saveError=result.error
        if(after!=null) screen=after
        render()
        if(!result.saved) toast("변경은 적용됐지만 저장하지 못했어요. 위치 숨기기와 종료는 적용되었습니다.")
        return result.saved
    }
    private fun action(block: () -> Unit) { try { block() } catch(e: Exception) { toast(e.message ?: "다시 시도해 주세요.") } }
    private fun mutate(after: String?=null, transform: (Trip)->Trip): Boolean = try {
        current?.let { update(transform(it),after) } ?: false
    } catch(e: Exception) { toast(e.message ?: "변경하지 못했어요.");false }
    private fun watch(edit: EditText, changed: (String)->Unit) {
        edit.addTextChangedListener(object: TextWatcher {
            override fun beforeTextChanged(s:CharSequence?,start:Int,count:Int,after:Int) {}
            override fun onTextChanged(s:CharSequence?,start:Int,before:Int,count:Int) { changed(s.toString()) }
            override fun afterTextChanged(s:Editable?) {}
        })
    }
    private fun navigate(next: String) {
        // 주행 중에도 정차해 있으면 커뮤니티는 볼 수 있음(참여는 동행을 마친 뒤).
        if(driving && next !in listOf("drive","privacy","community")) { toast("주행 체험 중에는 동행 화면을 사용해 주세요.");screen="drive" }
        else screen=next
        render()
    }
    private fun select(trip: Trip) { selectedId=trip.id;screen=when(trip.phase) { Phase.PLANNING->"trip";Phase.DRIVING->"drive";Phase.FINISHED->"record" };render() }
    /** 화면 제목. 영문 소제목은 쓰지 않는다([kicker]는 호출부 호환용). 보조 글은 한 줄만. */
    private fun title(@Suppress("UNUSED_PARAMETER") kicker: String, title: String, subtitle: String="") {
        content.addView(ui.text(title,30f,Palette.ink,true))
        if(subtitle.isNotEmpty()) { content.addView(ui.gap(6));content.addView(ui.text(subtitle,16f,Palette.muted)) }
        content.addView(ui.gap(22))
    }
    private fun sectionTitle(label: String) { content.addView(ui.gap(14));content.addView(ui.text(label,20f,Palette.ink,true));content.addView(ui.gap(12)) }
    private fun note(label: String) { content.addView(ui.gap(10));content.addView(ui.text(label,14f,Palette.muted));content.addView(ui.gap(10)) }
    private fun map(t: Trip,height: Int=265): View = RouteMapView(this,t,gateway).apply { layoutParams=LinearLayout.LayoutParams(-1,ui.d(height));clipToOutline=true;background=ui.box(Palette.soft,20) }
    private fun render() {
        val live=current
        if(live?.phase==Phase.DRIVING && screen=="trip") screen="drive"
        if(screen=="drive") {
            if(live?.phase==Phase.DRIVING) { showDrive(live);return }
            screen=if(live?.phase==Phase.FINISHED) "record" else "trip"
        }
        driveView?.release();driveView=null
        // 출발 전 화면에서 보던 방이 아니면 실시간 연결을 닫음(주행 중인 온라인 방은 유지).
        if(link!=null && onlineDriving==null && !(screen=="trip" && current?.id==linkTripId)) stopLive()
        val keepY=if(screen==shownScreen) scrollView?.scrollY ?: 0 else 0
        val root=ui.col();root.setBackgroundColor(Palette.bg)
        // 위쪽 한 줄: 이름, 탭, 연결 상태. 왼쪽 세로 메뉴 대신 Pleos처럼 위쪽 탭을 쓴다.
        // 절반 폭 창(Pleos 2칸 분할)에서는 이름·상태 칩을 빼고 탭 다섯 개를 똑같은 폭으로 한 줄에 둔다. 그렇지 않으면 뒤의 탭이 잘려 나간다.
        val narrow=narrowWindow()
        val top=ui.row().apply { setPadding(ui.d(if(narrow) 8 else 24),ui.d(2),ui.d(if(narrow) 8 else 24),0);setBackgroundColor(Palette.surface) }
        if(!narrow) top.addView(ui.text("카마라드",20f,Palette.ink,true).apply { setPadding(0,0,ui.d(26),0) })
        val links=listOf("home" to "내 동행","community" to "커뮤니티","trip" to "함께 달리기","records" to "주행 기록","privacy" to "설정")
        val icons=mapOf("home" to R.drawable.ic_home,"community" to R.drawable.ic_chat,"trip" to R.drawable.ic_car,"records" to R.drawable.ic_history,"privacy" to R.drawable.ic_settings)
        val tabs=ui.row()
        links.forEach { (key,label) ->
            val active=key==screen || (key=="trip" && screen=="drive") || (key=="records" && screen=="record") || (key=="home" && screen=="create")
            val tabView=ui.tab(label,active,icons.getValue(key),narrow) {
                if(key=="trip" && current?.phase==Phase.FINISHED) { selectedId=trips.firstOrNull { it.phase!=Phase.FINISHED }?.id.orEmpty() }
                if(key=="community") communityRooms=null // 들어올 때마다 새 목록
                navigate(if(key=="trip" && current?.phase==Phase.DRIVING) "drive" else key)
            }
            if(narrow) tabs.addView(tabView,LinearLayout.LayoutParams(0,-2,1f)) else tabs.addView(tabView)
        }
        if(narrow) top.addView(tabs,LinearLayout.LayoutParams(0,-2,1f))
        else { top.addView(HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled=false;addView(tabs) },LinearLayout.LayoutParams(0,-2,1f));top.addView(ui.chip(gateway.label)) }
        root.addView(top)
        root.addView(View(this).apply { setBackgroundColor(Palette.line) },LinearLayout.LayoutParams(-1,ui.d(1)))
        // 넓은 화면에서 내용이 끝까지 늘어나지 않게 가운데 정렬로 폭을 제한함.
        val side=maxOf(ui.d(24),(resources.displayMetrics.widthPixels-ui.d(940))/2)
        content=ui.col().apply { setPadding(side,ui.d(26),side,ui.d(36)) }
        val scroll=ScrollView(this).apply { isFillViewport=true;addView(content);clipToPadding=false }
        root.addView(scroll,LinearLayout.LayoutParams(-1,0,1f));setContentView(root)
        if(saveError!=null) {
            val warning=ui.card(Palette.accentSoft,16)
            warning.addView(ui.text("저장하지 못한 변경이 있어요. 앱을 닫으면 사라질 수 있습니다.",14f,Palette.ink,true));warning.addView(ui.gap(10))
            warning.addView(ui.button("저장 다시 시도") { action { store.save(trips);saveError=null;render();toast("변경 내용을 저장했어요.") } })
            content.addView(warning);content.addView(ui.gap(18))
        }
        if(moving && screen!="privacy") parkedOnly()
        else when(screen) { "home"->home();"community"->community();"trip"->planner();"privacy"->privacy();"records"->records();"record"->record();"create"->create();else->home() }
        shownScreen=screen;scrollView=scroll
        // 첫 프레임을 그리기 전에 위치를 되돌려 맨 위가 잠깐 보이는 깜빡임을 막음. 내용이 짧아졌으면 ScrollView가 끝에 맞춰 줌.
        if(keepY>0) scroll.viewTreeObserver.addOnPreDrawListener(object: ViewTreeObserver.OnPreDrawListener {
            override fun onPreDraw(): Boolean { scroll.viewTreeObserver.removeOnPreDrawListener(this);scroll.scrollTo(0,keepY);return true }
        })
    }
    /** 차가 움직이는 동안 입력·설정 화면 대신 보여 줌. Pleos는 주행 중 설정(NO_SETUP)을 제한함. */
    private fun parkedOnly() {
        title("","운전에 집중해요","모임 만들기·경로 설정·후기 작성은 정차한 뒤에 할 수 있어요.")
        if(driving) { content.addView(ui.button("주행 화면으로 돌아가기",true) { navigate("drive") });content.addView(ui.gap(12)) }
        content.addView(ui.button("위치공유 설정") { navigate("privacy") })
    }
    private fun home() {
        val head=ui.row()
        ui.add(head,ui.text("내 동행",30f,Palette.ink,true),1f)
        head.addView(ui.button("+  새 동행 만들기",true) { navigate("create") }.apply { minHeight=ui.d(60);setPadding(ui.d(26),0,ui.d(26),0) })
        content.addView(head);content.addView(ui.gap(22))
        loadError?.let { note(it) }
        val active=trips.filter { it.phase!=Phase.FINISHED }
        if(active.isEmpty()) content.addView(ui.group(ui.listRow("아직 준비된 동행이 없어요","새 동행을 만들거나 아래에서 방에 참여해 보세요.")))
        if(active.isNotEmpty()) content.addView(ui.group(*active.map { t ->
            val people=t.members.joinToString(", ") { if(it.id=="me") "나" else if(t.online) it.name else "${it.name}(가상)" }
            ui.listRow(t.name,"${t.route.destination.name}  ·  ${t.members.size}대\n$people",
                tags=listOfNotNull(if(t.phase==Phase.DRIVING) "주행 중" to true else null,if(t.online) "온라인" to true else null,t.type to false),
                cta=if(t.phase==Phase.DRIVING) "이어가기" else "준비하기") { select(t) }
        }.toTypedArray()))
        sectionTitle("방 참여");roomsCard()
    }
    /** 방 참여: 초대 코드 입장과 모집 중인 방 찾기만 둔다. 목적지로 찾기는 커뮤니티에, 이름·서버는 설정에 있다. */
    private fun roomsCard() {
        content.addView(ui.group(
            ui.listRow("초대 코드로 입장","방장에게 받은 6자리 코드") { withAccount { askCode() } },
            ui.listRow("방 찾기","모집 중인 방과 같은 목적지 방") { communityRooms=null;navigate("community") }
        ))
    }
    private fun create() {
        title("","새 동행 만들기","크루는 오래 함께할 모임, 동행은 이번 여행을 위한 모임이에요.")
        val c=ui.card();c.addView(ui.text("모임 이름",15f,Palette.muted,true));c.addView(ui.gap(10))
        val name=ui.edit("예: 주말엔, 동해",draftName);name.filters=arrayOf(android.text.InputFilter.LengthFilter(40));watch(name) { draftName=it };c.addView(name);c.addView(ui.gap(20))
        val types=RadioGroup(this).apply { orientation=RadioGroup.HORIZONTAL }
        listOf("크루","동행").forEachIndexed { i,s -> types.addView(RadioButton(this).apply { id=100+i;text=s;textSize=18f;minHeight=ui.d(52);setPadding(0,0,ui.d(30),0) }) };types.check(draftType);types.setOnCheckedChangeListener { _,id->draftType=id };c.addView(types)
        c.addView(ui.gap(20));c.addView(ui.text("첫 목적지",15f,Palette.muted,true));c.addView(ui.gap(10))
        var destination=draftDestination
        val dest=ui.button(destination.name) { };dest.setOnClickListener { choosePlace(emptyList()) { destination=it;draftDestination=it;dest.text=it.name } };c.addView(dest)
        c.addView(ui.gap(20))
        val online=CheckBox(this).apply { text="온라인 방으로 만들기 (다른 차량이 참여)";textSize=16f;isChecked=draftOnline;minHeight=ui.d(48) }
        val public=CheckBox(this).apply { text="공개 방 (같은 목적지를 찾는 사람에게 보여요)";textSize=16f;isChecked=draftPublic;minHeight=ui.d(48);isEnabled=draftOnline }
        online.setOnCheckedChangeListener { _,on -> draftOnline=on;public.isEnabled=on };public.setOnCheckedChangeListener { _,on -> draftPublic=on }
        c.addView(online);c.addView(public)
        // 공개 방은 커뮤니티에 모집 글로 올라감: 출발 시간과 한 줄 소개.
        val recruit=ui.col().apply { visibility=if(draftOnline && draftPublic) View.VISIBLE else View.GONE }
        recruit.addView(ui.gap(14));recruit.addView(ui.text("모집 글",15f,Palette.muted,true));recruit.addView(ui.gap(10))
        val noteEdit=ui.edit("예: 휴게소에서 커피 한잔하고 가요 (선택)",draftNote,true).apply { filters=arrayOf(android.text.InputFilter.LengthFilter(200));minLines=2 }
        watch(noteEdit) { draftNote=it };recruit.addView(noteEdit);recruit.addView(ui.gap(12))
        val depart=ui.button(departLabel(draftDepart)) { }
        depart.setOnClickListener { pickDepart { at -> draftDepart=at;depart.text=departLabel(at) } }
        recruit.addView(depart)
        c.addView(recruit)
        val showRecruit={ recruit.visibility=if(draftOnline && draftPublic) View.VISIBLE else View.GONE }
        online.setOnCheckedChangeListener { _,on -> draftOnline=on;public.isEnabled=on;showRecruit() }
        public.setOnCheckedChangeListener { _,on -> draftPublic=on;showRecruit() }
        c.addView(ui.gap(22));c.addView(ui.button("동행 만들기",true) { action {
            val t=Trip.create(name.text.toString(),if(types.checkedRadioButtonId==100) "크루" else "동행",destination)
            fun done(made: Trip) { selectedId=made.id;draftName="";draftType=100;draftDestination=Catalog.places.first();draftNote="";draftDepart=null;update(made,"trip") }
            if(!online.isChecked) { done(t);return@action }
            withAccount {
                val pub=public.isChecked
                server.createRoom(t.name,t.type=="크루",pub,t.route,if(pub) draftNote.trim() else null,if(pub) draftDepart else null) { r -> r.fold(
                    { room -> done(t.copy(roomId=room.id,roomCode=room.inviteCode,roomHost=true,roomHostId=room.hostId));toast("방을 만들었어요. 초대 코드: ${room.inviteCode}") },
                    { e -> toast(e.message ?: "방을 만들지 못했어요.") }) }
            }
        } });content.addView(c);note("위치 공유는 기본으로 꺼져 있어요. 만든 뒤 직접 켤 수 있어요.")
    }
    private fun planner() {
        val t=current
        if(t==null || t.phase==Phase.FINISHED) { title("","준비 중인 동행이 없어요","새 동행을 만들면 여기서 일행과 경로를 정해요.");content.addView(ui.button("새 동행 만들기",true) { navigate("create") });return }
        if(t.phase==Phase.DRIVING) { screen="drive";content.post { render() };return }
        // 온라인 방은 출발 전에도 실시간 연결: 새 멤버·수락·경로 변경이 바로 보인다.
        if(t.online) connectRoom(t)
        title("",t.name,if(t.online) "각자 자기 차에서 경로를 수락하고 출발해요." else "목적지와 경유지를 확인하고 각자 수락해요.")
        // 온라인 방은 방장만 경로를 바꾸고, 바꾸면 서버를 거쳐 멤버에게 전해진다.
        val canEdit=!t.online || t.roomHost
        fun changeRoute(destination: Place, waypoints: List<Place>) {
            if(!t.online) { mutate { it.setRoute("me",destination,waypoints) };return }
            action {
                val next=RoutePlan(destination,waypoints,t.route.version+1)
                server.setRoute(t.roomId!!,next) { r -> r.fold({ mutate { it.setRoute("me",destination,waypoints) } },{ e -> toast(e.message ?: "경로를 바꾸지 못했어요.") }) }
            }
        }
        if(t.online) { inviteCard(t);content.addView(ui.gap(14)) }
        val c=ui.card();val row=ui.row();ui.add(row,ui.text("우리의 목적지",17f,Palette.ink,true),1f);row.addView(ui.chip("경로 v${t.route.version}"));c.addView(row)
        c.addView(ui.gap(16));c.addView(ui.text(t.route.destination.name,26f,Palette.ink,true));c.addView(ui.gap(14))
        if(canEdit) { c.addView(ui.button("목적지 변경") { choosePlace(t.route.waypoints) { p -> changeRoute(p,t.route.waypoints) } });c.addView(ui.gap(14)) }
        t.route.waypoints.forEachIndexed { i,p ->
            val r=ui.row();ui.add(r,ui.text("${i+1}  ${p.name}",17f),1f)
            if(canEdit) r.addView(ui.button("삭제") { changeRoute(t.route.destination,t.route.waypoints.filter { s->s.id!=p.id }) })
            c.addView(r);c.addView(ui.gap(10))
        }
        if(canEdit && t.route.waypoints.size<2) c.addView(ui.button("+  경유지 추가 (${t.route.waypoints.size}/2)") { choosePlace(t.route.waypoints+ t.route.destination) { p -> changeRoute(t.route.destination,t.route.waypoints+p) } })
        if(!canEdit) c.addView(ui.text("목적지와 경유지는 방장이 정해요. 바뀌면 다시 수락해 주세요.",13f,Palette.muted))
        content.addView(c);content.addView(ui.gap(18));content.addView(map(t,225))
        sectionTitle("함께 달릴 ${t.members.size}대")
        content.addView(ui.group(*t.members.map { m ->
            val role=when { m.id=="me" && t.online -> if(t.roomHost) " · 방장" else "";m.id=="me" -> " · 호스트";t.online -> if(m.id==t.roomHostId) " · 방장" else "";else -> " · 가상" }
            val state=when { t.online && m.id!="me" && memberPhase[m.id]=="arrived" -> "도착" to true;t.online && m.id!="me" && memberPhase[m.id]=="driving" -> "출발함" to true
                m.accepted(t.route) -> "수락 완료" to true;else -> "확인 대기" to false }
            ui.listRow("${m.name}$role",m.car,tags=listOf(state))
        }.toTypedArray()));content.addView(ui.gap(12))
        val controls=ui.row();if(!t.online) ui.add(controls,ui.button("초대 코드 보기") { invite(t) },1f,margin=3)
        if(t.online) ui.add(controls,ui.button("새로고침") { refreshRoom(t) },1f,margin=3)
        else if(t.members.size<5) ui.add(controls,ui.button("+ 가상 멤버") { addMember() },1f,margin=3)
        content.addView(controls);content.addView(ui.gap(10))
        if(!t.online && t.members.any { it.id!="me" && !it.accepted(t.route) }) {
            content.addView(ui.button("가상 일행의 경로 수락 시뮬레이션") { mutate { trip ->
                var result=trip
                trip.members.filter { it.id!="me" }.forEach { m -> result=result.acceptRoute(m.id,result.route.version).setSharing(m.id,Sharing.EXACT) }
                result
            } });content.addView(ui.gap(10))
        }
        if(!t.members.first().accepted(t.route)) {
            content.addView(ui.button("이 경로 수락하기",true) {
                gateway.acceptRoute(t.route) { result -> when(result) {
                    is NavigationResult.Simulated -> { if(mutate { it.acceptRoute("me",result.version) }) {
                        if(t.online) { server.setStatus(t.roomId!!,accepted=true);toast("경로를 수락했어요. 일행에게도 알렸어요.") }
                        else toast("체험 경로를 수락했어요. 실제 내비게이션은 실행되지 않습니다.") } }
                    is NavigationResult.Applied -> mutate { it.acceptRoute("me",result.version) }
                    is NavigationResult.Unavailable -> toast(result.reason)
                } }
            });content.addView(ui.gap(10))
        }
        content.addView(ui.button(if(t.online) "출발하기  →" else "동행 주행 체험 시작  →",true) {
            if(mutate("drive") { it.start("me",System.currentTimeMillis()) } && t.online) server.setStatus(t.roomId!!,phase="driving")
        })
        if(t.online) { content.addView(ui.gap(10));content.addView(ui.button("방 나가기") { confirmLeave(t) }) }
        note(if(t.online) (if(linkConnected) "실시간으로 연결돼 있어요. " else "방에 연결하는 중이에요. ")+"출발하면 위치 공유 여부를 물어보고, 도착하면 자동으로 꺼져요."
            else "모두 수락하면 출발할 수 있어요. 실제 주행에서는 각 차량의 내비가 길을 안내해요.")
    }
    /** 온라인 방의 초대 코드를 크게 보여 줌. 말로 불러 주거나 메시지로 보내기 쉽게 복사 버튼을 둔다. */
    private fun inviteCard(t: Trip) {
        val c=ui.card(padding=22)
        val head=ui.row();ui.add(head,ui.text("초대 코드",18f,Palette.ink,true),1f)
        head.addView(ui.chip(if(t.roomHost) "방장" else "멤버"));c.addView(head);c.addView(ui.gap(14))
        val cells=ui.row().apply { gravity=Gravity.CENTER_VERTICAL }
        t.inviteCode.forEach { cells.addView(codeCell(ui,it.toString(),64,80,36f,Palette.accentSoft,Palette.teal)) }
        c.addView(cells);c.addView(ui.gap(10))
        c.addView(ui.text("말로 알릴 땐 “${spokenCode(t.inviteCode)}”처럼 끊어 읽어요.",14f,Palette.muted));c.addView(ui.gap(14))
        val buttons=ui.row()
        ui.add(buttons,ui.button("코드 복사") { copyText("초대 코드",t.inviteCode,"초대 코드를 복사했어요.") },1f,margin=3)
        ui.add(buttons,ui.button("초대 문구 복사") { copyText("카마라드 초대",inviteMessage(t.name,t.route.destination.name,t.inviteCode),"초대 문구를 복사했어요.") },1f,margin=3)
        c.addView(buttons);content.addView(c)
    }
    private fun copyText(label: String, text: String, done: String) {
        (getSystemService(CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText(label,text));toast(done)
    }
    private fun invite(t: Trip) {
        AlertDialog.Builder(this).setTitle("초대 코드 · ${t.inviteCode}")
            .setMessage(if(t.online) "다른 차량에서 ‘초대 코드로 입장’을 누르고 이 코드를 입력하면 같은 방에 들어와요. 공개 방은 ‘같은 목적지 방 찾기’로도 찾을 수 있어요."
                else "이 모임은 기기 안에서만 쓰는 체험 모임이라 다른 차량이 들어올 수 없어요. 새 모임을 ‘온라인 방’으로 만들면 초대 코드로 함께할 수 있어요.")
            .setPositiveButton("코드 복사") { _,_->
                (getSystemService(CLIPBOARD_SERVICE) as android.content.ClipboardManager).setPrimaryClip(ClipData.newPlainText("체험 초대 코드",t.inviteCode));toast("체험 코드를 복사했어요.")
            }.setNegativeButton("닫기",null).show()
    }
    private fun addMember() {
        val i=current?.members?.size ?: return
        val names=listOf("나","지수","하늘","도윤","유진")
        val cars=listOf("내 차량","IONIQ 5","AVANTE","SANTA FE","EV6")
        mutate { it.addMember("me","guest_${UUID.randomUUID()}",names[i],cars[i]) }
    }
    private fun choosePlace(exclude: List<Place>,onSelect: (Place)->Unit) {
        if(driving) { toast("출발 전에 장소를 선택해 주세요.");return }
        val holder=ui.col(20);val input=ui.edit("체험 장소 검색 (8곳)");holder.addView(input);holder.addView(ui.gap(12))
        val results=ui.col();val scroll=ScrollView(this).apply { addView(results) };holder.addView(scroll,LinearLayout.LayoutParams(-1,ui.d(280)))
        val dialog=AlertDialog.Builder(this).setTitle("어디로 갈까요?").setView(holder).setNegativeButton("취소",null).create()
        fun refresh(query: String) {
            results.removeAllViews()
            val places=Catalog.places.filter { p->p.name.contains(query.trim(),ignoreCase=true) && exclude.none { it.id==p.id } }
            if(places.isEmpty()) results.addView(ui.text("선택 가능한 장소가 없어요.",16f,Palette.muted))
            places.forEach { p-> results.addView(ui.button(p.name) { dialog.dismiss();onSelect(p) });results.addView(ui.gap(8)) }
        }
        input.addTextChangedListener(object: TextWatcher { override fun beforeTextChanged(s:CharSequence?,start:Int,count:Int,after:Int){};override fun onTextChanged(s:CharSequence?,start:Int,before:Int,count:Int){refresh(s.toString())};override fun afterTextChanged(s:Editable?){} })
        refresh("");dialog.show()
    }
    private fun showDrive(t: Trip) {
        val v=driveView?.takeIf { it.tripId==t.id } ?: DriveScreen(this,ui,gateway,driveHost).also { driveView?.release();driveView=it }
        if(t.online) { sim=null;startLive(t) } else if(sim?.tripId!=t.id) sim=DriveSimulation(t,System.currentTimeMillis())
        v.bind(t,sound)
        v.status(if(!t.online) "체험 · 가상 일행 · 실제 길 안내 아님" else if(linkConnected) "온라인 방 · ${t.inviteCode} · 연결됨" else "온라인 방 · 연결 중…")
        if(v.parent==null) setContentView(v)
        shownScreen="drive";scrollView=null
        if(!ticking) { ticking=true;ticker.postDelayed(tick,1000) }
    }
    private fun memberName(t: Trip, id: String)=if(id=="me") "나" else t.members.find { it.id==id }?.name ?: "멤버"
    private fun spoken(label: String)=label.replace("km","킬로미터").replace("m","미터")
    /** 1초마다: 가상 일행의 거리 알림·정차·메시지를 처리. 주행 중인 동행이 없으면 멈춤. */
    private fun onTick(): Boolean {
        val t=trips.find { it.phase==Phase.DRIVING } ?: run { sim=null;stopLive();return false }
        val now=System.currentTimeMillis()
        if(t.online) { onlineTick(t);driveView?.refresh();return true }
        val s=sim?.takeIf { it.tripId==t.id } ?: DriveSimulation(t,now).also { sim=it }
        s.tick(now).forEach { e -> when(e) {
            is SimEvent.Distance -> {
                driveView?.showDistance(e.memberId,e.label)
                val name=memberName(t,e.memberId)
                say(if(e.label==ARRIVING_LABEL) "${name}님 곧 도착해요." else "${name}님 ${spoken(e.label)} 남았어요.")
            }
            is SimEvent.Stopped -> driveView?.clearRest(e.memberId)
            is SimEvent.Incoming -> receive(t.id,e)
        } }
        driveView?.refresh()
        return true
    }
    private fun receive(tripId: String, e: SimEvent.Incoming) {
        val t=trips.find { it.id==tripId && it.phase==Phase.DRIVING } ?: return
        val now=System.currentTimeMillis()
        try { update(t.send(e.memberId,e.recipient,e.text,now)) } catch(_: Exception) { return }
        driveView?.showMessage(e.memberId,e.recipient,e.text)
        if(quickKind(e.text)==QuickKind.REST) sim?.stopSoon(e.memberId,now)
        say("${memberName(t,e.memberId)}님, ${e.text}")
    }
    private fun history() {
        val t=current ?: return
        val fmt=SimpleDateFormat("HH:mm",Locale.KOREA)
        val lines=t.messages.reversed().joinToString("\n\n") { m ->
            "${fmt.format(Date(m.at))}  ${memberName(t,m.sender)} → ${if(m.recipient=="all") "모두" else memberName(t,m.recipient)}\n${m.text}"
        }
        AlertDialog.Builder(this).setTitle("메시지 기록")
            .setMessage(if(lines.isEmpty()) "아직 주고받은 메시지가 없어요." else lines+if(t.online) "\n\n같은 방 멤버와 주고받은 메시지예요." else "\n\n체험 기록입니다. 다른 기기로 전송되지 않아요.")
            .setPositiveButton("닫기",null).show()
    }
    private fun confirmFinish() {
        AlertDialog.Builder(this).setTitle("이번 동행을 마칠까요?").setMessage("모든 위치공유가 종료되고 주행 체험 기록이 남아요.")
            .setPositiveButton("종료하기") { _,_-> finishTrip() }.setNegativeButton("계속 달리기",null).show()
    }
    private fun finishTrip() = action {
        val latest=trips.find { it.phase==Phase.DRIVING } ?: return@action;val now=System.currentTimeMillis();val distance=((now-latest.startedAt!!).coerceAtLeast(0)/1000.0)*0.02
        selectedId=latest.id
        if(latest.online) { server.setStatus(latest.roomId!!,phase="arrived");stopLive() }
        if(update(latest.finish("me",now,distance),"record")) say("동행이 종료되었습니다. 위치 공유를 껐어요.")
    }
    /** 내가 보낸 메시지는 말풍선으로만 보여 주고 소리로 다시 읽지 않음. */
    private fun send(text: String, recipient: String=target, mode: InputMode=InputMode.PRESET) {
        action { current?.let { t->
            val now=System.currentTimeMillis()
            update(t.send("me",recipient,text,now,mode))
            if(t.online) link?.sendMessage(recipient,text)
            // 모두에게 보낸 쉬어가요·따봉은 왼쪽 위 버튼 색으로 표시하므로 말풍선을 띄우지 않음.
            if(recipient!="all" || quickKind(text)==QuickKind.TEXT) driveView?.showMessage("me",recipient,text)
            if(quickKind(text)==QuickKind.REST) sim?.stopSoon("me",now)
        } }
    }
    private fun voice() {
        val intent=Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL,RecognizerIntent.LANGUAGE_MODEL_FREE_FORM).putExtra(RecognizerIntent.EXTRA_LANGUAGE,"ko-KR").putExtra(RecognizerIntent.EXTRA_PROMPT,"일행에게 전할 말을 해주세요")
        if(intent.resolveActivity(packageManager)==null) { toast("이 기기는 음성 입력을 지원하지 않아요. 빠른 메시지를 이용해 주세요. Pleos 음성 연결은 다음 단계입니다.");return }
        voiceTripId=current?.id;voiceRecipient=target
        try { startActivityForResult(intent,51) } catch(_:ActivityNotFoundException) { toast("음성 입력을 사용할 수 없어요.") }
    }
    @Deprecated("Legacy recognizer result")
    override fun onActivityResult(requestCode:Int,resultCode:Int,data:Intent?) {
        super.onActivityResult(requestCode,resultCode,data)
        if(requestCode==51 && resultCode==RESULT_OK) {
            val text=data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)?.firstOrNull() ?: return
            if(current?.id!=voiceTripId || current?.phase==Phase.FINISHED) { toast("동행이 변경되어 메시지를 기록하지 않았어요.");return }
            AlertDialog.Builder(this).setTitle("이렇게 전할까요?").setMessage(text)
                .setPositiveButton("체험 기록에 남기기") { _,_-> target=voiceRecipient;send(text,voiceRecipient,InputMode.VOICE) }.setNegativeButton("취소",null).show()
        }
    }
    /** 설정: 위치 공유, 내 정보(이름·차량), 디버그 빌드에서만 서버 주소. */
    private fun privacy() {
        val t=current
        title("","설정","위치 공유와 내 정보를 정해요.")
        sectionTitle("위치 공유")
        if(t==null || t.phase==Phase.FINISHED) content.addView(ui.group(ui.listRow("진행 중인 동행이 없어요","위치 공유는 꺼져 있어요")))
        else {
            val me=t.members.first()
            val choices=listOf(Sharing.OFF to ("위치 숨기기" to "일행에게 내 위치가 보이지 않아요."),
                Sharing.APPROXIMATE to ("대략적인 위치" to "약 1km 단위로 낮춰서 보여요."),Sharing.EXACT to ("정확한 위치" to "같은 모임 일행에게만 정확히 보여요."))
            content.addView(ui.group(*choices.map { (mode,text) -> ui.choiceRow(text.first,text.second,me.sharing==mode) { if(me.sharing!=mode) mutate { it.setSharing("me",mode) } } }.toTypedArray()))
            note(if(t.online) "같은 방 일행에게만 보내요. 동행을 마치거나 앱을 닫으면 서버에서 바로 지워요."
                else "체험 모임이라 가상 좌표만 써요. 동행을 마치면 자동으로 꺼져요.")
        }
        sectionTitle("내 정보")
        val signed=server.signedIn
        val rows=mutableListOf<View>(ui.listRow(if(signed) server.nickname.orEmpty() else "닉네임 정하기",if(signed) server.car.orEmpty() else "방에서 다른 차량에게 보일 이름과 차종",
            action=if(signed) null else ({ askProfile { render() } })))
        // 서버 주소는 개발용 설정이라 디버그 빌드에서만 보인다.
        if((applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE)!=0) rows+=ui.listRow("서버 주소 (개발용)",server.baseUrl) { editServer() }
        content.addView(ui.group(*rows.toTypedArray()))
    }
    private fun records() {
        title("","주행 기록","마친 동행이 여기에 쌓여요.")
        val records=trips.filter { it.phase==Phase.FINISHED }.reversed()
        if(records.isEmpty()) { content.addView(ui.group(ui.listRow("아직 기록이 없어요","동행을 마치면 여기에 쌓여요")));return }
        content.addView(ui.group(*records.map { t -> ui.listRow(t.name,"${t.route.destination.name}  ·  ${t.members.size}대",cta="보기") { select(t) } }.toTypedArray()))
    }
    private fun record() {
        val t=current;val r=t?.record
        if(t==null || r==null) { records();return }
        title("","동행을 마쳤어요",t.name)
        val c=ui.card(padding=26);c.addView(ui.text(t.route.destination.name,28f,Palette.ink,true));c.addView(ui.gap(12));c.addView(ui.text(SimpleDateFormat("yyyy.MM.dd · HH:mm",Locale.KOREA).format(Date(t.endedAt!!)),15f,Palette.muted));c.addView(ui.gap(25))
        val metrics=ui.row()
        listOf(String.format(Locale.KOREA,"%.1f km",r.distanceKm) to "시뮬레이션 거리", "${r.durationSeconds/60}분 ${r.durationSeconds%60}초" to "체험 시간", "${t.members.size}대" to "함께한 차량").forEach { (value,label) ->
            val col=ui.col();col.addView(ui.text(value,26f,Palette.ink,true));col.addView(ui.gap(8));col.addView(ui.text(label,14f,Palette.muted));ui.add(metrics,col,1f)
        };c.addView(metrics);content.addView(c);content.addView(ui.gap(15));content.addView(ui.chip("✓  모든 위치공유가 종료되었어요"));content.addView(ui.gap(20))
        val card=ui.card();card.addView(ui.text("오늘의 한 줄",19f,Palette.ink,true));card.addView(ui.gap(13))
        val review=ui.edit("함께 달린 기분을 남겨보세요.",reviewDrafts[t.id] ?: r.review,true);review.filters=arrayOf(android.text.InputFilter.LengthFilter(500));watch(review) { reviewDrafts[t.id]=it };card.addView(review);card.addView(ui.gap(15));card.addView(ui.button("후기 저장",true) {
            // 저장하면 키보드를 닫고, 저장됐다는 알림을 화면에 띄움.
            (getSystemService(INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager).hideSoftInputFromWindow(review.windowToken,0)
            if(mutate { it.review(review.text.toString()) }) { reviewDrafts.remove(t.id);toast("후기를 저장했어요.") }
        });content.addView(card)
        content.addView(ui.gap(15));content.addView(ui.button("새로운 동행 만들기") { navigate("create") })
        note("이 기록의 거리는 가상 속도 72km/h × 체험 시간으로 계산했습니다. 실제 차량의 주행거리가 아닙니다.")
    }

    // ---------- 커뮤니티(모집) ----------
    /** 같이 갈 사람 찾기: 초대 코드 입장, 모집 글 쓰기, 지금 모집 중인 공개 방 목록. */
    private fun community() {
        title("","같이 갈 사람 찾기","가는 방향이 같은 방에 들어가거나 초대 코드로 입장해요.")
        if(!server.signedIn) {
            val c=ui.card(padding=24)
            c.addView(ui.text("먼저 닉네임을 정해 주세요",20f,Palette.ink,true));c.addView(ui.gap(10))
            c.addView(ui.text("방에서 다른 차량에게 보일 이름과 차종이에요.",15f,Palette.muted));c.addView(ui.gap(16))
            c.addView(ui.button("닉네임 정하기") { askProfile { communityRooms=null;render() } })
            content.addView(c);content.addView(ui.gap(16))
        }
        // 초대 코드 입장과 모집 글 쓰기: 같은 목록 행으로.
        content.addView(ui.group(
            ui.listRow("초대 코드로 입장","방장에게 받은 6자리 코드 · 비공개 방도 들어갈 수 있어요") { withAccount { askCode() } },
            ui.listRow("같이 갈 사람 모집하기","공개 방을 만들고 모집 글을 올려요") { draftOnline=true;draftPublic=true;navigate("create") }
        ))
        // 모집 목록
        val head=ui.row().apply { setPadding(0,ui.d(22),0,ui.d(12)) }
        ui.add(head,ui.text("모집 중인 방",19f,Palette.ink,true),1f)
        head.addView(ui.button("목적지로 찾기") { withAccount { choosePlace(emptyList()) { p -> findRooms(p) } } }.apply { textSize=13f;minHeight=ui.d(40) })
        head.addView(ui.gap(1).apply { layoutParams=LinearLayout.LayoutParams(ui.d(8),1) })
        head.addView(ui.button("새로고침") { communityRooms=null;loadCommunity() }.apply { textSize=13f;minHeight=ui.d(40) })
        content.addView(head)
        val search=ui.row()
        val query=ui.edit("방 이름·목적지·모집 글 검색",communityQuery)
        ui.add(search,query,1f);search.addView(ui.gap(1).apply { layoutParams=LinearLayout.LayoutParams(ui.d(10),1) })
        search.addView(ui.button("검색") { communityQuery=query.text.toString().trim();communityRooms=null;loadCommunity() })
        content.addView(search);content.addView(ui.gap(14))
        if(!server.signedIn) { note("닉네임을 정하면 모집 중인 방이 보여요.");return }
        val rooms=communityRooms
        when {
            rooms==null && communityError!=null -> { note(communityError!!);content.addView(ui.button("다시 불러오기") { communityError=null;loadCommunity() }) }
            rooms==null -> { note("모집 중인 방을 불러오는 중이에요…");loadCommunity() }
            rooms.isEmpty() -> note(if(communityQuery.isEmpty()) "아직 모집 중인 방이 없어요. 첫 모집 글을 올려 보세요." else "‘$communityQuery’에 맞는 방이 없어요.")
            else -> rooms.forEach { communityCard(it) }
        }
        if(driving) note("지금 동행 중이에요. 다른 방 참여는 이번 동행을 마친 뒤에 할 수 있어요.")
    }
    private fun communityCard(room: RemoteRoom) {
        val c=ui.card(padding=18)
        val top=ui.row();ui.add(top,ui.text(room.title,19f,Palette.ink,true),1f)
        top.addView(ui.chip("${room.memberCount}/${room.maxMembers}대",if(room.memberCount>=room.maxMembers) Palette.line else Palette.soft))
        c.addView(top);c.addView(ui.gap(8))
        val via=if(room.waypoints.isNotEmpty()) "  ·  경유 ${room.waypoints.joinToString { it.name }}" else ""
        c.addView(ui.text("→ ${room.destination.name}$via",15f,Palette.teal,true));c.addView(ui.gap(6))
        c.addView(ui.text("${departLabel(room.departAt,short=true)}  ·  방장 ${room.hostName}  ·  ${if(room.kind=="crew") "크루" else "동행"}",13f,Palette.muted))
        room.note?.let { c.addView(ui.gap(10));c.addView(ui.text(it,15f,Palette.ink)) }
        c.addView(ui.gap(14))
        c.addView(ui.button(if(room.joined) "참여 중 · 열기" else "이 방에 참여하기",!room.joined) { joinRoom(room) })
        content.addView(c);content.addView(ui.gap(10))
    }
    private fun loadCommunity() {
        if(communityLoading || !server.signedIn) return
        communityLoading=true
        server.openRooms(communityQuery) { r ->
            communityLoading=false
            r.fold({ communityRooms=it;communityError=null },{ communityRooms=null;communityError=it.message ?: "목록을 불러오지 못했어요." })
            if(screen=="community" && !moving) render()
        }
    }
    /** 다른 동행이 주행 중이면 새 방에 들어가지 않음(동시에 두 방에서 위치를 보내지 않도록). */
    private fun joinBlocker(roomId: String?=null): String? {
        val active=trips.find { it.phase==Phase.DRIVING }
        return if(active!=null && (roomId==null || active.roomId!=roomId)) "지금 동행 중이에요. 이번 동행을 마친 뒤 다른 방에 참여해 주세요." else null
    }
    private fun canJoin(roomId: String?=null): Boolean { val why=joinBlocker(roomId);if(why!=null) toast(why);return why==null }
    /** 코드 입력 화면이 열려 있으면 실패 이유를 그 안에 보여 주려고 [onFail]을 받는다. */
    private fun joinCode(raw: String, onFail: (String) -> Unit={ toast(it) }, onSuccess: () -> Unit={}) {
        val code=normalizeCode(raw)
        if(!isCodeComplete(code)) { onFail("초대 코드 ${CODE_LENGTH}자리를 입력해 주세요.");return }
        joinBlocker()?.let { onFail(it);return }
        server.joinByCode(code) { r -> r.fold({ onSuccess();onJoined(it,true) },{ e -> onFail(e.message ?: "입장하지 못했어요.") }) }
    }
    private fun joinRoom(room: RemoteRoom) {
        if(!canJoin(room.id)) return
        withAccount { server.joinPublic(room.id) { j -> j.fold({ onJoined(it,true) },{ e -> toast(e.message ?: "참여하지 못했어요.") }) } }
    }
    private fun departLabel(at: Long?, short: Boolean=false): String {
        if(at==null) return if(short) "출발 시간 미정" else "출발 시간 정하기 (선택)"
        val f=SimpleDateFormat("M월 d일 (E) a h:mm",Locale.KOREA).format(Date(at))
        return if(short) "$f 출발" else "출발  $f"
    }
    /** 날짜 → 시간 순서로 고름. 지난 시간은 받지 않음. */
    private fun pickDepart(onPick: (Long?) -> Unit) {
        val cal=Calendar.getInstance().apply { draftDepart?.let { timeInMillis=it } ?: add(Calendar.HOUR_OF_DAY,1) }
        val date=android.app.DatePickerDialog(this,{ _,y,m,d ->
            android.app.TimePickerDialog(this,{ _,h,min ->
                val at=Calendar.getInstance().apply { set(y,m,d,h,min,0);set(Calendar.MILLISECOND,0) }.timeInMillis
                if(at<System.currentTimeMillis()-60_000) toast("지난 시간은 고를 수 없어요.") else onPick(at)
            },cal.get(Calendar.HOUR_OF_DAY),cal.get(Calendar.MINUTE),false).show()
        },cal.get(Calendar.YEAR),cal.get(Calendar.MONTH),cal.get(Calendar.DAY_OF_MONTH))
        date.datePicker.minDate=System.currentTimeMillis()-1000
        date.setButton(android.content.DialogInterface.BUTTON_NEUTRAL,"정하지 않음") { _,_ -> onPick(null) }
        date.show()
    }

    // ---------- 온라인 방 ----------
    /** 닉네임이 없으면 먼저 정하게 하고 이어서 실행. */
    private fun withAccount(block: () -> Unit) { if(server.signedIn) block() else askProfile(block) }
    private fun askProfile(then: () -> Unit) {
        val holder=ui.col(20)
        val name=ui.edit("닉네임 (예: 하늘)",server.nickname.orEmpty()).apply { filters=arrayOf(android.text.InputFilter.LengthFilter(24)) }
        val car=ui.edit("차량 (예: IONIQ 5)",server.car.orEmpty()).apply { filters=arrayOf(android.text.InputFilter.LengthFilter(24)) }
        holder.addView(name);holder.addView(ui.gap(10));holder.addView(car)
        AlertDialog.Builder(this).setTitle("방에서 쓸 이름").setView(holder)
            .setPositiveButton("시작하기") { _,_ ->
                val n=name.text.toString().trim();val c=car.text.toString().trim().ifEmpty { "내 차량" }
                if(n.isEmpty()) { toast("닉네임을 입력해 주세요.");return@setPositiveButton }
                server.register(n,c) { r -> r.fold({ toast("$n 님, 반가워요.");render();then() },{ e -> toast(e.message ?: "등록하지 못했어요.") }) }
            }.setNegativeButton("취소",null).show()
    }
    private fun editServer() {
        val input=ui.edit(RoomServer.DEFAULT_URL,server.baseUrl)
        val holder=ui.col(20);holder.addView(input);holder.addView(ui.gap(8))
        holder.addView(ui.text("에뮬레이터에서 이 PC의 서버는 ${RoomServer.DEFAULT_URL} 이에요. 실제 서비스는 https 주소를 써요.",12f,Palette.muted))
        AlertDialog.Builder(this).setTitle("서버 주소").setView(holder)
            .setPositiveButton("저장") { _,_ -> val v=input.text.toString().trim()
                if(v.startsWith("http://") || v.startsWith("https://")) { server.baseUrl=v;render() } else toast("http:// 또는 https:// 로 시작해야 해요.") }
            .setNeutralButton("기본값") { _,_ -> server.baseUrl=RoomServer.DEFAULT_URL;render() }
            .setNegativeButton("취소",null).show()
    }
    /** 초대 코드 입력: 6칸과 화면 자판(Pleos 전체 화면 키보드를 쓰지 않음). */
    private fun askCode() {
        var dialog: AlertDialog?=null
        var entry: CodeEntryView?=null
        entry=CodeEntryView(this,ui) { code -> joinCode(code,{ entry?.showError(it) },{ dialog?.dismiss() }) }
        val holder=ui.col(20);holder.addView(ui.text("방장에게 받은 6자리 코드를 눌러 주세요.",15f,Palette.muted));holder.addView(ui.gap(16));holder.addView(entry)
        dialog=AlertDialog.Builder(this).setTitle("초대 코드로 입장").setView(holder).setNegativeButton("취소",null).create()
        dialog.show()
        dialog.window?.setLayout(ui.d(560),android.view.WindowManager.LayoutParams.WRAP_CONTENT)
    }
    /** 같은 목적지(반경 1km)의 공개 방 목록. 없으면 이 목적지로 방을 만들게 안내. */
    private fun findRooms(p: Place) {
        server.sameDestination(p) { r -> r.fold({ rooms ->
            val holder=ui.col(20)
            val dialog=AlertDialog.Builder(this).setTitle("${p.name}(으)로 가는 방").setView(ScrollView(this).apply { addView(holder) }).setNegativeButton("닫기",null).create()
            if(rooms.isEmpty()) { holder.addView(ui.text("아직 ${p.name}(으)로 가는 공개 방이 없어요.",16f,Palette.muted));holder.addView(ui.gap(14)) }
            rooms.forEach { room ->
                val c=ui.card(padding=16)
                c.addView(ui.text(room.title,18f,Palette.ink,true));c.addView(ui.gap(6))
                val via=if(room.waypoints.isNotEmpty()) " · 경유 ${room.waypoints.joinToString { it.name }}" else ""
                c.addView(ui.text("방장 ${room.hostName} · ${room.memberCount}/${room.maxMembers}대 · ${if(room.kind=="crew") "크루" else "동행"}$via",13f,Palette.muted));c.addView(ui.gap(10))
                val full=room.memberCount>=room.maxMembers && !room.joined
                c.addView(ui.button(if(room.joined) "참여 중 · 열기" else if(full) "자리가 없어요" else "이 방에 참여",!full) {
                    if(full) return@button
                    dialog.dismiss();joinRoom(room)
                })
                holder.addView(c);holder.addView(ui.gap(10))
            }
            holder.addView(ui.button("이 목적지로 새 방 만들기") { dialog.dismiss();draftDestination=p;draftOnline=true;draftPublic=true;navigate("create") })
            dialog.show()
        },{ e -> toast(e.message ?: "방을 찾지 못했어요.") }) }
    }
    /** 서버 멤버 → 이 기기의 멤버. 수락 여부는 서버 값, 진행 단계는 [memberPhase]에 기억. */
    private fun remoteOthers(members: List<RemoteMember>, version: Int): List<Member> {
        members.forEach { memberPhase[it.id]=it.phase }
        return members.filter { it.id!=server.userId }.map { Member(it.id,it.name.take(24),it.car.take(24),acceptedVersion=if(it.accepted) version else 0) }
    }
    /** 서버 방을 이 기기의 모임으로. 이미 있으면 경로·멤버만 맞춤. */
    private fun onJoined(room: RemoteRoom, announce: Boolean) = action {
        val existing=trips.find { it.roomId==room.id }
        val host=room.hostId==server.userId
        val t=if(existing!=null) existing.applyRemoteRoute(room.destination,room.waypoints).let { it.syncMembers(remoteOthers(room.members,it.route.version)) }
                .copy(roomHost=host,roomHostId=room.hostId,roomCode=room.inviteCode ?: existing.roomCode)
            else Trip(room.title.take(40),if(room.kind=="crew") "크루" else "동행",RoutePlan(room.destination,room.waypoints.take(2)),
                listOf(Member("me","나",(server.car ?: "내 차량").take(24))),roomId=room.id,roomCode=room.inviteCode,roomHost=host,roomHostId=room.hostId)
                .let { it.syncMembers(remoteOthers(room.members,it.route.version)) }
        selectedId=t.id
        update(t,if(t.phase==Phase.DRIVING) "drive" else if(t.phase==Phase.FINISHED) "record" else "trip")
        if(announce) toast("‘${room.title}’ 방에 들어왔어요.")
    }
    private fun refreshRoom(t: Trip) {
        val roomId=t.roomId ?: return
        server.room(roomId) { r -> r.fold({ onJoined(it,false);toast("방 정보를 새로 받았어요.") },{ e -> toast(e.message ?: "새로고침하지 못했어요.") }) }
    }
    private fun confirmLeave(t: Trip) {
        val roomId=t.roomId ?: return
        AlertDialog.Builder(this).setTitle("방에서 나갈까요?").setMessage("이 기기에서 모임이 사라지고, 다시 들어오려면 초대 코드가 필요해요.")
            .setPositiveButton("나가기") { _,_ ->
                server.leave(roomId) { r ->
                    val err=r.exceptionOrNull()
                    // 이미 닫혔거나 빠진 방이면 이 기기에서만 지우면 됨.
                    if(err!=null && (err as? RoomServer.ServerError)?.status !in listOf(403,404)) { toast(err.message ?: "나가지 못했어요.");return@leave }
                    if(linkTripId==t.id) stopLive()
                    val rest=trips.filter { it.id!=t.id }
                    try { store.save(rest) } catch(_: Exception) { }
                    trips=rest;selectedId=rest.firstOrNull { it.phase!=Phase.FINISHED }?.id.orEmpty();navigate("home");toast("방에서 나왔어요.")
                }
            }.setNegativeButton("취소",null).show()
    }
    private fun syncRemote(tripId: String, members: List<RemoteMember>) {
        val t=trips.find { it.id==tripId } ?: return
        // 주행 중 일행이 도착하면 말풍선과 음성으로 알림.
        if(t.phase==Phase.DRIVING) members.filter { it.id!=server.userId && it.phase=="arrived" && memberPhase[it.id]!="arrived" }.forEach { m ->
            driveView?.showDistance(m.id,"도착");say("${m.name}님이 도착했어요.")
        }
        val hostId=members.find { it.role=="host" }?.id
        val phaseChanged=members.any { memberPhase[it.id]!=it.phase }
        val next=t.syncMembers(remoteOthers(members,t.route.version)).copy(roomHost=hostId==server.userId,roomHostId=hostId)
        if(next!=t) action { update(next) }
        // 출발·도착은 모임 데이터가 아니라 화면 표시만 바뀌므로 출발 전 화면을 다시 그림.
        else if(phaseChanged && screen=="trip" && driveView==null && !moving) render()
    }
    private var locationAsked=false
    private var sharingAskedFor: String?=null
    private val memberPhase=mutableMapOf<String,String>()
    /** 방에 실시간 연결(멤버·경로·상태 변경을 바로 받음). 출발 전 화면과 주행 화면에서 쓴다. */
    private fun connectRoom(t: Trip) {
        if(linkTripId==t.id && link!=null) return
        stopLive()
        linkTripId=t.id;lastSentAt=0;lastSentMode=null;locationAsked=false
        link=server.live(t.roomId!!,liveEvents(t.id))
    }
    /** 주행 화면: 방에 연결하고, 처음 한 번 위치 공유를 물음. 내 위치는 공유를 켰을 때만 보낸다. */
    private fun startLive(t: Trip) {
        connectRoom(t)
        if(sharingAskedFor==t.id || arrivedTripId==t.id) return
        sharingAskedFor=t.id
        if(t.members.first().sharing==Sharing.OFF) AlertDialog.Builder(this).setTitle("일행에게 내 위치를 보여 줄까요?")
            .setMessage("같은 방 멤버에게만 보여요. 동행을 마치면 바로 꺼지고 서버에서도 지워져요. 나중에 ‘위치공유 설정’에서 바꿀 수 있어요.")
            .setPositiveButton("정확한 위치") { _,_ -> mutate { it.setSharing("me",Sharing.EXACT) } }
            .setNeutralButton("대략적인 위치") { _,_ -> mutate { it.setSharing("me",Sharing.APPROXIMATE) } }
            .setNegativeButton("나중에",null).show()
    }
    private var arrivedTripId: String?=null
    /** 목적지 도착(Pleos 내비 도착 이벤트 또는 목적지 300m 안): 위치 공유를 끄고 서버에서도 지운 뒤, 동행을 마칠지 묻는다. */
    private fun arrive(t: Trip) {
        if(arrivedTripId==t.id || t.phase!=Phase.DRIVING) return
        arrivedTripId=t.id
        myLocation?.stop();myLocation=null
        if(t.members.first().sharing!=Sharing.OFF) action { update(t.setSharing("me",Sharing.OFF)) }
        trips.find { it.id==t.id }?.let { maybeSendLocation(it) }
        if(t.online) server.setStatus(t.roomId!!,phase="arrived")
        driveView?.status("도착 · 위치 공유를 껐어요")
        say("목적지에 도착했어요. 위치 공유를 껐어요.")
        AlertDialog.Builder(this).setTitle("목적지에 도착했어요").setMessage("위치 공유를 자동으로 껐어요. 이번 동행을 마칠까요?")
            .setPositiveButton("동행 마치기") { _,_ -> finishTrip() }.setNegativeButton("일행 기다리기",null).show()
    }
    private fun stopLive() {
        sharingAskedFor=null
        link?.close();link=null;linkTripId=null;linkConnected=false
        livePositions.clear();liveLastKm.clear()
        myLocation?.stop();myLocation=null;myFix=null;myHeading=null
    }
    private fun liveEvents(tripId: String)=object: LiveEvents {
        fun trip()=trips.find { it.id==tripId }
        override fun onConnected(connected: Boolean) {
            linkConnected=connected
            if(connected) { lastSentAt=0;lastSentMode=null }
            trip()?.let { driveView?.status(if(connected) "온라인 방 · ${it.inviteCode} · 연결됨" else "온라인 방 · 다시 연결하는 중…") }
            if(driveView==null && screen=="trip" && !moving) render()
        }
        override fun onSnapshot(members: List<RemoteMember>, locations: List<RemoteLocation>) {
            syncRemote(tripId,members);livePositions.clear();locations.forEach { onLocation(it) }
        }
        override fun onMembers(members: List<RemoteMember>)=syncRemote(tripId,members)
        override fun onRoom(room: RemoteRoom) { trip()?.let { t -> action { update(t.applyRemoteRoute(room.destination,room.waypoints).copy(roomHost=room.hostId==server.userId,roomHostId=room.hostId)) } } }
        override fun onLocation(location: RemoteLocation) {
            if(location.userId==server.userId) return
            livePositions[location.userId]=LivePoint(GeoPoint(location.lat,location.lon),location.approximate);driveView?.refresh()
        }
        override fun onLocationRemoved(userId: String) { livePositions.remove(userId);liveLastKm.remove(userId);driveView?.refresh() }
        override fun onMessage(from: String, recipient: String, text: String, at: Long) {
            val t=trip() ?: return
            if(t.members.none { it.id==from }) return
            receive(t.id,SimEvent.Incoming(from,if(recipient=="all") "all" else "me",text))
        }
        override fun onRoomClosed() { toast("방이 닫혔어요. 위치 공유를 멈췄어요.");stopLive();driveView?.status("온라인 방 · 닫힘") }
        override fun onError(message: String) { toast(message) }
    }
    /** 1초마다: 내 위치 보내기, 일행 남은 거리 알림. */
    private fun onlineTick(t: Trip) {
        if(link!=null && t.members.first().sharing!=Sharing.OFF && myLocation==null && !locationAsked) { locationAsked=true;startMyLocation(true) }
        maybeSendLocation(t)
        val total=demoRouteKm(t.route)
        t.members.filter { it.id!="me" }.forEach { m ->
            val km=onlineRemaining(m.id) ?: return@forEach
            liveLastKm[m.id]?.let { last -> crossedAlert(last,km,total)?.let { th ->
                val label=alertLabel(th);driveView?.showDistance(m.id,label)
                say(if(label==ARRIVING_LABEL) "${m.name}님 곧 도착해요." else "${m.name}님 ${spoken(label)} 남았어요.")
            } }
            liveLastKm[m.id]=km
        }
    }
    private fun onlineRemaining(memberId: String): Double? {
        val t=onlineDriving ?: return null
        val p=driveHost.livePosition(memberId)?.point ?: return null
        return RouteGeometry.line(t.route).remainingKm(p)*1.25
    }
    /** 공유 설정대로 위치를 보냄(2초에 한 번). 끄면 한 번 "off"를 보내 서버에서 지우게 함. */
    private fun maybeSendLocation(t: Trip) {
        val l=link ?: return
        if(!linkConnected) return
        val mode=when(t.members.first().sharing) { Sharing.OFF->"off";Sharing.APPROXIMATE->"approximate";Sharing.EXACT->"exact" }
        val now=System.currentTimeMillis()
        if(mode=="off") { if(lastSentMode!="off") { l.sendLocation(0.0,0.0,null,"off");lastSentMode="off" };return }
        val p=myFix ?: return
        if(mode==lastSentMode && now-lastSentAt<2000) return
        l.sendLocation(p.lat,p.lon,myHeading,mode);lastSentAt=now;lastSentMode=mode
    }
    /** Pleos 위치 권한은 시스템 문구가 어색해서, 요청 전에 이유를 먼저 보여 준다. */
    private fun startMyLocation(explain: Boolean) {
        if(checkSelfPermission(MyLocation.PERMISSION)!=android.content.pm.PackageManager.PERMISSION_GRANTED) {
            if(explain) AlertDialog.Builder(this).setTitle("위치 권한이 필요해요")
                .setMessage("내 차 위치를 같은 방 일행에게 보여 주려면 차량 위치 권한이 필요해요. 다음 화면에서 허용해 주세요.")
                .setPositiveButton("계속") { _,_ -> requestPermissions(arrayOf(MyLocation.PERMISSION),61) }.setNegativeButton("나중에",null).show()
            return
        }
        val l=myLocation ?: MyLocation(this) { p,h ->
            myFix=p;myHeading=h
            onlineDriving?.let { t ->
                maybeSendLocation(t)
                if(haversineKm(p.lat,p.lon,t.route.destination.latitude,t.route.destination.longitude)<=ARRIVE_KM) arrive(t)
            }
            driveView?.refresh()
        }.also { myLocation=it }
        if(!l.start()) { myLocation=null;toast("이 차에서 내 위치를 가져올 수 없어요. 일행 위치만 보여요.") }
    }
    @Deprecated("Legacy permission callback")
    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode,permissions,grantResults)
        if(requestCode!=61) return
        if(grantResults.firstOrNull()==android.content.pm.PackageManager.PERMISSION_GRANTED) { if(onlineDriving!=null) startMyLocation(false) }
        else toast("위치 권한이 없어 내 위치는 보내지 않아요. 일행 위치는 볼 수 있어요.")
    }
    companion object { /** 목적지까지 이 거리(km) 안이면 도착으로 봄. */ const val ARRIVE_KM=0.3 }
}
