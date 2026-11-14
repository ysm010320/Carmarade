package ai.camarade.app

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.widget.LinearLayout
import android.widget.TextView
import ai.camarade.core.*

/** 코드 한 글자를 보여 주는 둥근 칸. 입력 화면과 방 화면의 큰 코드 표시가 함께 쓴다. */
fun codeCell(ui: Ui, text: String, widthDp: Int, heightDp: Int, sp: Float, fill: Int, ink: Int, border: Int?=null) = TextView(ui.context).apply {
    this.text=text; textSize=sp; setTextColor(ink); gravity=Gravity.CENTER; includeFontPadding=false
    typeface=Typeface.create("sans-serif-medium",Typeface.NORMAL)
    background=GradientDrawable().apply {
        shape=GradientDrawable.RECTANGLE; cornerRadius=ui.d(14).toFloat(); setColor(fill)
        if(border!=null) setStroke(ui.d(2),border)
    }
    layoutParams=LinearLayout.LayoutParams(ui.d(widthDp),ui.d(heightDp)).apply { setMargins(ui.d(4),0,ui.d(4),0) }
}

/**
 * 초대 코드 입력: 6칸 + 화면 자판. Pleos의 전체 화면 키보드(글자 칸 하나만 보이고 한글로 시작)를 쓰지 않고,
 * 코드에 쓰는 글자(영문 대문자·숫자 31자)만 큰 버튼으로 보여 준다. 틀리면 칸을 그대로 두고 이유만 알려 준다.
 */
class CodeEntryView(context: Context, private val ui: Ui, private val onSubmit: (String) -> Unit) : LinearLayout(context) {
    var code=""; private set
    private val cells=ArrayList<TextView>()
    private val cellRow=LinearLayout(context).apply { gravity=Gravity.CENTER }
    private val message=ui.text("",14f,Color.rgb(163,45,45),true).apply { gravity=Gravity.CENTER }
    private val submit: TextView
    private var busy=false

    init {
        orientation=VERTICAL
        addView(cellRow,LayoutParams(-1,-2))
        addView(message,LayoutParams(-1,-2).apply { topMargin=ui.d(10);bottomMargin=ui.d(6) })
        val pad=LinearLayout(context).apply { orientation=VERTICAL;gravity=Gravity.CENTER_HORIZONTAL }
        codeKeypad().forEach { keys ->
            val line=LinearLayout(context).apply { gravity=Gravity.CENTER }
            keys.forEach { k -> line.addView(key(k)) }
            pad.addView(line,LayoutParams(-2,-2).apply { topMargin=ui.d(6) })
        }
        addView(pad,LayoutParams(-1,-2))
        submit=ui.button("입장",true) { if(!busy) go() }
        addView(submit,LayoutParams(-1,-2).apply { topMargin=ui.d(16) })
        redraw()
    }

    private fun key(k: String): TextView {
        val back=k==KEY_BACKSPACE
        return TextView(context).apply {
            text=k; textSize=if(back) 22f else 21f; gravity=Gravity.CENTER; includeFontPadding=false
            typeface=Typeface.create("sans-serif-medium",Typeface.NORMAL)
            setTextColor(if(back) Palette.ink else Palette.teal)
            background=ui.box(if(back) Palette.line else Palette.soft,12)
            isClickable=true; isFocusable=true
            contentDescription=if(back) "지우기" else k
            layoutParams=LinearLayout.LayoutParams(ui.d(54),ui.d(54)).apply { setMargins(ui.d(3),0,ui.d(3),0) }
            setOnClickListener { if(!busy) { code=pressCodeKey(code,k);message.text="";redraw() } }
        }
    }

    private fun go() {
        if(!isCodeComplete(code)) { showError("초대 코드 ${CODE_LENGTH}자리를 모두 눌러 주세요.");return }
        busy=true;submit.text="확인하는 중…";submit.alpha=0.6f
        onSubmit(code)
    }

    /** 입장하지 못했을 때. 입력한 코드는 남겨 두고 한 글자만 고칠 수 있게 한다. */
    fun showError(text: String) { busy=false;message.text=text;redraw() }

    private fun redraw() {
        cellRow.removeAllViews();cells.clear()
        for(i in 0 until CODE_LENGTH) {
            val filled=i<code.length;val active=i==code.length
            val cell=codeCell(ui,if(filled) code[i].toString() else "",56,68,30f,if(filled) Palette.white else Palette.soft,Palette.ink,
                if(active) Palette.teal else if(filled) Palette.line else null)
            cells.add(cell);cellRow.addView(cell)
        }
        val ready=isCodeComplete(code)
        submit.text="입장";submit.alpha=if(ready) 1f else 0.45f
    }
}
