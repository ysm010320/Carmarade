package ai.camarade.app

import android.animation.LayoutTransition
import android.annotation.SuppressLint
import android.content.Context
import android.graphics.*
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.animation.Interpolator
import android.widget.*
import ai.camarade.core.*
import ai.camarade.app.vehicle.VehicleGateway
import kotlin.math.cos
import kotlin.math.exp

/** 비눗방울처럼 커졌다가 탄성 있게 "띠용" 하고 자리 잡는 움직임. */
private object Boing : Interpolator {
    override fun getInterpolation(t: Float)=(1-exp(-6.0*t)*cos(14.0*t)).toFloat()
}

/** 말풍선 배경: 둥근 사각형 + 왼쪽 아래 꼬리. */
private class BubbleBackground(private val fill: Int, private val stroke: Int, private val density: Float) : Drawable() {
    private val p=Paint(Paint.ANTI_ALIAS_FLAG)
    val tail=8*density
    override fun draw(c: Canvas) {
        val b=bounds; val sw=1.5f*density; val r=18*density
        val body=Path().apply { addRoundRect(RectF(b.left+sw,b.top+sw,b.right-sw,b.bottom-tail),r,r,Path.Direction.CW) }
        val x=b.left+20*density
        val tip=Path().apply { moveTo(x,b.bottom-tail-sw*2);lineTo(x+14*density,b.bottom-tail-sw*2);lineTo(x+2*density,b.bottom-sw);close() }
        body.op(tip,Path.Op.UNION)
        p.style=Paint.Style.FILL;p.color=fill;c.drawPath(body,p)
        p.style=Paint.Style.STROKE;p.strokeWidth=sw;p.color=stroke;c.drawPath(body,p)
    }
    override fun setAlpha(alpha: Int) { p.alpha=alpha }
    override fun setColorFilter(cf: ColorFilter?) { p.colorFilter=cf }
    @Deprecated("Deprecated in Java") override fun getOpacity()=PixelFormat.TRANSLUCENT
}

private data class BubbleStyle(val fill: Int, val stroke: Int, val ink: Int)
private val REST_STYLE=BubbleStyle(Color.rgb(250,238,218),Color.rgb(239,159,39),Color.rgb(99,56,6))
private val OK_STYLE=BubbleStyle(Color.rgb(230,241,251),Color.rgb(133,183,235),Color.rgb(12,68,124))
private val DISTANCE_STYLE=BubbleStyle(Color.rgb(225,245,238),Color.rgb(93,202,165),Color.rgb(8,80,65))
/** Boing 효과가 가장 크게 부풀 때(약 1.26배)를 덮는 여유 비율. */
private const val OVERSHOOT_ROOM=0.3f
private const val REST_LABEL="모두에게 잠깐 쉬어가요"
private const val OK_LABEL="모두에게 잘 따라가고 있어요"
private val TEXT_STYLE=BubbleStyle(Color.WHITE,Color.rgb(196,206,192),Palette.mapInk)

