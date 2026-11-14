package com.parktrace.core

import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Turns the dead-reckoned path into short, glanceable steps, e.g.
 *   ["입구에서 직진 20m", "나선 램프 2바퀴 (내려감)", "직진 30m", "우회전 후 9m", "주차"]
 */
object Directions {

    private enum class Kind { STRAIGHT, LEFT, RIGHT }
    private data class Run(var kind: Kind, var length: Double, var turn: Double)

    /** Curvature above this (deg per meter) counts as turning. */
    private const val TURN_RATE = 6.0

    /** Rebuild heading/distance from a stored x,y path (records keep only coordinates). */
    fun fromXY(path: List<Pair<Double, Double>>, floorLevel: Int? = null): List<String> {
        if (path.size < 2) return from(emptyList(), floorLevel)
        val pts = mutableListOf(DeadReckoner.Point(path[0].first, path[0].second, 0.0, 0.0))
        var dist = 0.0
        var heading = 0.0
        var prevRaw: Double? = null
        for (i in 1 until path.size) {
            val dx = path[i].first - path[i - 1].first
            val dy = path[i].second - path[i - 1].second
            val ds = kotlin.math.hypot(dx, dy)
            if (ds < 0.01) continue
            val raw = Math.toDegrees(kotlin.math.atan2(dx, dy))
            heading = if (prevRaw == null) raw else heading + (((raw - prevRaw) + 540.0) % 360.0 - 180.0)
            prevRaw = raw
            dist += ds
            pts += DeadReckoner.Point(path[i].first, path[i].second, heading, dist)
        }
        // first segment heading defines the reference
        if (pts.size > 1) pts[0] = pts[0].copy(headingDeg = pts[1].headingDeg)
        return from(pts, floorLevel)
    }

    fun from(points: List<DeadReckoner.Point>, floorLevel: Int? = null): List<String> {
        if (points.size < 2) return listOf("입구 바로 근처", "주차")

        val runs = mutableListOf<Run>()
        for (i in 1 until points.size) {
            val ds = points[i].distance - points[i - 1].distance
            if (ds <= 0) continue
            val dh = points[i].headingDeg - points[i - 1].headingDeg
            val kind = when {
                abs(dh / ds) <= TURN_RATE -> Kind.STRAIGHT
                dh > 0 -> Kind.RIGHT
                else -> Kind.LEFT
            }
            val last = runs.lastOrNull()
            if (last != null && last.kind == kind) { last.length += ds; last.turn += dh } else runs += Run(kind, ds, dh)
        }

        // tiny wiggles (< 45°) are treated as straight driving
        val merged = mutableListOf<Run>()
        for (r in runs) {
            val kind = if (r.kind != Kind.STRAIGHT && abs(r.turn) < 45) Kind.STRAIGHT else r.kind
            val last = merged.lastOrNull()
            if (last != null && last.kind == kind) { last.length += r.length; last.turn += r.turn }
            else merged += Run(kind, r.length, r.turn)
        }

        val steps = mutableListOf<String>()
        var pending: String? = null
        var first = true
        for (r in merged) {
            when {
                r.kind == Kind.STRAIGHT -> {
                    val len = r.length.roundToInt()
                    if (len >= 3) {
                        steps += when {
                            first -> "입구에서 직진 ${len}m"
                            pending != null -> "$pending 후 ${len}m"
                            else -> "직진 ${len}m"
                        }
                    } else if (pending != null) steps += pending!!
                    pending = null
                }
                abs(r.turn) >= 300 -> {
                    pending?.let { steps += it }
                    pending = null
                    val loops = (abs(r.turn) / 360.0).roundToInt().coerceAtLeast(1)
                    val dir = floorLevel?.let { if (it < 0) " (내려감)" else " (올라감)" } ?: ""
                    steps += "나선 램프 ${loops}바퀴$dir"
                }
                else -> {
                    pending?.let { steps += it }
                    pending = if (r.turn > 0) "우회전" else "좌회전"
                }
            }
            first = false
        }
        pending?.let { steps += it }
        steps += "주차"
        return steps
    }
}
