package ai.camarade.app

import android.content.Context
import android.graphics.*
import android.view.View
import ai.camarade.core.*
import ai.camarade.app.vehicle.VehicleGateway

/** 경로를 그릴 영역(화면 비율). 개념도 전체를 쓰면 (0,0)-(1,1). */
internal class RouteFrame(val l: Float=0f, val t: Float=0f, val r: Float=1f, val b: Float=1f) {
    fun x(v: Float)=l+(r-l)*v
    fun y(v: Float)=t+(b-t)*v
}
/** 개념도의 경로 꺾은선(x,y 비율 쌍). 출발 → 경유지 → 목적지. */
internal val ROUTE=floatArrayOf(.14f,.79f,.26f,.62f,.41f,.6f,.47f,.42f,.69f,.35f,.8f,.17f)

internal fun drawMapLabel(c: Canvas, p: Paint, text: String, x: Float, y: Float, size: Float, color: Int, scale: Float) {
    p.style=Paint.Style.FILL; p.color=color; p.typeface=Typeface.create("sans-serif-medium",Typeface.NORMAL); p.textSize=size*scale; c.drawText(text,x,y,p)
}

/** 지도 바탕, 경로, 목적지·경유지 표시. 계획 화면 개념도와 주행 전체 화면이 함께 쓴다. */
internal fun drawRouteBackdrop(c: Canvas, p: Paint, w: Float, h: Float, scale: Float, trip: Trip, f: RouteFrame, caption: Boolean) {
    fun line(color: Int, width: Float, points: FloatArray, frame: RouteFrame?) {
        p.color=color; p.style=Paint.Style.STROKE; p.strokeWidth=width; p.strokeCap=Paint.Cap.ROUND; p.strokeJoin=Paint.Join.ROUND
        fun px(i: Int)=(frame?.x(points[i]) ?: points[i])*w
        fun py(i: Int)=(frame?.y(points[i+1]) ?: points[i+1])*h
        val path=Path(); path.moveTo(px(0),py(0))
        for(i in 2 until points.size step 2) path.lineTo(px(i),py(i))
        c.drawPath(path,p); p.style=Paint.Style.FILL
    }
    c.drawColor(Color.rgb(232,237,224))
    p.style=Paint.Style.FILL; p.color=Color.rgb(219,229,206)
    c.drawOval(RectF(-w*.15f,-h*.15f,w*.55f,h*.55f),p)
    c.drawOval(RectF(w*.55f,h*.35f,w*1.2f,h*1.2f),p)
    line(Color.rgb(190,216,214),27*scale,floatArrayOf(.04f,.8f,.25f,.66f,.5f,.7f,.72f,.5f,1f,.54f),null)
    for(i in 0..4) line(Color.rgb(246,248,241),7*scale,floatArrayOf(0f,.12f+i*.18f,.3f,.22f+i*.12f,.68f,.10f+i*.17f,1f,.24f+i*.13f),null)
    line(Color.WHITE,17*scale,ROUTE,f)
    line(Palette.teal,7*scale,ROUTE,f)
    if(caption) drawMapLabel(c,p,"함께하는 목적지",.05f*w,.10f*h,13f,Palette.muted,scale)
    drawMapLabel(c,p,trip.route.destination.name,f.x(.50f)*w,f.y(.14f)*h,14f,Palette.ink,scale)
    p.color=Palette.teal; c.drawCircle(f.x(.8f)*w,f.y(.17f)*h,9*scale,p)
    if(trip.route.waypoints.isNotEmpty()) {
        val x=f.x(.47f)*w; val y=f.y(.42f)*h
        p.color=Color.WHITE;c.drawCircle(x,y,8*scale,p);p.color=Palette.teal;c.drawCircle(x,y,4*scale,p)
        drawMapLabel(c,p,trip.route.waypoints.first().name,f.x(.50f)*w,f.y(.46f)*h,12f,Palette.muted,scale)
    }
}

/** 경로 위 진행률(0=출발, 1=목적지)에 해당하는 화면 좌표. */
internal fun routePoint(fraction: Double, w: Float, h: Float, f: RouteFrame): PointF {
    val pts=(ROUTE.indices step 2).map { PointF(f.x(ROUTE[it])*w,f.y(ROUTE[it+1])*h) }
    val lengths=pts.zipWithNext { a,b -> Math.hypot((b.x-a.x).toDouble(),(b.y-a.y).toDouble()) }
    var left=fraction.coerceIn(0.0,1.0)*lengths.sum()
    lengths.forEachIndexed { i,len ->
        if(left<=len || i==lengths.lastIndex) {
            val k=if(len==0.0) 0f else (left/len).coerceIn(0.0,1.0).toFloat()
            return PointF(pts[i].x+(pts[i+1].x-pts[i].x)*k,pts[i].y+(pts[i+1].y-pts[i].y)*k)
        }
        left-=len
    }
    return pts.last()
}

/** 차량 표시(이름 두 글자 상자). 나는 진한 색, 일행은 흰색. 대략 위치는 넓은 원을 함께 그림. */
internal fun drawCarMarker(c: Canvas, p: Paint, m: Member, cx: Float, cy: Float, scale: Float) {
    p.style=Paint.Style.FILL
    if(m.sharing==Sharing.APPROXIMATE) { p.color=Color.argb(40,8,111,95);c.drawCircle(cx,cy,37*scale,p) }
    p.color=if(m.id=="me") Palette.dark else Color.WHITE
    c.drawRoundRect(RectF(cx-22*scale,cy-18*scale,cx+22*scale,cy+18*scale),12*scale,12*scale,p)
    p.color=if(m.id=="me") Palette.lime else Palette.teal;p.textSize=13*scale;p.textAlign=Paint.Align.CENTER
    p.typeface=Typeface.create("sans-serif-medium",Typeface.NORMAL)
    c.drawText(m.name.take(2),cx,cy+5*scale,p);p.textAlign=Paint.Align.LEFT
}

/** Schematic demo only. Never presents itself as turn-by-turn navigation. */
class RouteMapView(context: Context, private val trip: Trip, private val gateway: VehicleGateway): View(context) {
    private val p=Paint(Paint.ANTI_ALIAS_FLAG)
    init { contentDescription="동행 위치 개념도. 실제 길 안내가 아닙니다. " + trip.members.joinToString { it.name+if(it.sharing==Sharing.OFF) " 위치 숨김" else " 가상 위치" }; importantForAccessibility=IMPORTANT_FOR_ACCESSIBILITY_YES }
    override fun onDraw(c: Canvas) {
        super.onDraw(c)
        val w=width.toFloat(); val h=height.toFloat(); val scale=resources.displayMetrics.density
        drawRouteBackdrop(c,p,w,h,scale,trip,RouteFrame(),true)
        val coords=listOf(.25f to .64f,.41f to .56f,.66f to .35f,.53f to .40f,.18f to .73f)
        val now=System.currentTimeMillis()
        trip.members.forEachIndexed { i,m ->
            if(visiblePosition(m.sharing,gateway.position(i,now),now)!=null) { val (x,y)=coords[i]; drawCarMarker(c,p,m,x*w,y*h,scale) }
        }
        p.color=Color.argb(235,255,255,255);c.drawRoundRect(RectF(14*scale,h-42*scale,w-14*scale,h-12*scale),8*scale,8*scale,p)
        drawMapLabel(c,p,"가상 위치 개념도 · 실제 길 안내 아님",.06f*w,h-23*scale,11f,Palette.muted,scale)
    }
}