/** 주행 체험 전체 화면 지도. 실제 길 안내가 아닌 개념도. */
@SuppressLint("ViewConstructor")
class DriveMapView(context: Context, private val gateway: VehicleGateway, private val host: DriveScreen.Host) : View(context) {
    var trip: Trip?=null
    private val p=Paint(Paint.ANTI_ALIAS_FLAG)
    // 아이콘·프로필이 가리지 않도록 경로는 가운데 영역에 그림.
    private val frame=RouteFrame(.18f,.12f,.86f,.74f)
    private var focusId: String?=null
    private var focusUntil=0L
    init { contentDescription="동행 위치 개념도. 실제 길 안내가 아닙니다."; importantForAccessibility=IMPORTANT_FOR_ACCESSIBILITY_YES }
    fun focus(id: String) { focusId=id; focusUntil=System.currentTimeMillis()+10_000; invalidate() }
    override fun onDraw(c: Canvas) {
        super.onDraw(c)
        val t=trip ?: return
        val w=width.toFloat(); val h=height.toFloat(); val s=resources.displayMetrics.density; val now=System.currentTimeMillis()
        drawRouteBackdrop(c,p,w,h,s,t,frame,false)
        val spots=mutableMapOf<String,PointF>()
        // 앞서가는 차가 위에 오도록 뒤처진 차부터 그림.
        t.members.mapIndexedNotNull { i,m -> host.progress(m.id)?.let { Triple(i,m,it) } }.sortedBy { it.third }.forEach { (i,m,prog) ->
            if(m.id!="me" && visiblePosition(m.sharing,gateway.position(i,now),now)==null) return@forEach
            val pt=routePoint(prog,w,h,frame); spots[m.id]=pt; drawCarMarker(c,p,m,pt.x,pt.y,s)
        }
        val id=focusId
        if(id!=null && now<focusUntil) {
            val targets=if(id=="all") spots.filterKeys { it!="me" } else spots.filterKeys { it==id }
            p.style=Paint.Style.STROKE;p.strokeWidth=3*s;p.color=Color.rgb(216,90,48);p.pathEffect=DashPathEffect(floatArrayOf(8*s,6*s),0f)
            targets.values.forEach { c.drawCircle(it.x,it.y,34*s,p) }
            p.pathEffect=null;p.style=Paint.Style.FILL
            targets.forEach { (mid,pt) ->
                val name=t.members.find { it.id==mid }?.name ?: return@forEach
                val label=host.remainingKm(mid)?.let { "$name · ${formatRemaining(it)}" } ?: name
                p.textSize=15*s;p.typeface=Typeface.create("sans-serif-medium",Typeface.NORMAL)
                val tw=p.measureText(label); val bx=pt.x-tw/2-10*s; val by=pt.y+40*s
                p.color=Color.WHITE;c.drawRoundRect(RectF(bx,by,bx+tw+20*s,by+30*s),10*s,10*s,p)
                p.color=Palette.ink;c.drawText(label,bx+10*s,by+21*s,p)
            }
            if(targets.isEmpty()) { p.textSize=15*s;p.color=Palette.ink;p.textAlign=Paint.Align.CENTER;c.drawText("위치를 공유하지 않는 멤버예요",w/2,h*.5f,p);p.textAlign=Paint.Align.LEFT }
        }
    }
}

/**
 * 주행 체험 화면(내비게이션 전체 화면).
 * 왼쪽 위 빠른 메시지(모두에게), 오른쪽 위치공유·음성 읽어주기·주행 종료, 오른쪽 아래 메시지 기록,
 * 왼쪽 아래 프로필과 그 위로 튀어 오르는 말풍선.
 */
@SuppressLint("ViewConstructor")
class DriveScreen(context: Context, private val ui: Ui, gateway: VehicleGateway, private val host: Host) : FrameLayout(context) {
    interface Host {
        fun quick(text: String, recipient: String)
        fun voiceTo(recipient: String)
        fun call(recipient: String)
        fun openPrivacy()
        /** Pleos 내비 지도에서 모임 경로 전체 보기. */
        fun openNaviMap()
        fun toggleSound()
        fun finishTrip()
        fun showHistory()
        /** 쉬어가요 버튼을 다시 눌러 끔. 예정된 정차를 취소한다. */
        fun cancelRest()
        fun remainingKm(memberId: String): Double?
        fun progress(memberId: String): Double?
        /** 온라인 방에서 서버로 받은 실제 위치. 체험 모드면 null(진행률로 위치를 정함). */
        fun livePosition(memberId: String): LivePoint?=null
    }
    private class Shown(val view: View, val restOwner: String?, val seq: Long, val expire: Runnable?)

    var tripId=""; private set
    private var trip: Trip?=null
    // 개념도는 실제 지도(OpenFreeMap)를 불러오지 못할 때를 위해 아래에 깔아 둠.
    private val map=DriveMapView(context,gateway,host)
    private var live: LiveMapView?=LiveMapView(context,gateway,host) { post { dropLiveMap() } }
    private val statusChip=ui.chip("체험 · 가상 일행 · 실제 길 안내 아님",Color.argb(225,255,255,255),Palette.mapMuted)
    private val row=LinearLayout(context)
    private val slots=linkedMapOf<String,LinearLayout>()
    private val avatars=mutableMapOf<String,TextView>()
    private val bubbles=mutableMapOf<String,Shown>()
    private var seq=0L
    private val soundButton: ImageView
    private lateinit var restButton: ImageView
    private lateinit var okButton: ImageView
    private val okOff=Runnable { setActive(okButton,false,OK_LABEL) }
    private val restOn: Boolean get()=restButton.isSelected
    private val random=java.util.Random()
    private var menu: PopupWindow?=null
    private val density=resources.displayMetrics.density

