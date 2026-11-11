package app.sichimi.core

import android.car.Car
import android.car.drivingstate.CarUxRestrictions
import android.car.drivingstate.CarUxRestrictionsManager
import android.content.Context
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** AAOS CarUxRestrictions를 구독한다. 주행 규제가 걸리면 주행 화면만 보인다(설계 5절). */
class DrivingRestrictions(private val context: Context) {
    private val _requiresDo = MutableStateFlow(false)
    val requiresDistractionOptimization: StateFlow<Boolean> = _requiresDo.asStateFlow()

    private var car: Car? = null
    private var manager: CarUxRestrictionsManager? = null

    fun start() {
        runCatching {
            val c = Car.createCar(context)
            car = c
            val m = c.getCarManager(Car.CAR_UX_RESTRICTION_SERVICE) as CarUxRestrictionsManager
            manager = m
            m.currentCarUxRestrictions?.let(::apply)
            m.registerListener { apply(it) }
        }.onFailure { Log.w(TAG, "CarUxRestrictionsManager 실패: $it") }
    }

    private fun apply(r: CarUxRestrictions) {
        Log.i(TAG, "주행 규제 requiresDO=${r.isRequiresDistractionOptimization} active=${r.activeRestrictions}")
        _requiresDo.value = r.isRequiresDistractionOptimization
    }

    fun stop() {
        runCatching { manager?.unregisterListener() }
        runCatching { car?.disconnect() }
    }

    companion object { const val TAG = "SC.Ux" }
}
