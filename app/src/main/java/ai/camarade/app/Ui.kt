package ai.camarade.app

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.widget.*

/**
 * 색. Pleos 기본 화면(밝은 회색 바탕·흰 면·검정 글자·얇은 선)과 어울리게 무채색을 바탕으로 두고,
 * 카마라드 색(초록)은 눌러야 하는 것·선택된 것·일행을 나타내는 곳에만 쓴다.
 * Pleos가 낮·밤에 맞춰 밝기를 바꾸므로 시스템 야간 모드를 따라 두 벌을 가진다([apply]).
 */
object Palette {
    var night=false; private set
    fun apply(isNight: Boolean) { night=isNight }
    private fun pick(day: Int, dark: Int)=if(night) dark else day
    private fun hex(v: Long)=Color.rgb(((v shr 16) and 0xFF).toInt(),((v shr 8) and 0xFF).toInt(),(v and 0xFF).toInt())
    // 회색 단계는 Pleos 디자인 가이드(Foundations > Colors)의 basic_00~900 값과 같다.
    // 화면 바탕 = background_secondary, 면 = surface_basic, 눌린·비활성 면 = surface_high, 구분선 = surface_accent.
    val bg get()=pick(hex(0xF7F8FA),hex(0x1D1E21))
    val surface get()=pick(hex(0xFFFFFF),hex(0x131417))
    val soft get()=pick(hex(0xEDEEF2),hex(0x313236))
    val line get()=pick(hex(0xE0E1E6),hex(0x44464E))
    /** 글자: 기본(basic_900) / 보조(라이트 basic_500, 다크 basic_400). 면 위 대비 4.5:1 이상. */
    val ink get()=pick(hex(0x131417),hex(0xFFFFFF))
    val muted get()=pick(hex(0x686A72),hex(0xA4A8AE))
    /** 카마라드 포인트(초록)와 그 위의 글자, 연한 초록 면. 글자·버튼에 쓰므로 대비를 우선해 Pleos의 밝은 활성 초록(#02C265)보다 진하다. */
    val teal get()=pick(hex(0x0B7A68),hex(0x3CC2A7))
    val onAccent get()=pick(Color.WHITE,hex(0x081E1A))
    val accentSoft get()=pick(hex(0xECF7F3),hex(0x173A33))
    /** 경고·종료 아이콘: Pleos informative_negative(라이트 #FE3D16, 다크 #FF4C28). 글자에는 대비가 모자라 [dangerText]를 쓴다. */
    val danger get()=pick(hex(0xFE3D16),hex(0xFF4C28))
    val dangerText get()=pick(hex(0xC62B10),hex(0xFF4C28))
    /** 지도 위에 그리는 글자와 면. 지도 그림은 낮·밤과 관계없이 밝아서 고정값을 쓴다. */
    val mapInk=hex(0x131417)
    val mapMuted=hex(0x686A72)
    val mapAccent=hex(0x0B7A68)
    val mapSurface=Color.WHITE
    val mapSoft=hex(0xEDEEF2)
    val mapLine=hex(0xE0E1E6)
    val mapDanger=hex(0xFE3D16)
    /** 예전 이름. 카드 면과 같다. */
    val white get()=surface
}
/**
 * 화면 배율. 앱은 약 1024×620dp 화면 기준으로 만들었으므로, 그보다 큰 차량 화면(Pleos Connect 2560×1440, mdpi)에서는
 * 전체를 같은 비율로 키운다. 작은 화면에서는 1(그대로), 최대 1.8배.
 */
fun uiScale(widthDp: Int, heightDp: Int): Float =
    if(widthDp<=0 || heightDp<=0) 1f else minOf(widthDp/1024f,heightDp/620f).coerceIn(1f,1.8f)