    init {
        setBackgroundColor(Palette.bg)
        addView(map,LayoutParams(-1,-1))
        live?.let { addView(it,LayoutParams(-1,-1)) }
        addView(statusChip,LayoutParams(-2,-2,Gravity.TOP or Gravity.CENTER_HORIZONTAL).apply { topMargin=ui.d(18) })
        val quick=LinearLayout(context).apply { orientation=LinearLayout.HORIZONTAL }
        // 모두에게 보내는 빠른 메시지는 말풍선 대신 버튼으로 보냈음을 표시함.
        // 쉬어가요: 누르면 켜짐(모두에게 보냄), 다시 누르면 꺼짐(쉬어가기 취소). 정차하면 저절로 꺼짐.
        restButton=icon(R.drawable.ic_coffee,REST_LABEL) {
            if(restOn) { setActive(restButton,false,REST_LABEL);host.cancelRest() }
            else { host.quick(REST_TEXT,"all");setActive(restButton,true,REST_LABEL) }
        }
        // 따봉: 잠깐 켜졌다 꺼지고, 작은 따봉이 거품처럼 뽀글뽀글 올라가며 사라짐.
        okButton=icon(R.drawable.ic_thumb_up,OK_LABEL) {
            host.quick(OK_TEXT,"all");setActive(okButton,true,OK_LABEL)
            removeCallbacks(okOff);postDelayed(okOff,450)
            fizz(okButton,R.drawable.ic_thumb_up)
        }
        quick.addView(restButton);quick.addView(space());quick.addView(okButton)
        addView(quick,LayoutParams(-2,-2,Gravity.TOP or Gravity.START).apply { setMargins(ui.d(16),ui.d(16),0,0) })
        val side=LinearLayout(context).apply { orientation=LinearLayout.VERTICAL }
        side.addView(icon(R.drawable.ic_map,"Pleos 내비 지도에서 경로 보기") { host.openNaviMap() })
        side.addView(space());side.addView(icon(R.drawable.ic_pin,"위치공유 설정") { host.openPrivacy() })
        soundButton=icon(R.drawable.ic_voice,"음성 읽어주기") { host.toggleSound() }
        side.addView(space());side.addView(soundButton)
        side.addView(space());side.addView(icon(R.drawable.ic_flag,"주행 종료",Palette.danger) { host.finishTrip() })
        addView(side,LayoutParams(-2,-2,Gravity.TOP or Gravity.END).apply { setMargins(0,ui.d(16),ui.d(16),0) })
        addView(icon(R.drawable.ic_chat,"메시지 기록") { host.showHistory() },LayoutParams(ui.d(60),ui.d(60),Gravity.BOTTOM or Gravity.END).apply { setMargins(0,0,ui.d(16),ui.d(16)) })
        row.orientation=LinearLayout.HORIZONTAL;row.gravity=Gravity.BOTTOM;row.layoutTransition=sliding()
        // 말풍선이 띠용 하고 부풀 때 자기 영역 밖으로 그려지도록 잘라내기를 끔.
        clipChildren=false;row.clipChildren=false
        addView(row,LayoutParams(-2,-2,Gravity.BOTTOM or Gravity.START).apply { setMargins(ui.d(16),0,0,ui.d(16)) })
    }

