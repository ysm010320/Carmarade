package ai.camarade.app

import android.app.Activity
import android.app.AlertDialog
import android.content.*
import android.content.res.Configuration
import android.graphics.Color
import android.os.Bundle
import android.speech.RecognizerIntent
import android.speech.tts.TextToSpeech
import android.text.Editable
import android.text.TextWatcher
import android.view.*
import android.widget.*
import ai.camarade.core.*
import ai.camarade.app.vehicle.*
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
    private var tts: TextToSpeech?=null
    private var speechReady=false
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
    private val ticker=android.os.Handler(android.os.Looper.getMainLooper())
    private var ticking=false
    private val tick=object: Runnable { override fun run() { if(onTick()) ticker.postDelayed(this,1000) else ticking=false } }
    private val driveHost=object: DriveScreen.Host {
        override fun quick(text: String, recipient: String)=send(text,recipient)
        override fun voiceTo(recipient: String) { target=recipient;voice() }
        override fun call(recipient: String)=toast("통화 기능은 준비 중이에요.")
        override fun openPrivacy()=navigate("privacy")
        override fun toggleSound() {
            if(!speechReady) toast("이 기기에 한국어 음성 엔진이 설치되어 있지 않아요.") else { sound=!sound;render();if(sound) say("카마라드 음성 안내를 시작합니다.") }
        }
        override fun finishTrip()=confirmFinish()
        override fun showHistory()=history()
        override fun remainingKm(memberId: String)=sim?.remainingKm(memberId,System.currentTimeMillis())
        override fun progress(memberId: String)=sim?.progress(memberId,System.currentTimeMillis())
    }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ui=Ui(this);store=TripStore(this)
        val loaded=store.load();trips=loaded.trips;loadError=loaded.error
        selectedId=savedInstanceState?.getString("selected") ?: trips.firstOrNull()?.id.orEmpty()
        screen=savedInstanceState?.getString("screen") ?: "home"
        trips.find { it.phase==Phase.DRIVING }?.let { selectedId=it.id;screen="drive" }
        tts=TextToSpeech(this) { status ->
            if(status==TextToSpeech.SUCCESS) {
                val result=tts?.setLanguage(Locale.KOREAN)
                speechReady=result!=TextToSpeech.LANG_MISSING_DATA && result!=TextToSpeech.LANG_NOT_SUPPORTED
            }
        }
        render()
    }
    override fun onSaveInstanceState(out: Bundle) { out.putString("selected",selectedId);out.putString("screen",screen);super.onSaveInstanceState(out) }
    override fun onConfigurationChanged(config: Configuration) { super.onConfigurationChanged(config);render() }
    override fun onDestroy() { ticker.removeCallbacks(tick);tts?.stop();tts?.shutdown();gateway.close();super.onDestroy() }
    @Deprecated("Legacy Android activity back handling")
    override fun onBackPressed() { if(driving) navigate("drive") else if(screen!="home") navigate("home") else super.onBackPressed() }
    private fun toast(message: String) { Toast.makeText(this,message,Toast.LENGTH_LONG).show() }
    private fun say(message: String) { if(sound && speechReady) tts?.speak(message,TextToSpeech.QUEUE_ADD,null,"camarade") }
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
        if(driving && next !in listOf("drive","privacy")) { toast("주행 체험 중에는 동행 화면을 사용해 주세요.");screen="drive" }
        else screen=next
        render()
    }
    private fun select(trip: Trip) { selectedId=trip.id;screen=when(trip.phase) { Phase.PLANNING->"trip";Phase.DRIVING->"drive";Phase.FINISHED->"record" };render() }
    private fun title(kicker: String, title: String, subtitle: String="") {
        content.addView(ui.text(kicker,12f,Palette.teal,true));content.addView(ui.gap(10));content.addView(ui.text(title,30f,Palette.ink,true))
        if(subtitle.isNotEmpty()) { content.addView(ui.gap(8));content.addView(ui.text(subtitle,14f,Palette.muted)) }
        content.addView(ui.gap(24))
    }
    private fun sectionTitle(label: String) { content.addView(ui.gap(12));content.addView(ui.text(label,19f,Palette.ink,true));content.addView(ui.gap(14)) }
    private fun note(label: String) { content.addView(ui.gap(10));content.addView(ui.text(label,12f,Palette.muted));content.addView(ui.gap(10)) }
    private fun map(t: Trip,height: Int=265): View = RouteMapView(this,t,gateway).apply { layoutParams=LinearLayout.LayoutParams(-1,ui.d(height));clipToOutline=true;background=ui.box(Palette.soft,20) }
    private fun render() {
        val live=current
        if(live?.phase==Phase.DRIVING && screen=="trip") screen="drive"
        if(screen=="drive") {
            if(live?.phase==Phase.DRIVING) { showDrive(live);return }
            screen=if(live?.phase==Phase.FINISHED) "record" else "trip"
        }
        driveView=null
        val keepY=if(screen==shownScreen) scrollView?.scrollY ?: 0 else 0
        val wide=resources.configuration.screenWidthDp>=720
        val root=ui.col();root.setBackgroundColor(Palette.bg)
        val top=ui.row().apply { setPadding(ui.d(24),ui.d(14),ui.d(24),ui.d(14)) }
        ui.add(top,ui.text("◈  CARMARADE",18f,Palette.dark,true),1f)
        top.addView(ui.chip(gateway.label));root.addView(top)
        val body=if(wide) ui.row().apply { gravity=Gravity.TOP } else ui.col()
        val nav=if(wide) ui.col(16) else ui.row()
        val links=listOf("home" to "⌂  내 크루","trip" to "↗  함께 달리기","records" to "▤  주행 기록","privacy" to "◎  공유 설정")
        links.forEach { (key,label) ->
            val active=key==screen || (key=="trip" && screen=="drive") || (key=="records" && screen=="record")
            val b=ui.button(label,false) {
                if(key=="trip" && current?.phase==Phase.FINISHED) { selectedId=trips.firstOrNull { it.phase!=Phase.FINISHED }?.id.orEmpty() }
                navigate(if(key=="trip" && current?.phase==Phase.DRIVING) "drive" else key)
            }.apply { textSize=if(wide) 15f else 12f;setPadding(ui.d(10),ui.d(10),ui.d(10),ui.d(10));background=ui.box(if(active) Palette.dark else Palette.bg,12);setTextColor(if(active) Palette.white else Palette.muted) }
            if(wide) { nav.addView(b);nav.addView(ui.gap(10)) } else ui.add(nav,b,1f)
        }
        if(wide) {
            nav.addView(ui.gap(36));nav.addView(ui.text("CAR + MARADE\n차로 함께하는 동료",12f,Palette.muted))
            ui.add(body,nav,width=ui.d(170));
        } else body.addView(nav)
        content=ui.col(if(wide) 24 else 18)
        val scroll=ScrollView(this).apply { isFillViewport=true;addView(content);clipToPadding=false }
        if(wide) ui.add(body,scroll,1f,height=-1) else body.addView(scroll,LinearLayout.LayoutParams(-1,0,1f))
        root.addView(body,LinearLayout.LayoutParams(-1,0,1f));setContentView(root)
        if(saveError!=null) {
            val warning=ui.card(Palette.lime,16)
            warning.addView(ui.text("저장하지 못한 변경이 있어요. 앱을 닫으면 사라질 수 있습니다.",14f,Palette.ink,true));warning.addView(ui.gap(10))
            warning.addView(ui.button("저장 다시 시도") { action { store.save(trips);saveError=null;render();toast("변경 내용을 저장했어요.") } })
            content.addView(warning);content.addView(ui.gap(18))
        }
        when(screen) { "home"->home();"trip"->planner();"privacy"->privacy();"records"->records();"record"->record();"create"->create();else->home() }
        shownScreen=screen;scrollView=scroll
        // 첫 프레임을 그리기 전에 위치를 되돌려 맨 위가 잠깐 보이는 깜빡임을 막음. 내용이 짧아졌으면 ScrollView가 끝에 맞춰 줌.
        if(keepY>0) scroll.viewTreeObserver.addOnPreDrawListener(object: ViewTreeObserver.OnPreDrawListener {
            override fun onPreDraw(): Boolean { scroll.viewTreeObserver.removeOnPreDrawListener(this);scroll.scrollTo(0,keepY);return true }
        })
    }
    private fun home() {
        title("OUR NEXT JOURNEY","오늘은, 어디로 함께 갈까요?","각자의 차에서 시작해도, 우리의 이야기는 함께.")
        loadError?.let { note(it) }
        val hero=ui.card(Palette.dark,26)
        hero.addView(ui.text("따로 타도,\n함께 가는 길.",32f,Palette.white,true));hero.addView(ui.gap(13))
        hero.addView(ui.text("목적지는 같이 · 연락은 간단하게 · 추억은 오래",14f,Palette.lime));hero.addView(ui.gap(20))
        hero.addView(ui.button("+  새로운 동행 만들기") { navigate("create") });content.addView(hero)
        sectionTitle("나의 크루와 동행")
        val active=trips.filter { it.phase!=Phase.FINISHED }
        if(active.isEmpty()) note("아직 준비된 동행이 없어요. 함께 갈 모임을 만들어 보세요.")
        active.forEach { t ->
            val c=ui.card();val row=ui.row();ui.add(row,ui.text(t.name,22f,Palette.ink,true),1f);row.addView(ui.chip(t.type));c.addView(row)
            c.addView(ui.gap(10));c.addView(ui.text(t.route.destination.name+"  ·  "+t.members.size+"대",15f,Palette.muted));c.addView(ui.gap(17))
            c.addView(ui.text(t.members.joinToString("   ") { if(it.id=="me") "● 나" else "● ${it.name} (가상)" },14f));c.addView(ui.gap(18))
            c.addView(ui.button(if(t.phase==Phase.DRIVING) "주행 체험 이어가기  →" else "동행 준비하기  →",true) { select(t) })
            content.addView(c);content.addView(ui.gap(12))
        }
        note("첫 버전은 이 기기에서 동행 흐름을 체험합니다. 실제 초대·위치 전송·차량 내비 연결은 다음 단계입니다.")
    }
    private fun create() {
        title("MAKE A CREW","함께 갈 사람들을 위한 공간","크루는 오래 함께할 모임, 동행은 이번 여행을 위한 모임이에요.")
        val c=ui.card();c.addView(ui.text("모임 이름",15f,Palette.ink,true));c.addView(ui.gap(10))
        val name=ui.edit("예: 주말엔, 동해",draftName);name.filters=arrayOf(android.text.InputFilter.LengthFilter(40));watch(name) { draftName=it };c.addView(name);c.addView(ui.gap(20))
        val types=RadioGroup(this).apply { orientation=RadioGroup.HORIZONTAL }
        listOf("크루","동행").forEachIndexed { i,s -> types.addView(RadioButton(this).apply { id=100+i;text=s;textSize=18f;minHeight=ui.d(52);setPadding(0,0,ui.d(30),0) }) };types.check(draftType);types.setOnCheckedChangeListener { _,id->draftType=id };c.addView(types)
        c.addView(ui.gap(20));c.addView(ui.text("첫 목적지",15f,Palette.ink,true));c.addView(ui.gap(10))
        var destination=draftDestination
        val dest=ui.button(destination.name) { };dest.setOnClickListener { choosePlace(emptyList()) { destination=it;draftDestination=it;dest.text=it.name } };c.addView(dest)
        c.addView(ui.gap(22));c.addView(ui.button("동행 만들기",true) { action {
            val t=Trip.create(name.text.toString(),if(types.checkedRadioButtonId==100) "크루" else "동행",destination)
            selectedId=t.id;draftName="";draftType=100;draftDestination=Catalog.places.first();update(t,"trip")
        } });content.addView(c);note("위치공유는 기본으로 꺼져 있어요. 모임을 만든 뒤 직접 켤 수 있습니다.")
    }
    private fun planner() {
        val t=current
        if(t==null || t.phase==Phase.FINISHED) { title("LET'S GO","새로운 동행을 준비해요");content.addView(ui.button("모임 만들기",true) { navigate("create") });return }
        if(t.phase==Phase.DRIVING) { screen="drive";content.post { render() };return }
        title("PLAN TOGETHER",t.name,"공통 목적지와 경유지를 확인하고, 각자 수락해요.")
        val c=ui.card();val row=ui.row();ui.add(row,ui.text("우리의 목적지",17f,Palette.ink,true),1f);row.addView(ui.chip("경로 v${t.route.version}"));c.addView(row)
        c.addView(ui.gap(16));c.addView(ui.text(t.route.destination.name,26f,Palette.ink,true));c.addView(ui.gap(14))
        c.addView(ui.button("목적지 변경") { choosePlace(t.route.waypoints) { p -> mutate { it.setRoute("me",p,it.route.waypoints) } } });c.addView(ui.gap(14))
        t.route.waypoints.forEachIndexed { i,p ->
            val r=ui.row();ui.add(r,ui.text("${i+1}  ${p.name}",17f),1f);r.addView(ui.button("삭제") { mutate { it.setRoute("me",it.route.destination,it.route.waypoints.filter { s->s.id!=p.id }) } });c.addView(r);c.addView(ui.gap(10))
        }
        if(t.route.waypoints.size<2) c.addView(ui.button("+  경유지 추가 (${t.route.waypoints.size}/2)") { choosePlace(t.route.waypoints+ t.route.destination) { p -> mutate { it.setRoute("me",it.route.destination,it.route.waypoints+p) } } })
        content.addView(c);content.addView(ui.gap(18));content.addView(map(t,225))
        sectionTitle("함께 달릴 ${t.members.size}대")
        t.members.forEach { m ->
            val r=ui.card(padding=16);val line=ui.row();ui.add(line,ui.text("${m.name}${if(m.id=="me") " · 호스트" else " · 가상 멤버"}",17f,Palette.ink,true),1f)
            line.addView(ui.chip(if(m.accepted(t.route)) "수락 완료" else "확인 대기"));r.addView(line);r.addView(ui.gap(7));r.addView(ui.text(m.car,13f,Palette.muted));content.addView(r);content.addView(ui.gap(8))
        }
        val controls=ui.row();ui.add(controls,ui.button("초대 코드 보기") { invite(t) },1f,margin=3)
        if(t.members.size<5) ui.add(controls,ui.button("+ 가상 멤버") { addMember() },1f,margin=3)
        content.addView(controls);content.addView(ui.gap(10))
        if(t.members.any { it.id!="me" && !it.accepted(t.route) }) {
            content.addView(ui.button("가상 일행의 경로 수락 시뮬레이션") { mutate { trip ->
                var result=trip
                trip.members.filter { it.id!="me" }.forEach { m -> result=result.acceptRoute(m.id,result.route.version).setSharing(m.id,Sharing.EXACT) }
                result
            } });content.addView(ui.gap(10))
        }
        if(!t.members.first().accepted(t.route)) {
            content.addView(ui.button("이 경로 수락하기",true) {
                gateway.acceptRoute(t.route) { result -> when(result) {
                    is NavigationResult.Simulated -> { if(mutate { it.acceptRoute("me",result.version) }) toast("체험 경로를 수락했어요. 실제 내비게이션은 실행되지 않습니다.") }
                    is NavigationResult.Applied -> mutate { it.acceptRoute("me",result.version) }
                    is NavigationResult.Unavailable -> toast(result.reason)
                } }
            });content.addView(ui.gap(10))
        }
        content.addView(ui.button("동행 주행 체험 시작  →",true) { mutate("drive") { it.start("me",System.currentTimeMillis()) } })
        note("모두 수락하면 출발할 수 있어요. 실제 주행에서는 각 차량의 내비게이션이 경로를 계산합니다.")
    }
    private fun invite(t: Trip) {
        AlertDialog.Builder(this).setTitle("초대 코드 · ${t.inviteCode}")
            .setMessage("현재는 기기 안에서만 작동하는 체험 코드입니다. 다른 기기에서 가입하는 기능은 서버 연결 후 제공됩니다.")
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
        val v=driveView?.takeIf { it.tripId==t.id } ?: DriveScreen(this,ui,gateway,driveHost).also { driveView=it }
        if(sim?.tripId!=t.id) sim=DriveSimulation(t,System.currentTimeMillis())
        v.bind(t,sound)
        if(v.parent==null) setContentView(v)
        shownScreen="drive";scrollView=null
        if(!ticking) { ticking=true;ticker.postDelayed(tick,1000) }
    }
    private fun memberName(t: Trip, id: String)=if(id=="me") "나" else t.members.find { it.id==id }?.name ?: "멤버"
    private fun spoken(label: String)=label.replace("km","킬로미터").replace("m","미터")
    /** 1초마다: 가상 일행의 거리 알림·정차·메시지를 처리. 주행 중인 동행이 없으면 멈춤. */
    private fun onTick(): Boolean {
        val t=trips.find { it.phase==Phase.DRIVING } ?: run { sim=null;return false }
        val now=System.currentTimeMillis()
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
            .setMessage(if(lines.isEmpty()) "아직 주고받은 메시지가 없어요." else "$lines\n\n체험 기록입니다. 다른 기기로 전송되지 않아요.")
            .setPositiveButton("닫기",null).show()
    }
    private fun confirmFinish() {
        AlertDialog.Builder(this).setTitle("이번 동행을 마칠까요?").setMessage("모든 위치공유가 종료되고 주행 체험 기록이 남아요.")
            .setPositiveButton("종료하기") { _,_-> action {
                val latest=current ?: return@action;val now=System.currentTimeMillis();val distance=((now-latest.startedAt!!).coerceAtLeast(0)/1000.0)*0.02
                if(update(latest.finish("me",now,distance),"record")) say("동행이 종료되었습니다. 위치 공유를 껐어요.")
            } }.setNegativeButton("계속 달리기",null).show()
    }
    /** 내가 보낸 메시지는 말풍선으로만 보여 주고 소리로 다시 읽지 않음. */
    private fun send(text: String, recipient: String=target, mode: InputMode=InputMode.PRESET) {
        action { current?.let { t->
            val now=System.currentTimeMillis()
            update(t.send("me",recipient,text,now,mode))
            driveView?.showMessage("me",recipient,text)
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
    private fun privacy() {
        val t=current
        title("YOUR LOCATION, YOUR CHOICE","내 위치는, 내가 정해요","동행 안에서 필요한 동안만 함께 나눠요.")
        if(t==null || t.phase==Phase.FINISHED) { note("진행 중인 동행이 없어요. 위치공유는 꺼져 있습니다.");return }
        val me=t.members.first();val choices=listOf(Sharing.OFF to "위치 숨기기",Sharing.APPROXIMATE to "대략적인 위치",Sharing.EXACT to "정확한 위치")
        choices.forEach { (mode,label)->
            val c=ui.card();c.addView(ui.text((if(me.sharing==mode) "●  " else "○  ")+label,21f,Palette.ink,true));c.addView(ui.gap(10))
            c.addView(ui.text(when(mode) { Sharing.OFF->"멤버에게 내 위치가 표시되지 않아요.";Sharing.APPROXIMATE->"약 1km 단위로 낮춘 위치를 표시해요.";Sharing.EXACT->"모임 멤버에게만 위치를 보여줘요." },14f,Palette.muted));c.addView(ui.gap(15))
            c.addView(ui.button(if(me.sharing==mode) "선택됨" else "이 설정 사용",me.sharing==mode) { mutate { it.setSharing("me",mode) } });content.addView(c);content.addView(ui.gap(12))
        }
        note("동행 종료 시 자동으로 해제됩니다. 앱을 다시 열 때도 공유 동의를 다시 받아요. 현재 지도에는 가상 좌표만 사용합니다.")
    }
    private fun records() {
        title("OUR ROAD STORIES","함께 달린 길이 이야기가 되도록","도착한 뒤에도, 우리의 동행은 여기 남아요.")
        val records=trips.filter { it.phase==Phase.FINISHED }.reversed()
        if(records.isEmpty()) { val c=ui.card();c.addView(ui.text("첫 번째 이야기를 기다리고 있어요.",22f,Palette.ink,true));c.addView(ui.gap(12));c.addView(ui.text("동행 체험을 마치면 기록이 쌓입니다.",16f,Palette.muted));content.addView(c) }
        records.forEach { t->
            val c=ui.card();c.addView(ui.text(t.name,23f,Palette.ink,true));c.addView(ui.gap(10));c.addView(ui.text("${t.route.destination.name}  ·  ${t.members.size}대  ·  체험 기록",15f,Palette.muted));c.addView(ui.gap(16));c.addView(ui.button("기록 보기  →") { select(t) });content.addView(c);content.addView(ui.gap(12))
        }
    }
    private fun record() {
        val t=current;val r=t?.record
        if(t==null || r==null) { records();return }
        title("WE ARRIVED","좋은 길, 좋은 동료.",t.name+"의 동행을 마쳤어요.")
        val c=ui.card(Palette.dark,26);c.addView(ui.text(t.route.destination.name,28f,Palette.white,true));c.addView(ui.gap(12));c.addView(ui.text(SimpleDateFormat("yyyy.MM.dd · HH:mm",Locale.KOREA).format(Date(t.endedAt!!)),14f,Palette.lime));c.addView(ui.gap(25))
        val metrics=ui.row()
        listOf(String.format(Locale.KOREA,"%.1f km",r.distanceKm) to "시뮬레이션 거리", "${r.durationSeconds/60}분 ${r.durationSeconds%60}초" to "체험 시간", "${t.members.size}대" to "함께한 차량").forEach { (value,label) ->
            val col=ui.col();col.addView(ui.text(value,25f,Palette.white,true));col.addView(ui.gap(8));col.addView(ui.text(label,12f,Palette.lime));ui.add(metrics,col,1f)
        };c.addView(metrics);content.addView(c);content.addView(ui.gap(15));content.addView(ui.chip("✓  모든 위치공유가 종료되었어요"));content.addView(ui.gap(20))
        val card=ui.card();card.addView(ui.text("오늘의 한 줄",19f,Palette.ink,true));card.addView(ui.gap(13))
        val review=ui.edit("함께 달린 기분을 남겨보세요.",reviewDrafts[t.id] ?: r.review,true);review.filters=arrayOf(android.text.InputFilter.LengthFilter(500));watch(review) { reviewDrafts[t.id]=it };card.addView(review);card.addView(ui.gap(15));card.addView(ui.button("후기 저장",true) { if(mutate { it.review(review.text.toString()) }) toast("후기를 저장했어요.") });content.addView(card)
        content.addView(ui.gap(15));content.addView(ui.button("새로운 동행 만들기") { navigate("create") })
        note("이 기록의 거리는 가상 속도 72km/h × 체험 시간으로 계산했습니다. 실제 차량의 주행거리가 아닙니다.")
    }
}
