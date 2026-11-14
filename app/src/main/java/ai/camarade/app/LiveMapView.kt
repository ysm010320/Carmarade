package ai.camarade.app

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.*
import android.view.View
import android.widget.FrameLayout
import ai.camarade.core.*
import ai.camarade.app.vehicle.VehicleGateway
import org.maplibre.android.MapLibre
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.geometry.LatLngBounds
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.MapView
import org.maplibre.android.maps.Style
import org.maplibre.android.style.expressions.Expression
import org.maplibre.android.style.layers.PropertyFactory
import org.maplibre.android.style.layers.SymbolLayer

/**
 * 주행 화면의 실제 지도. OpenFreeMap 타일을 MapLibre로 그리고, 그 위에 경유지·목적지와 일행 차량을 표시한다.
 * 경로 선은 그리지 않는다(길 안내는 Pleos 내비가 맡음).
 * 온라인 방이면 서버로 받은 실제 위치를, 체험 모드면 가상 진행률 위치를 그린다.
 * 지도 스타일을 불러오지 못하면 [onFailed]로 알려 개념도로 돌아가게 한다.
 */
@SuppressLint("ViewConstructor")
class LiveMapView(context: Context, private val gateway: VehicleGateway, private val host: DriveScreen.Host, private val onFailed: () -> Unit) : FrameLayout(context) {
    var trip: Trip?=null
        set(value) { val changed=field?.route!=value?.route;field=value;if(changed) loadRoute() }
    private val mapView: MapView
    private var map: MapLibreMap?=null
    private var style: Style?=null
    private var line: RouteLine?=null
    private var framed=false
    private var focusId: String?=null
    private var focusUntil=0L
    private var released=false
    private val overlay=Overlay()

    init {
        MapLibre.getInstance(context.applicationContext)
        mapView=MapView(context).apply { onCreate(null) }
        addView(mapView,LayoutParams(-1,-1))
        addView(overlay,LayoutParams(-1,-1))
        contentDescription="동행 지도. 일행 위치는 체험용 가상 위치입니다."
        mapView.addOnDidFailLoadingMapListener { if(!released) onFailed() }
        mapView.getMapAsync { m ->
            map=m
            m.uiSettings.apply { isAttributionEnabled=false;isLogoEnabled=false;isCompassEnabled=false;isRotateGesturesEnabled=false;isTiltGesturesEnabled=false }
            m.addOnCameraMoveListener { overlay.invalidate() }
            m.setStyle(Style.Builder().fromUri(STYLE_URL)) { s -> style=s;koreanLabels(s);frame() }
        }
    }

    fun refresh() { overlay.invalidate() }
    fun focus(id: String) {
        focusId=id;focusUntil=System.currentTimeMillis()+10_000
        val pt=if(id=="all") null else spot(id)
        pt?.let { map?.animateCamera(CameraUpdateFactory.newLatLngZoom(LatLng(it.lat,it.lon),11.0),800) }
        overlay.invalidate()
    }

    /** 지도는 화면에 붙어 있는 동안만 그린다. */
    override fun onAttachedToWindow() { super.onAttachedToWindow();if(!released) { mapView.onStart();mapView.onResume() } }
    override fun onDetachedFromWindow() { if(!released) { mapView.onPause();mapView.onStop() };super.onDetachedFromWindow() }
    fun release() { if(released) return;released=true;mapView.onDestroy() }

    private fun loadRoute() {
        val route=trip?.route ?: return
        line=RouteGeometry.line(route);framed=false;frame();overlay.invalidate()
    }
    /** 처음 한 번 출발지·경유지·목적지가 모두 보이게 맞춤. 아이콘·프로필이 가리지 않도록 여백을 둠. */
    private fun frame() {
        val m=map ?: return; val l=line ?: return
        if(framed || style==null) return
        if(width==0) { post { frame() };return }
        val b=LatLngBounds.Builder().includes(l.points.map { LatLng(it.lat,it.lon) }).build()
        val d=resources.displayMetrics.density
        m.moveCamera(CameraUpdateFactory.newLatLngBounds(b,(110*d).toInt(),(90*d).toInt(),(110*d).toInt(),(110*d).toInt()))
        framed=true;overlay.invalidate()
    }
    /** OpenFreeMap 기본 라벨은 "영문 + 현지어"라서 한국어 이름을 먼저 쓰도록 바꿈. 도로 번호(ref)는 그대로. */
    private fun koreanLabels(s: Style) {
        s.layers.filterIsInstance<SymbolLayer>().forEach { layer ->
            if(runCatching { layer.textField.toString() }.getOrDefault("").contains("name"))
                runCatching { layer.setProperties(PropertyFactory.textField(Expression.coalesce(Expression.get("name:ko"),Expression.get("name")))) }
        }
    }
    private fun spot(id: String): GeoPoint?=host.livePosition(id)?.point ?: host.progress(id)?.let { p -> line?.pointAt(p) }