    /** 말풍선 크기가 바뀌면 옆 프로필이 부드럽게 밀려나고 돌아오게 함. 나타남·사라짐 효과는 직접 처리. */
    private fun sliding()=LayoutTransition().apply {
        enableTransitionType(LayoutTransition.CHANGING)
        setAnimator(LayoutTransition.APPEARING,null);setAnimator(LayoutTransition.DISAPPEARING,null)
        setDuration(260)
    }
    private fun space()=View(context).apply { layoutParams=ViewGroup.LayoutParams(ui.d(10),ui.d(10)) }
    private fun icon(res: Int, label: String, tint: Int=Palette.teal, onClick: () -> Unit)=ImageView(context).apply {
        setImageResource(res);setColorFilter(tint);scaleType=ImageView.ScaleType.CENTER_INSIDE
        setPadding(ui.d(15),ui.d(15),ui.d(15),ui.d(15));background=ui.box(Palette.surface,16,true)
        contentDescription=label;isClickable=true;isFocusable=true;layoutParams=ViewGroup.LayoutParams(ui.d(60),ui.d(60))
        setOnClickListener { onClick() }
    }

    fun bind(t: Trip, soundOn: Boolean) {
        trip=t;tripId=t.id;map.trip=t;map.invalidate();live?.trip=t
        soundButton.background=ui.box(if(soundOn) Palette.teal else Palette.surface,16,true)
        soundButton.setColorFilter(if(soundOn) Palette.onAccent else Palette.muted)
        soundButton.contentDescription=if(soundOn) "음성 읽어주기 켜짐" else "음성 읽어주기 꺼짐"
        val ids=listOf("all")+t.members.filter { it.id!="me" }.map { it.id }
        if(ids==slots.keys.toList()) return
        menu?.dismiss();row.removeAllViews();slots.clear();avatars.clear()
        bubbles.values.forEach { s -> s.expire?.let { removeCallbacks(it) } };bubbles.clear()
        ids.forEach { id ->
            val slot=LinearLayout(context).apply { orientation=LinearLayout.VERTICAL;gravity=Gravity.START;layoutTransition=sliding();clipChildren=false }
            val avatar=TextView(context).apply {
                text=nameOf(id).take(2);textSize=16f;gravity=Gravity.CENTER;includeFontPadding=false
                typeface=Typeface.create("sans-serif-medium",Typeface.NORMAL);contentDescription="${nameOf(id)} 메뉴 열기"
                isClickable=true;isFocusable=true;setOnClickListener { openMenu(id,this) }
            }
            paintAvatar(id,avatar,false)
            slot.addView(avatar,LinearLayout.LayoutParams(ui.d(60),ui.d(60)))
            row.addView(slot,LinearLayout.LayoutParams(-2,-2).apply { marginEnd=ui.d(10) })
            slots[id]=slot;avatars[id]=avatar
        }
    }
    fun refresh() { map.invalidate();live?.refresh() }
    /** 위쪽 가운데 상태 표시(체험 모드 안내, 온라인 방 연결 상태). */
    fun status(text: String) { if(statusChip.text!=text) statusChip.text=text }
    fun focus(id: String) { map.focus(id);live?.focus(id) }
    /** 지도 자원 해제. 주행 화면을 버릴 때 부른다. */
    fun release() { live?.release() }
    /** 실제 지도를 불러오지 못하면 개념도로 돌아감. */
    private fun dropLiveMap() { live?.let { removeView(it);it.release() };live=null }

    private fun nameOf(id: String)=when(id) { "all"->"모두";"me"->"나";else->trip?.members?.find { it.id==id }?.name ?: "멤버" }
    private fun paintAvatar(id: String, v: TextView, open: Boolean) {
        val all=id=="all"
        v.background=GradientDrawable().apply {
            shape=GradientDrawable.OVAL
            setColor(if(open) Palette.soft else if(all) Palette.teal else Palette.surface)
            setStroke(ui.d(if(open) 3 else 1),if(open) Palette.teal else Palette.line)
        }
        v.setTextColor(if(open) Palette.teal else if(all) Palette.onAccent else Palette.ink)
    }

