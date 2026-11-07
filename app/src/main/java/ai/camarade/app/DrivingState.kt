package ai.camarade.app

import android.car.Car
import android.car.drivingstate.CarUxRestrictionsManager
import android.content.Context
import android.content.pm.PackageManager

/**
 * 차량이 "주행 최적화 필요" 상태인지(움직이거나 D단에 있는지) Android CarUxRestrictions로 알려 준다.
 * 차량 기기가 아니면 만들지 않음(android.car 라이브러리가 없음).
 */
class DrivingState private constructor(context: Context, private val changed: (Boolean) -> Unit) {
    var restricted=false; private set
    private val car: Car?=Car.createCar(context)
    private val manager=car?.getCarManager(Car.CAR_UX_RESTRICTION_SERVICE) as? CarUxRestrictionsManager

    init {
        restricted=manager?.currentCarUxRestrictions?.isRequiresDistractionOptimization ?: false
        manager?.registerListener { r ->
            val now=r.isRequiresDistractionOptimization
            if(now!=restricted) { restricted=now;changed(now) }
        }
    }
    fun release() { runCatching { manager?.unregisterListener() };runCatching { car?.disconnect() } }

    companion object {
        fun attach(context: Context, changed: (Boolean) -> Unit): DrivingState? =
            if(!context.packageManager.hasSystemFeature(PackageManager.FEATURE_AUTOMOTIVE)) null
            else runCatching { DrivingState(context,changed) }.getOrNull()
    }
}