    /** 지도 위에 그리는 장소 표시·차량·강조 원·출처 표기. 기존 개념도와 같은 모양을 쓴다. */
    private inner class Overlay : View(context) {
        private val p=Paint(Paint.ANTI_ALIAS_FLAG)
        init { importantForAccessibility=IMPORTANT_FOR_ACCESSIBILITY_NO }
        override fun onDraw(c: Canvas) {
            super.onDraw(c)
            val t=trip ?: return; val m=map ?: return; if(style==null) return
            val s=resources.displayMetrics.density; val now=System.currentTimeMillis()
            fun screen(g: GeoPoint)=m.projection.toScreenLocation(LatLng(g.lat,g.lon))
            // 경유지·목적지
            t.route.waypoints.forEach { w ->
                val pt=screen(GeoPoint(w.latitude,w.longitude))
                p.style=Paint.Style.FILL;p.color=Color.WHITE;c.drawCircle(pt.x,pt.y,8*s,p);p.color=Palette.teal;c.drawCircle(pt.x,pt.y,4*s,p)
                label(c,w.name,pt.x+12*s,pt.y+5*s,13f,Palette.muted,s)
            }
            val dest=screen(GeoPoint(t.route.destination.latitude,t.route.destination.longitude))
            p.style=Paint.Style.FILL;p.color=Palette.teal;c.drawCircle(dest.x,dest.y,9*s,p)
            label(c,t.route.destination.name,dest.x+14*s,dest.y+5*s,15f,Palette.ink,s)
            // 차량. 앞서가는 차가 위에 오도록 뒤처진 차부터 그림.
            // 온라인 방이면 서버에서 받은 실제 위치, 체험 모드면 가상 진행률 위치.
            val spots=mutableMapOf<String,PointF>()
            t.members.mapIndexedNotNull { i,mem ->
                val live=host.livePosition(mem.id)
                when {
                    live!=null -> Triple(mem.copy(sharing=if(live.approximate) Sharing.APPROXIMATE else Sharing.EXACT),live.point,host.remainingKm(mem.id) ?: 0.0)
                    mem.id!="me" && visiblePosition(mem.sharing,gateway.position(i,now),now)==null -> null
                    else -> host.progress(mem.id)?.let { prog -> line?.pointAt(prog)?.let { Triple(mem,it,1-prog) } }
                }
            }.sortedByDescending { it.third }.forEach { (mem,geo,_) ->
                val pt=screen(geo);spots[mem.id]=pt;drawCarMarker(c,p,mem,pt.x,pt.y,s)
            }
            drawFocus(c,t,spots,now,s)
            // 출처 표기(OpenFreeMap 이용 조건)
            p.textSize=11*s;p.typeface=Typeface.DEFAULT;val text="© OpenFreeMap © OpenMapTiles © OpenStreetMap"
            val tw=p.measureText(text);val x=(width-tw)/2f;val y=58*s
            p.color=Color.argb(210,255,255,255);c.drawRoundRect(RectF(x-8*s,y-14*s,x+tw+8*s,y+6*s),6*s,6*s,p)
            p.color=Palette.muted;c.drawText(text,x,y,p)
        }
        private fun label(c: Canvas, text: String, x: Float, y: Float, size: Float, color: Int, s: Float) {
            p.textSize=size*s;p.typeface=Typeface.create("sans-serif-medium",Typeface.NORMAL)
            p.style=Paint.Style.STROKE;p.strokeWidth=4*s;p.color=Color.WHITE;c.drawText(text,x,y,p)
            p.style=Paint.Style.FILL;p.color=color;c.drawText(text,x,y,p)
        }
        private fun drawFocus(c: Canvas, t: Trip, spots: Map<String,PointF>, now: Long, s: Float) {
            val id=focusId ?: return
            if(now>=focusUntil) return
            val targets=if(id=="all") spots.filterKeys { it!="me" } else spots.filterKeys { it==id }
            p.style=Paint.Style.STROKE;p.strokeWidth=3*s;p.color=Color.rgb(216,90,48);p.pathEffect=DashPathEffect(floatArrayOf(8*s,6*s),0f)
            targets.values.forEach { c.drawCircle(it.x,it.y,34*s,p) }
            p.pathEffect=null;p.style=Paint.Style.FILL
            targets.forEach { (mid,pt) ->
                val name=t.members.find { it.id==mid }?.name ?: return@forEach
                val text=host.remainingKm(mid)?.let { "$name · ${formatRemaining(it)}" } ?: name
                p.textSize=15*s;p.typeface=Typeface.create("sans-serif-medium",Typeface.NORMAL)
                val tw=p.measureText(text); val bx=pt.x-tw/2-10*s; val by=pt.y+40*s
                p.color=Color.WHITE;c.drawRoundRect(RectF(bx,by,bx+tw+20*s,by+30*s),10*s,10*s,p)
                p.color=Palette.ink;c.drawText(text,bx+10*s,by+21*s,p)
            }
            if(targets.isEmpty()) { p.textSize=15*s;p.color=Palette.ink;p.textAlign=Paint.Align.CENTER;c.drawText("위치를 공유하지 않는 멤버예요",width/2f,height*.5f,p);p.textAlign=Paint.Align.LEFT }
        }
    }

    companion object {
        const val STYLE_URL="https://tiles.openfreemap.org/styles/liberty"
    }
}
