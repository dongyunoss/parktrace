package com.parktrace.ui

import android.content.Context
import android.util.Log
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Low-poly car models for the 3D parking view: Kenney Car Kit 3.1 (www.kenney.nl, CC0), converted by
 * tools/convert_car_models.py into assets/cars/[name].bin (format documented there).
 */
class CarModel(
    val name: String,
    val triangles: Int,
    /** 9 floats per triangle: f, r, z of 3 vertices (meters, front = +f, right = +r, up = +z). */
    val v: FloatArray,
    val color: IntArray,
    /** Body paint: [color] holds only a grey brightness (128 = average) to be tinted per car. */
    val paint: BooleanArray,
)

object CarModels {
    /** Neighbours pick from all; our car is drawn with [MINE]. */
    val NAMES = listOf("sedan", "suv", "hatchback-sports", "van", "suv-luxury", "sedan-sports")
    const val MINE = "suv"

    @Volatile private var cache: Map<String, CarModel>? = null

    fun get(context: Context): Map<String, CarModel> = cache ?: synchronized(this) {
        cache ?: load(context).also { cache = it }
    }

    private fun load(context: Context): Map<String, CarModel> = NAMES.mapNotNull { name ->
        runCatching {
            val bytes = context.assets.open("cars/$name.bin").use { it.readBytes() }
            val b = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
            val magic = ByteArray(4).also { b.get(it) }
            require(String(magic) == "PTCM") { "bad model file" }
            val n = b.int
            val v = FloatArray(n * 9); val c = IntArray(n); val p = BooleanArray(n)
            for (i in 0 until n) {
                for (k in 0 until 9) v[i * 9 + k] = b.float
                c[i] = b.int
                p[i] = b.get().toInt() and 1 == 1
            }
            name to CarModel(name, n, v, c, p)
        }.onFailure { Log.w("ParkTrace/UI", "car model $name not loaded", it) }.getOrNull()
    }.toMap()
}
