package com.parktrace.data

import android.content.Context
import com.parktrace.core.ParkingDetector
import org.json.JSONArray
import org.json.JSONObject

/**
 * Garages the app has learned. The first visit is detected by GPS stall / altitude drop / confidence drop;
 * the entrance is then saved, and later visits are detected instantly with a FusedLocation proximity alert.
 *
 * Can be pre-seeded with the public parking-lot API (data.go.kr, 한국교통안전공단 주차정보 API).
 */
class GarageRegistry(context: Context) {

    data class Garage(val name: String, val lat: Double, val lon: Double, val radiusMeters: Float = 80f)

    private val prefs = context.getSharedPreferences("garages", Context.MODE_PRIVATE)

    fun all(): List<Garage> = runCatching {
        val arr = JSONArray(prefs.getString(KEY, "[]"))
        (0 until arr.length()).map {
            val o = arr.getJSONObject(it)
            Garage(o.getString("name"), o.getDouble("lat"), o.getDouble("lon"), o.optDouble("r", 80.0).toFloat())
        }
    }.getOrDefault(emptyList())

    /** Returns true if this is a new garage (caller should re-register geofences). */
    fun learn(name: String, lat: Double, lon: Double): Boolean {
        val list = all()
        if (list.any { ParkingDetector.haversine(it.lat, it.lon, lat, lon) < 120 }) return false
        val updated = (list + Garage(name, lat, lon)).takeLast(MAX)
        val arr = JSONArray().apply {
            updated.forEach { put(JSONObject().put("name", it.name).put("lat", it.lat).put("lon", it.lon).put("r", it.radiusMeters.toDouble())) }
        }
        prefs.edit().putString(KEY, arr.toString()).apply()
        return true
    }

    fun clear() = prefs.edit().remove(KEY).apply()

    companion object {
        private const val KEY = "list"
        private const val MAX = 20
    }
}
