package com.parktrace.data

import android.content.Context
import org.json.JSONArray
import java.io.File

/** Records live only on the vehicle (no server). Keeps the latest [MAX] parkings. */
class ParkingRepository(context: Context) {

    private val file = File(context.filesDir, "parking_records.json")

    @Synchronized
    fun all(): List<ParkingRecord> = runCatching {
        if (!file.exists()) return emptyList()
        val arr = JSONArray(file.readText())
        (0 until arr.length()).map { ParkingRecord.fromJson(arr.getJSONObject(it)) }
    }.getOrDefault(emptyList())

    fun latest(): ParkingRecord? = all().maxByOrNull { it.savedAt }

    @Synchronized
    fun save(record: ParkingRecord) {
        val list = (all().filter { it.id != record.id } + record).sortedByDescending { it.savedAt }.take(MAX)
        val arr = JSONArray().apply { list.forEach { put(it.toJson()) } }
        file.writeText(arr.toString())
    }

    @Synchronized
    fun clear() { file.delete() }

    companion object { private const val MAX = 30 }
}
