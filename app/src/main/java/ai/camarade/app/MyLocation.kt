package ai.camarade.app

import android.content.Context
import android.os.Handler
import android.os.Looper
import ai.pleos.playground.location.fused.FusedLocation
import ai.pleos.playground.location.fused.listener.FusedLocationListener
import ai.pleos.playground.location.fused.model.LocationData

/**
 * 내 차 위치. Pleos FusedLocation(권한 pleos.car.permission.FUSED_LOCATION)을 쓴다.
 * 차량에 위치 서비스가 없으면 [start]가 false를 돌려준다.
 */
class MyLocation(context: Context, private val onFix: (GeoPoint, Double?) -> Unit) {
    private val app=context.applicationContext
    private val main=Handler(Looper.getMainLooper())
    private var fused: FusedLocation?=null
    private val listener=object: FusedLocationListener {
        override fun onChanged(location: LocationData?) { location?.let { l -> main.post { deliver(l) } } }
        override fun onError(exception: Exception) = Unit
    }
    private fun deliver(l: LocationData) {
        if(!l.latitude.isFinite() || !l.longitude.isFinite()) return
        onFix(GeoPoint(l.latitude,l.longitude),l.heading.toDouble().takeIf { it.isFinite() })
    }

    fun start(): Boolean {
        if(fused!=null) return true
        return try {
            val f=FusedLocation(app)
            if(f.isSupported()!=true) return false
            f.initialize();f.registerFusedLocationCallback(listener);fused=f
            f.getLastKnownLocation(listener)
            true
        } catch(_: Throwable) { false }
    }
    fun stop() {
        runCatching { fused?.unregisterFusedLocationCallback(listener);fused?.release() }
        fused=null
    }

    companion object { const val PERMISSION="pleos.car.permission.FUSED_LOCATION" }
}
