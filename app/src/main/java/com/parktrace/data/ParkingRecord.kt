package com.parktrace.data

import com.parktrace.core.FeePolicy
import org.json.JSONArray
import org.json.JSONObject

data class ParkingRecord(
    val id: Long,
    val savedAt: Long,
    val inGarage: Boolean,
    val placeName: String?,
    val entranceLat: Double?,
    val entranceLon: Double?,
    /** "B2", "3F", "RF" ... null for outdoor parking. */
    val floorLabel: String?,
    /** "표지판 인식" or "고도·회전 추정". */
    val floorSource: String?,
    /** Estimate from altitude + ramp turns, kept for cross-checking. */
    val estimatedFloor: String?,
    val estimateConfidence: String?,
    val floorCrossCheckOk: Boolean?,
    /** Pillar / zone read by OCR, e.g. "C-15". */
    val spot: String?,
    val spotVotes: Int,
    val entryCue: String?,
    /** Local path from entrance to car, meters (x = right, y = forward). */
    val path: List<Pair<Double, Double>>,
    val travelledMeters: Double,
    /** When the car entered the garage (fee clock starts here). */
    val enteredAt: Long? = null,
    /** Fee table of this garage at the time of parking. */
    val fee: FeePolicy? = null,
    /** Backed into the spot: the car faces the aisle. */
    val reversedIn: Boolean = false,
) {
    fun headline(): String = when {
        !inGarage -> "지상 주차"
        floorLabel != null && spot != null -> "${spokenFloor(floorLabel)} $spot 구역"
        floorLabel != null -> spokenFloor(floorLabel)
        else -> "주차장"
    }

    /** Text for TTS. */
    fun spoken(): String {
        if (!inGarage) return "주차 위치를 저장했어요."
        val floor = floorLabel?.let { spokenFloor(it) }
        return when {
            floor != null && spot != null -> "$floor ${spot.replace("-", " ")} 구역에 주차했어요."
            floor != null -> "$floor 에 주차했어요. 구역 번호는 읽지 못했어요."
            else -> "주차 위치를 저장했어요."
        }
    }

    fun toJson(): JSONObject = JSONObject().apply {
        put("id", id); put("savedAt", savedAt); put("inGarage", inGarage)
        put("placeName", placeName); put("entranceLat", entranceLat); put("entranceLon", entranceLon)
        put("floorLabel", floorLabel); put("floorSource", floorSource)
        put("estimatedFloor", estimatedFloor); put("estimateConfidence", estimateConfidence)
        put("floorCrossCheckOk", floorCrossCheckOk)
        put("spot", spot); put("spotVotes", spotVotes); put("entryCue", entryCue)
        put("travelledMeters", travelledMeters)
        put("path", JSONArray().apply { path.forEach { (x, y) -> put(JSONArray().put(x).put(y)) } })
        put("enteredAt", enteredAt)
        fee?.let { put("fee", FeeBook.toJson(it)) }
        put("reversedIn", reversedIn)
    }

    companion object {
        fun spokenFloor(label: String): String = when {
            label == "RF" -> "옥상"
            label.startsWith("B") -> "지하 ${label.drop(1)}층"
            label.endsWith("F") -> "${label.dropLast(1)}층"
            else -> label
        }

        fun fromJson(o: JSONObject): ParkingRecord {
            fun str(k: String) = if (o.isNull(k)) null else o.optString(k)
            fun dbl(k: String) = if (o.isNull(k)) null else o.optDouble(k)
            val arr = o.optJSONArray("path") ?: JSONArray()
            val path = (0 until arr.length()).map { i -> arr.getJSONArray(i).let { it.getDouble(0) to it.getDouble(1) } }
            return ParkingRecord(
                id = o.optLong("id"), savedAt = o.optLong("savedAt"), inGarage = o.optBoolean("inGarage"),
                placeName = str("placeName"), entranceLat = dbl("entranceLat"), entranceLon = dbl("entranceLon"),
                floorLabel = str("floorLabel"), floorSource = str("floorSource"),
                estimatedFloor = str("estimatedFloor"), estimateConfidence = str("estimateConfidence"),
                floorCrossCheckOk = if (o.isNull("floorCrossCheckOk")) null else o.optBoolean("floorCrossCheckOk"),
                spot = str("spot"), spotVotes = o.optInt("spotVotes"), entryCue = str("entryCue"),
                path = path, travelledMeters = o.optDouble("travelledMeters", 0.0),
                enteredAt = if (o.isNull("enteredAt")) null else o.optLong("enteredAt"),
                fee = o.optJSONObject("fee")?.let { runCatching { FeeBook.fromJson(it) }.getOrNull() },
                reversedIn = o.optBoolean("reversedIn", false),
            )
        }
    }
}