    /** 메시지 말풍선. 모두에게 → "모두" 위에 "이름:", 나에게 → 보낸 사람 위, 내가 한 사람에게 → 그 사람 위에 "나:". */
    fun showMessage(senderId: String, recipient: String, text: String) {
        val slot=if(recipient=="all") "all" else if(senderId=="me") recipient else senderId
        val prefix=if(slot=="all") nameOf(senderId) else if(senderId=="me") "나" else null
        val kind=quickKind(text)
        val view=when(kind) {
            QuickKind.REST->bubble(prefix,R.drawable.ic_coffee,null,REST_STYLE,"잠깐 쉬어가요")
            QuickKind.OK->bubble(prefix,R.drawable.ic_thumb_up,null,OK_STYLE,"잘 따라가고 있어요")
            QuickKind.TEXT->bubble(prefix,null,text,TEXT_STYLE,text)
        }
        put(slot,view,if(kind==QuickKind.REST) senderId else null)
    }
    fun showDistance(memberId: String, label: String) {
        put(memberId,bubble(null,R.drawable.ic_flag,label,DISTANCE_STYLE,if(label==ARRIVING_LABEL) "곧 도착" else "$label 남음"),null)
    }
    /** 정차하면 그 사람이 보낸 쉬어가요 말풍선을 닫음. */
    fun clearRest(ownerId: String) {
        bubbles.filterValues { it.restOwner==ownerId }.keys.toList().forEach { remove(it,null) }
        if(ownerId=="me") setActive(restButton,false,REST_LABEL)
    }
    /**
     * 버튼 근처에서 작은 아이콘 여러 개가 거품처럼 생겨 흔들리며 오른쪽 위로 떠오르다 사라짐.
     * 버튼이 화면 위쪽에 있어 위로만 보내면 바로 잘리므로, 지도 쪽(오른쪽)으로 퍼지게 함.
     */
    private fun fizz(anchor: View, res: Int) {
        val me=IntArray(2);getLocationInWindow(me);val at=IntArray(2);anchor.getLocationInWindow(at)
        val cx=at[0]-me[0]+anchor.width/2f;val cy=at[1]-me[1]+anchor.height/2f
        repeat(8) { i ->
            val size=ui.d(18+random.nextInt(16))
            val v=ImageView(context).apply {
                setImageResource(res);setColorFilter(Palette.teal);alpha=0f;scaleX=0f;scaleY=0f
                importantForAccessibility=IMPORTANT_FOR_ACCESSIBILITY_NO
            }
            addView(v,LayoutParams(size,size))
            val x0=cx-size/2f;val y0=cy-size/2f
            val dx=ui.d(20+random.nextInt(110)).toFloat();val dy=-ui.d(random.nextInt(45)).toFloat()+ui.d(25)
            val wobble=ui.d(4+random.nextInt(6)).toFloat();val phase=random.nextFloat()*6f
            android.animation.ValueAnimator.ofFloat(0f,1f).apply {
                startDelay=i*55L;duration=800L+random.nextInt(400)
                addUpdateListener {
                    val t=it.animatedFraction
                    v.translationX=x0+dx*t+kotlin.math.sin(t*12f+phase)*wobble
                    v.translationY=y0+dy*t-ui.d(30)*t*t
                    val s=Boing.getInterpolation((t*2.2f).coerceAtMost(1f))*(1f-0.3f*t)
                    v.scaleX=s;v.scaleY=s
                    v.alpha=if(t<0.1f) t*10f else (1f-(t-0.1f)/0.9f)
                }
                addListener(object: android.animation.AnimatorListenerAdapter() {
                    override fun onAnimationEnd(animation: android.animation.Animator) { removeView(v) }
                })
                start()
            }
        }
    }
    /** 쉬어가요 버튼은 켜고 끌 수 있고(정차하면 꺼짐), 따봉 버튼은 누른 순간만 잠깐 켜짐. */
    private fun setActive(b: ImageView, on: Boolean, label: String) {
        b.background=ui.box(if(on) Palette.teal else Palette.surface,16,true)
        b.setColorFilter(if(on) Palette.onAccent else Palette.teal)
        b.isSelected=on;b.contentDescription=if(on) "$label · 보냄" else label
    }