class Ui(val context: Context) {
    fun d(n: Int) = (n*context.resources.displayMetrics.density).toInt()
    fun box(color: Int=Palette.surface, radius: Int=16, stroke: Boolean=false) = GradientDrawable().apply {
        shape=GradientDrawable.RECTANGLE; cornerRadius=d(radius).toFloat(); setColor(color)
        if(stroke) setStroke(d(1),Palette.line)
    }
    fun col(padding: Int=0) = LinearLayout(context).apply { orientation=LinearLayout.VERTICAL; setPadding(d(padding),d(padding),d(padding),d(padding)) }
    fun row() = LinearLayout(context).apply { orientation=LinearLayout.HORIZONTAL; gravity=Gravity.CENTER_VERTICAL }
    fun text(value: String, size: Float=16f, color: Int=Palette.ink, bold: Boolean=false) = TextView(context).apply {
        text=value; textSize=size; setTextColor(color); includeFontPadding=false
        typeface=Typeface.create(if(bold) "sans-serif-medium" else "sans-serif",Typeface.NORMAL)
        setLineSpacing(0f,1.35f)
    }
    fun gap(n: Int=12) = View(context).apply { layoutParams=LinearLayout.LayoutParams(1,d(n)) }
    fun card(color: Int=Palette.surface,padding: Int=22) = col(padding).apply { background=box(color,16,true) }
    fun button(label: String, primary: Boolean=false, action: () -> Unit) = TextView(context).apply {
        text=label; textSize=16f; typeface=Typeface.create("sans-serif-medium",Typeface.NORMAL)
        gravity=Gravity.CENTER; minHeight=d(56); setPadding(d(18),d(12),d(18),d(12))
        setTextColor(if(primary) Palette.onAccent else Palette.ink); background=box(if(primary) Palette.teal else Palette.soft,14)
        isClickable=true; isFocusable=true; contentDescription=label
        setOnClickListener { action() }
    }
    fun edit(hintText: String, value: String="", multiline: Boolean=false) = EditText(context).apply {
        hint=hintText; setText(value); textSize=18f; setTextColor(Palette.ink); setHintTextColor(Palette.muted)
        background=box(Palette.soft,12); setPadding(d(16),d(12),d(16),d(12)); minHeight=d(56)
        setSingleLine(!multiline); if(multiline) { minLines=3; gravity=Gravity.TOP }
    }
    fun add(container: LinearLayout, view: View, weight: Float=0f, width: Int=LinearLayout.LayoutParams.MATCH_PARENT, height: Int=LinearLayout.LayoutParams.WRAP_CONTENT, margin: Int=0) {
        container.addView(view,LinearLayout.LayoutParams(if(weight>0) 0 else width,height,weight).apply { setMargins(d(margin),0,d(margin),0) })
    }
    /**
     * 일행 프로필 원을 겹쳐 놓은 묶음(주행 화면의 프로필 원과 같은 모양). 나는 초록 면, 일행은 회색 면에 이름 첫 글자. 겹친 곳은 카드 색 테두리로 갈라 보인다.
     * [names]는 (이름, 나인지). 넷을 넘으면 마지막 원에 "+n"을 쓴다.
     */
    fun avatarStack(names: List<Pair<String,Boolean>>, sizeDp: Int=44) = LinearLayout(context).apply {
        orientation=LinearLayout.HORIZONTAL; gravity=Gravity.CENTER_VERTICAL
        val shown=if(names.size>4) names.take(3) else names
        val extra=names.size-shown.size
        fun circle(label: String, me: Boolean, more: Boolean, index: Int) = TextView(context).apply {
            text=label; textSize=17f; gravity=Gravity.CENTER; includeFontPadding=false
            typeface=Typeface.create("sans-serif-medium",Typeface.NORMAL)
            setTextColor(if(me) Palette.onAccent else Palette.ink)
            background=GradientDrawable().apply {
                shape=GradientDrawable.OVAL
                setColor(if(me) Palette.teal else Palette.line)
                setStroke(d(2),Palette.surface)
            }
            elevation=0f
            layoutParams=LinearLayout.LayoutParams(d(sizeDp),d(sizeDp)).apply { if(index>0) marginStart=-d(12) }
        }
        shown.forEachIndexed { i,(n,me) -> addView(circle(n.take(1),me,false,i)) }
        if(extra>0) addView(circle("+$extra",false,true,shown.size))
    }
    /** 설정 앱처럼 구분선으로 나눈 목록 한 덩어리. 카드를 겹겹이 쌓지 않고 면 하나에 행만 둔다. */
    fun group(vararg rows: View) = LinearLayout(context).apply {
        orientation=LinearLayout.VERTICAL; background=box(Palette.surface,16,true)
        rows.forEachIndexed { i,r ->
            if(i>0) addView(View(context).apply { setBackgroundColor(Palette.line) },LinearLayout.LayoutParams(-1,d(1)).apply { setMargins(d(20),0,0,0) })
            addView(r,LinearLayout.LayoutParams(-1,-2))
        }
    }
    /**
     * 목록의 한 행: 제목(+ 보조 글) 왼쪽, 오른쪽에 상태 칩([tags], 두 번째 값이 true면 초록으로 강조)과 눌렀을 때 일어나는 일([cta]),
     * 누를 수 있으면 화살표. 눌렀을 때 무엇이 되는지 글로 알려 주려고 [cta]를 둔다.
     */
    fun listRow(title: String, subtitle: String="", trailing: String="", tags: List<Pair<String,Boolean>> = emptyList(), cta: String="", leading: View?=null, action: (() -> Unit)?=null) = LinearLayout(context).apply {
        orientation=LinearLayout.HORIZONTAL; gravity=Gravity.CENTER_VERTICAL; minimumHeight=d(76); setPadding(d(20),d(14),d(20),d(14))
        if(leading!=null) addView(leading,LinearLayout.LayoutParams(-2,-2).apply { marginEnd=d(18) })
        val texts=LinearLayout(context).apply { orientation=LinearLayout.VERTICAL }
        texts.addView(text(title,20f,Palette.ink,true))
        if(subtitle.isNotEmpty()) { texts.addView(gap(4));texts.addView(text(subtitle,16f,Palette.muted)) }
        addView(texts,LinearLayout.LayoutParams(0,-2,1f))
        tags.forEach { (label,on) ->
            addView(chip(label,if(on) Palette.accentSoft else Palette.soft,if(on) Palette.teal else Palette.muted),LinearLayout.LayoutParams(-2,-2).apply { marginStart=d(8) })
        }
        if(trailing.isNotEmpty()) addView(text(trailing,16f,Palette.muted),LinearLayout.LayoutParams(-2,-2).apply { marginStart=d(10) })
        if(cta.isNotEmpty()) addView(text(cta,18f,Palette.teal,true),LinearLayout.LayoutParams(-2,-2).apply { marginStart=d(16) })
        if(action!=null) {
            addView(text("›",26f,Palette.muted),LinearLayout.LayoutParams(-2,-2).apply { marginStart=d(10) })
            isClickable=true; isFocusable=true; contentDescription=if(cta.isEmpty()) title else "$title, $cta"; setOnClickListener { action() }
        }
    }
    /** 여럿 중 하나를 고르는 행: 왼쪽 동그라미(고르면 초록 면 + 체크), 제목, 보조 글. 행 전체가 눌림 영역이다. */
    fun choiceRow(title: String, subtitle: String, selected: Boolean, action: () -> Unit) = LinearLayout(context).apply {
        orientation=LinearLayout.HORIZONTAL; gravity=Gravity.CENTER_VERTICAL; minimumHeight=d(84); setPadding(d(20),d(14),d(20),d(14))
        val mark=TextView(context).apply {
            text=if(selected) "✓" else ""; textSize=17f; gravity=Gravity.CENTER; setTextColor(Palette.onAccent); includeFontPadding=false
            background=GradientDrawable().apply { shape=GradientDrawable.OVAL; if(selected) setColor(Palette.teal) else { setColor(Color.TRANSPARENT);setStroke(d(2),Palette.line) } }
        }
        addView(mark,LinearLayout.LayoutParams(d(32),d(32)).apply { marginEnd=d(18) })
        val texts=LinearLayout(context).apply { orientation=LinearLayout.VERTICAL }
        texts.addView(text(title,20f,Palette.ink,selected));texts.addView(gap(4));texts.addView(text(subtitle,16f,Palette.muted))
        addView(texts,LinearLayout.LayoutParams(0,-2,1f))
        isClickable=true; isFocusable=true; contentDescription=title+if(selected) ", 선택됨" else ""; setOnClickListener { action() }
    }
    /**
     * 위쪽 탭: 아이콘 + 글자. 고른 탭은 진한 글자·초록 아이콘과 초록 밑줄, 나머지는 보조 색.
     * [compact]는 절반 폭 창용: 아이콘 위에 글자를 쌓아 다섯 탭이 모두 한 줄에 들어가게 한다.
     */
    fun tab(label: String, selected: Boolean, icon: Int, compact: Boolean=false, action: () -> Unit) = LinearLayout(context).apply {
        orientation=LinearLayout.VERTICAL; isClickable=true; isFocusable=true; contentDescription=label; setOnClickListener { action() }
        val line=LinearLayout(context).apply {
            orientation=if(compact) LinearLayout.VERTICAL else LinearLayout.HORIZONTAL; gravity=if(compact) Gravity.CENTER_HORIZONTAL else Gravity.CENTER_VERTICAL
            if(compact) setPadding(d(4),d(10),d(4),d(8)) else setPadding(d(16),d(14),d(16),d(12))
        }
        val tint=if(selected) Palette.teal else Palette.muted
        line.addView(android.widget.ImageView(context).apply { setImageResource(icon);setColorFilter(tint) },LinearLayout.LayoutParams(d(26),d(26)).apply { if(!compact) marginEnd=d(8) })
        line.addView(text(label,if(compact) 15f else 18f,if(selected) Palette.ink else Palette.muted,selected).apply { if(compact) { gravity=Gravity.CENTER;maxLines=1 } })
        addView(line)
        addView(View(context).apply { setBackgroundColor(if(selected) Palette.teal else Color.TRANSPARENT) },LinearLayout.LayoutParams(-1,d(3)))
    }
    fun chip(value: String, color: Int=Palette.soft, textColor: Int=Palette.muted) = text(value,14f,textColor,true).apply {
        background=box(color,9); setPadding(d(10),d(6),d(10),d(6)); gravity=Gravity.CENTER
    }
}
