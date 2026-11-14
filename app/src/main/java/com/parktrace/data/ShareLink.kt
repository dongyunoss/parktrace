package com.parktrace.data

import org.json.JSONArray
import org.json.JSONObject
import java.util.Base64
import kotlin.math.roundToInt

/**
 * Car -> phone handoff without a server: the whole record is packed into the URL fragment
 * (#d=...), which never leaves the phone's browser. The static page in /web renders it.
 */
object ShareLink {

    fun build(baseUrl: String, r: ParkingRecord): String {
        val o = JSONObject().apply {
            put("v", 1)
            put("t", r.savedAt / 1000)
            r.placeName?.let { put("n", it) }
            r.floorLabel?.let { put("f", it) }
            r.spot?.let { put("s", it) }
            r.floorSource?.let { put("src", it) }
            if (r.entranceLat != null && r.entranceLon != null) {
                put("la", (r.entranceLat * 1e6).roundToInt() / 1e6)
                put("lo", (r.entranceLon * 1e6).roundToInt() / 1e6)
            }
            put("g", if (r.inGarage) 1 else 0)
            // Fee clock for the phone page: entry time (s) + [free min, unit min, unit price, daily max]
            r.enteredAt?.let { put("e", it / 1000) }
            r.fee?.let { put("fp", JSONArray().put(it.freeMinutes).put(it.unitMinutes).put(it.unitPrice).put(it.dailyMax)) }
            put("p", JSONArray().apply {
                downsample(r.path, 30).forEach { (x, y) -> put(JSONArray().put(x.roundToInt()).put(y.roundToInt())) }
            })
        }
        val b64 = Base64.getUrlEncoder().withoutPadding().encodeToString(o.toString().toByteArray(Charsets.UTF_8))
        return baseUrl.trimEnd('#') + "#d=" + b64
    }

    fun <T> downsample(list: List<T>, max: Int): List<T> {
        if (list.size <= max) return list
        val step = (list.size - 1).toDouble() / (max - 1)
        return (0 until max).map { list[(it * step).roundToInt().coerceAtMost(list.size - 1)] }
    }
}