    private fun bubble(prefix: String?, iconRes: Int?, label: String?, style: BubbleStyle, spoken: String): View {
        val bg=BubbleBackground(style.fill,style.stroke,density)
        val v=LinearLayout(context).apply {
            orientation=LinearLayout.HORIZONTAL;gravity=Gravity.CENTER_VERTICAL;background=bg
            setPadding(ui.d(14),ui.d(9),ui.d(14),ui.d(9)+bg.tail.toInt())
            contentDescription=listOfNotNull(prefix,spoken).joinToString(" ")
        }
        prefix?.let { v.addView(ui.text("$it:",17f,style.ink,true)) }
        iconRes?.let { res ->
            v.addView(ImageView(context).apply { setImageResource(res);setColorFilter(style.ink) },
                LinearLayout.LayoutParams(ui.d(28),ui.d(28)).apply { if(prefix!=null) marginStart=ui.d(6) })
        }
        label?.let {
            v.addView(ui.text(it,17f,style.ink,true).apply { maxWidth=ui.d(420);isSingleLine=true;ellipsize=TextUtils.TruncateAt.END },
                LinearLayout.LayoutParams(-2,-2).apply { if(iconRes!=null || prefix!=null) marginStart=ui.d(6) })
        }
        return v
    }

    /**
     * 프로필마다 일반 말풍선은 하나(새 것이 이전 것을 바꿈). 쉬어가요는 보낸 사람별로 따로 두어
     * 다른 메시지가 와도 정차 전까지 닫히지 않게 하고, 그 위에 새 말풍선을 쌓음.
     */
    private fun put(slotId: String, view: View, restOwner: String?) {
        val slot=slots[slotId] ?: return
        val key=if(restOwner!=null) "$slotId|rest|$restOwner" else "$slotId|msg"
        bubbles.remove(key)?.let { old -> old.expire?.let { removeCallbacks(it) };slot.removeView(old.view) }
        // 쉬어가요는 정차할 때까지, 나머지는 10초 뒤 닫힘.
        val expire=if(restOwner==null) Runnable { remove(key,view) } else null
        bubbles[key]=Shown(view,restOwner,++seq,expire)
        slot.addView(view,0,LinearLayout.LayoutParams(-2,-2).apply { bottomMargin=ui.d(6) })
        boing(view,ui.d(20).toFloat())
        expire?.let { postDelayed(it,10_000) }
        fit(key)
    }
    private fun remove(key: String, only: View?) {
        val s=bubbles[key] ?: return
        if(only!=null && s.view!==only) return
        bubbles.remove(key);s.expire?.let { removeCallbacks(it) }
        s.view.animate().scaleX(0f).scaleY(0f).alpha(0f).setDuration(160).withEndAction { (s.view.parent as? ViewGroup)?.removeView(s.view) }.start()
    }
    /** 말풍선이 길어져 프로필 줄이 메시지 기록 버튼까지 닿으면, 방금 띄운 것은 두고 오래된 말풍선부터 닫음. 정차 전 쉬어가요는 마지막까지 남김. */
    private fun fit(keep: String) {
        if(width==0) { post { fit(keep) };return }
        val limit=width-ui.d(16+16+60+16)
        repeat(6) {
            row.measure(MeasureSpec.makeMeasureSpec(0,MeasureSpec.UNSPECIFIED),MeasureSpec.makeMeasureSpec(0,MeasureSpec.UNSPECIFIED))
            if(row.measuredWidth<=limit) return
            val victim=bubbles.entries.filter { it.key!=keep }.sortedWith(compareBy({ it.value.restOwner!=null },{ it.value.seq })).firstOrNull() ?: return
            bubbles.remove(victim.key);victim.value.expire?.let { removeCallbacks(it) }
            (victim.value.view.parent as? ViewGroup)?.removeView(victim.value.view)
        }
    }
    private fun boing(v: View, pivotX: Float) {
        v.scaleX=0f;v.scaleY=0f;v.alpha=0f
        v.post { v.pivotX=pivotX;v.pivotY=v.height.toFloat();v.animate().scaleX(1f).scaleY(1f).alpha(1f).setDuration(700).setInterpolator(Boing).start() }
    }

