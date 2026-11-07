package ai.camarade.app

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.widget.*

object Palette {
    val bg=Color.rgb(244,245,240); val ink=Color.rgb(27,46,43); val muted=Color.rgb(102,119,112)
    val teal=Color.rgb(8,111,95); val dark=Color.rgb(18,66,55); val lime=Color.rgb(219,244,163)
    val line=Color.rgb(221,227,216); val soft=Color.rgb(233,240,229); val white=Color.WHITE
}
class Ui(val context: Context) {
    fun d(n: Int) = (n*context.resources.displayMetrics.density).toInt()
    fun box(color: Int=Palette.white, radius: Int=20, stroke: Boolean=false) = GradientDrawable().apply {
        shape=GradientDrawable.RECTANGLE; cornerRadius=d(radius).toFloat(); setColor(color)
        if(stroke) setStroke(d(1),Palette.line)
    }
    fun col(padding: Int=0) = LinearLayout(context).apply { orientation=LinearLayout.VERTICAL; setPadding(d(padding),d(padding),d(padding),d(padding)) }
    fun row() = LinearLayout(context).apply { orientation=LinearLayout.HORIZONTAL; gravity=Gravity.CENTER_VERTICAL }
    fun text(value: String, size: Float=16f, color: Int=Palette.ink, bold: Boolean=false) = TextView(context).apply {
        text=value; textSize=size; setTextColor(color); includeFontPadding=false
        typeface=Typeface.create(if(bold) "sans-serif-medium" else "sans-serif",Typeface.NORMAL)
        setLineSpacing(d(3).toFloat(),1f)
    }
    fun gap(n: Int=12) = View(context).apply { layoutParams=LinearLayout.LayoutParams(1,d(n)) }
    fun card(color: Int=Palette.white,padding: Int=22) = col(padding).apply { background=box(color,22,true) }
    fun button(label: String, primary: Boolean=false, action: () -> Unit) = TextView(context).apply {
        text=label; textSize=16f; typeface=Typeface.create("sans-serif-medium",Typeface.NORMAL)
        gravity=Gravity.CENTER; minHeight=d(52); setPadding(d(18),d(12),d(18),d(12))
        setTextColor(if(primary) Palette.white else Palette.teal); background=box(if(primary) Palette.teal else Palette.soft,13)
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
    fun chip(value: String, color: Int=Palette.soft, textColor: Int=Palette.teal) = text(value,12f,textColor,true).apply {
        background=box(color,9); setPadding(d(10),d(6),d(10),d(6)); gravity=Gravity.CENTER
    }
}
