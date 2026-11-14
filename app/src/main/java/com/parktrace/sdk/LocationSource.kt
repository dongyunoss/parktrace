package com.parktrace.sdk

import ai.pleos.playground.location.fused.FusedLocation
import ai.pleos.playground.location.fused.listener.FusedLocationListener
import ai.pleos.playground.location.fused.listener.ProximityAlertListener
import ai.pleos.playground.location.fused.model.LocationData
import ai.pleos.playground.location.fused.model.ProximityAlertEvent
import ai.pleos.playground.location.fused.model.ProximityAlertRequest
import ai.pleos.playground.location.fused.model.ProximityTransition
import android.content.Context
import android.util.Log
import com.parktrace.core.Signal
import com.parktrace.data.GarageRegistry

/**
 * Pleos FusedLocation SDK (GPS + IMU fusion) -> [Signal.Location],
 * plus proximity alerts (geofences) for garages the app has learned.
 */
class LocationSource(
    context: Context,
    private val garages: GarageRegistry,
    private val emit: (Signal) -> Unit,
) {
    private val fused = FusedLocation(context.applicationContext)
    private var initialized = false
    private val alertIdToGarage = HashMap<String, String>()

    val isSupported: Boolean get() = runCatching { fused.isSupported() }.getOrDefault(false)

    private val locationListener = object : FusedLocationListener {
        override fun onChanged(location: LocationData?) {
            location ?: return
            emit(
                Signal.Location(
                    t = location.timestampMillis.takeIf { it > 0 } ?: System.currentTimeMillis(),
                    lat = location.latitude,
                    lon = location.longitude,
                    altitude = location.altitude,
                    heading = location.heading,
                    confidence = location.confidence,
                )
            )
        }
        override fun onError(exception: Exception) {
            Log.w(TAG, "location error: ${exception.message}")
        }
    }

    fun start() {
        if (initialized) return
        if (!isSupported) { Log.w(TAG, "FusedLocation not supported on this build"); return }
        fused.initialize()
        initialized = true
        runCatching { fused.registerFusedLocationCallback(locationListener) }
            .onFailure { Log.e(TAG, "register location failed (permission?)", it) }
        registerGarageAlerts()
    }

    /** Re-register geofences (call after a new garage is learned). */
    fun registerGarageAlerts() {
        if (!initialized) return
        clearAlerts()
        garages.all().forEach { g ->
            val request = ProximityAlertRequest(g.lat, g.lon, g.radiusMeters, setOf(ProximityTransition.ENTER))
            runCatching {
                fused.addProximityAlert(request, object : ProximityAlertListener {
                    override fun onRegistered(alertId: String) { alertIdToGarage[alertId] = g.name }
                    override fun onTransition(event: ProximityAlertEvent) {
                        if (event.transition == ProximityTransition.ENTER) {
                            emit(Signal.GarageGeofence(System.currentTimeMillis(), alertIdToGarage[event.alertId]))
                        }
                    }
                    override fun onError(exception: Exception) {
                        Log.w(TAG, "geofence ${g.name} error: ${exception.message}")
                    }
                })
            }.onFailure { Log.w(TAG, "addProximityAlert failed", it) }
        }
    }

    private fun clearAlerts() {
        alertIdToGarage.keys.toList().forEach { id -> runCatching { fused.removeProximityAlert(id) } }
        alertIdToGarage.clear()
    }

    fun stop() {
        if (!initialized) return
        clearAlerts()
        runCatching { fused.unregisterFusedLocationCallback(locationListener) }
        runCatching { fused.release() }
        initialized = false
    }

    companion object { private const val TAG = "ParkTrace/Location" }
}