    /** 프로필을 누르면 그 위로 통화·음성 메시지·위치 보기(+한 사람에게 커피·따봉)가 비눗방울처럼 뜸. */
    private fun openMenu(id: String, anchor: TextView) {
        val wasOpen=menu?.isShowing==true && anchor.tag=="open"
        menu?.dismiss()
        if(wasOpen) return
        val bg=BubbleBackground(Color.WHITE,Color.rgb(196,206,192),density)
        val content=LinearLayout(context).apply {
            orientation=LinearLayout.HORIZONTAL;gravity=Gravity.CENTER_VERTICAL;background=bg
            setPadding(ui.d(10),ui.d(10),ui.d(10),ui.d(10)+bg.tail.toInt())
        }
        fun close(action: () -> Unit): () -> Unit = { menu?.dismiss();action() }
        fun add(v: View, width: Int=-2) { content.addView(v,LinearLayout.LayoutParams(width,ui.d(56)).apply { marginEnd=ui.d(8) }) }
        add(menuButton(R.drawable.ic_phone,"통화",close { host.call(id) }))
        add(menuButton(R.drawable.ic_mic,"음성 메시지",close { host.voiceTo(id) }))
        add(menuButton(R.drawable.ic_my_location,"위치 보기",close { focus(id) }))
        if(id!="all") {
            add(icon(R.drawable.ic_coffee,"${nameOf(id)}에게 잠깐 쉬어가요",onClick=close { host.quick(REST_TEXT,id) }),ui.d(56))
            add(icon(R.drawable.ic_thumb_up,"${nameOf(id)}에게 잘 따라가고 있어요",onClick=close { host.quick(OK_TEXT,id) }),ui.d(56))
        }
        content.measure(MeasureSpec.makeMeasureSpec(0,MeasureSpec.UNSPECIFIED),MeasureSpec.makeMeasureSpec(0,MeasureSpec.UNSPECIFIED))
        // 띠용 효과는 잠깐 실제 크기의 약 1.26배까지 부풀어서, 메뉴 창이 딱 맞으면 위·오른쪽이 잘림.
        // 왼쪽 아래(꼬리)를 기준으로 커지므로 위·오른쪽에 투명한 여유 공간을 둔다. 여유 공간을 누르면 닫힘.
        val roomTop=(content.measuredHeight*OVERSHOOT_ROOM).toInt();val roomEnd=(content.measuredWidth*OVERSHOOT_ROOM).toInt()
        val room=FrameLayout(context).apply {
            clipChildren=false;clipToPadding=false;setPadding(0,roomTop,roomEnd,0)
            setOnClickListener { menu?.dismiss() }
            addView(content,LayoutParams(-2,-2))
        }
        val pw=PopupWindow(room,-2,-2,true).apply {
            isOutsideTouchable=true;setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            // 여유 공간 때문에 화면 밖으로 나가도 메뉴 위치를 옮기지 않게 함.
            isClippingEnabled=false
            setOnDismissListener { anchor.tag=null;paintAvatar(id,anchor,false) }
        }
        val loc=IntArray(2);anchor.getLocationInWindow(loc)
        pw.showAtLocation(this,Gravity.NO_GRAVITY,loc[0],loc[1]-content.measuredHeight-roomTop-ui.d(6))
        anchor.tag="open";paintAvatar(id,anchor,true)
        menu=pw
        boing(content,ui.d(22).toFloat())
    }
    private fun menuButton(res: Int, label: String, onClick: () -> Unit)=TextView(context).apply {
        text=label;textSize=16f;setTextColor(Palette.teal);gravity=Gravity.CENTER;contentDescription=label
        typeface=Typeface.create("sans-serif-medium",Typeface.NORMAL);setPadding(ui.d(14),0,ui.d(16),0)
        background=ui.box(Palette.soft,13);isClickable=true;isFocusable=true
        val d=context.getDrawable(res)!!.mutate().apply { setTint(Palette.teal);setBounds(0,0,ui.d(24),ui.d(24)) }
        setCompoundDrawablesRelative(d,null,null,null);compoundDrawablePadding=ui.d(8)
        setOnClickListener { onClick() }
    }
    override fun onDetachedFromWindow() { menu?.dismiss();super.onDetachedFromWindow() }
}
