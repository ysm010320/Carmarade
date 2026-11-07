package ai.camarade.app

import android.content.Context
import android.graphics.*
import android.view.View
import ai.camarade.core.*
import ai.camarade.app.vehicle.VehicleGateway

/** Schematic demo only. Never presents itself as turn-by-turn navigation. */
class RouteMapView(context: Context, private val trip: Trip, private val gateway: VehicleGateway): View(context) {
    private val p=Paint(Paint.ANTI_ALIAS_FLAG)
    init { contentDescription="동행 위치 개념도. 실제 길 안내가 아닙니다. " + trip.members.joinToString { it.name+if(it.sharing==Sharing.OFF) " 위치 숨김" else " 가상 위치" }; importantForAccessibility=IMPORTANT_FOR_ACCESSIBILITY_YES }
    override fun onDraw(c: Canvas) {
        super.onDraw(c)
        val w=width.toFloat(); val h=height.toFloat(); val scale=resources.displayMetrics.density
        fun line(color:Int,width:Float,vararg points:Float) {
            p.color=color; p.style=Paint.Style.STROKE; p.strokeWidth=width; p.strokeCap=Paint.Cap.ROUND; p.strokeJoin=Paint.Join.ROUND
            val path=Path(); path.moveTo(points[0]*w,points[1]*h)
            for(i in 2 until points.size step 2) path.lineTo(points[i]*w,points[i+1]*h)
            c.drawPath(path,p); p.style=Paint.Style.FILL
        }
        c.drawColor(Color.rgb(232,237,224))
        p.color=Color.rgb(219,229,206)
        c.drawOval(RectF(-w*.15f,-h*.15f,w*.55f,h*.55f),p)
        c.drawOval(RectF(w*.55f,h*.35f,w*1.2f,h*1.2f),p)
        line(Color.rgb(190,216,214),27*scale,.04f,.8f,.25f,.66f,.5f,.7f,.72f,.5f,1f,.54f)
        for(i in 0..4) line(Color.rgb(246,248,241),7*scale,0f,.12f+i*.18f,.3f,.22f+i*.12f,.68f,.10f+i*.17f,1f,.24f+i*.13f)
        line(Color.WHITE,17*scale,.14f,.79f,.26f,.62f,.41f,.6f,.47f,.42f,.69f,.35f,.8f,.17f)
        line(Palette.teal,7*scale,.14f,.79f,.26f,.62f,.41f,.6f,.47f,.42f,.69f,.35f,.8f,.17f)
        fun label(text:String,x:Float,y:Float,size:Float=12f,color:Int=Palette.muted) {
            p.color=color; p.typeface=Typeface.create("sans-serif-medium",Typeface.NORMAL); p.textSize=size*scale; c.drawText(text,x*w,y*h,p)
        }
        label("함께하는 목적지",.05f,.10f,13f)
        label(trip.route.destination.name,.50f,.14f,14f,Palette.ink)
        p.color=Palette.teal; c.drawCircle(.8f*w,.17f*h,9*scale,p)
        if(trip.route.waypoints.isNotEmpty()) {
            p.color=Color.WHITE;c.drawCircle(.47f*w,.42f*h,8*scale,p);p.color=Palette.teal;c.drawCircle(.47f*w,.42f*h,4*scale,p)
            label(trip.route.waypoints.first().name,.50f,.46f,12f)
        }
        val coords=listOf(.25f to .64f,.41f to .56f,.66f to .35f,.53f to .40f,.18f to .73f)
        val now=System.currentTimeMillis()
        trip.members.forEachIndexed { i,m ->
            if(visiblePosition(m.sharing,gateway.position(i,now),now)!=null) {
                val (x,y)=coords[i]; val cx=x*w;val cy=y*h
                p.color=if(m.id=="me") Palette.dark else Color.WHITE
                c.drawRoundRect(RectF(cx-22*scale,cy-18*scale,cx+22*scale,cy+18*scale),12*scale,12*scale,p)
                p.color=if(m.id=="me") Palette.lime else Palette.teal;p.textSize=13*scale;p.textAlign=Paint.Align.CENTER
                c.drawText(m.name.take(2),cx,cy+5*scale,p);p.textAlign=Paint.Align.LEFT
                if(m.sharing==Sharing.APPROXIMATE) { p.color=Color.argb(40,8,111,95);c.drawCircle(cx,cy,37*scale,p) }
            }
        }
        p.color=Color.argb(235,255,255,255);c.drawRoundRect(RectF(14*scale,h-42*scale,w-14*scale,h-12*scale),8*scale,8*scale,p)
        label("가상 위치 개념도 · 실제 길 안내 아님",.06f,1-(23*scale/h),11f)
    }
}
