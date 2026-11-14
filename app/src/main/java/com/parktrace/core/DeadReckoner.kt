package com.parktrace.core

import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.tan

/**
 * Camera-free path tracking inside a garage where GPS is unreliable.
 *
 * Input: travelled distance increments (odometer trip distance, meters) and the
 * front-wheel steering angle. Heading is integrated with a kinematic bicycle model:
 *
 *     dψ = ds · tan(δ) / L
 *
 * Coordinates are local meters relative to the garage entrance.
 * Initial heading = 0 means "the direction the car was facing at the entrance" (screen up).
 * Positive heading = clockwise (right turn), matching the Pleos convention that left steering is negative.
 */
class DeadReckoner(
    private val wheelbaseMeters: Double = 2.9,
    private val minSampleSpacingMeters: Double = 2.0,
) {
    data class Point(val x: Double, val y: Double, val headingDeg: Double, val distance: Double)

    private var steeringDeg = 0.0
    private var headingRad = 0.0
    private var x = 0.0
    private var y = 0.0
    private var travelled = 0.0
    private var sinceLastSample = 0.0

    private val _points = mutableListOf(Point(0.0, 0.0, 0.0, 0.0))
    val points: List<Point> get() = _points

    /** Signed cumulative turn in degrees (e.g. two right-hand ramp loops ≈ +720). */
    val netTurnDegrees: Double get() = Math.toDegrees(headingRad)

    /** Total absolute turning in degrees (useful to tell a spiral from a straight ramp). */
    var totalTurnDegrees: Double = 0.0
        private set

    val travelledMeters: Double get() = travelled

    fun onSteering(degrees: Float) {
        steeringDeg = degrees.toDouble().coerceIn(-45.0, 45.0)
    }

    /** Advance by [ds] meters using the latest steering angle. Large steps are integrated in 0.5 m slices. */
    fun advance(ds: Double) {
        if (ds == 0.0 || ds.isNaN()) return
        // ds < 0 = reversing: same bicycle model with a negative step (heading still = where the nose points)
        val dir = if (ds < 0) -1.0 else 1.0
        var remaining = abs(ds)
        while (remaining > 0.0) {
            val step = dir * minOf(0.5, remaining)
            val dPsi = step * tan(Math.toRadians(steeringDeg)) / wheelbaseMeters
            // integrate at the mid-heading for better accuracy
            val mid = headingRad + dPsi / 2.0
            x += step * sin(mid)
            y += step * cos(mid)
            headingRad += dPsi
            totalTurnDegrees += abs(Math.toDegrees(dPsi))
            travelled += abs(step)
            sinceLastSample += abs(step)
            remaining -= abs(step)
            if (sinceLastSample >= minSampleSpacingMeters) {
                _points += Point(x, y, Math.toDegrees(headingRad), travelled)
                sinceLastSample = 0.0
            }
        }
    }

    /** Force a final sample (called when the car stops). */
    fun closePath() {
        val last = _points.last()
        if (last.x != x || last.y != y) _points += Point(x, y, Math.toDegrees(headingRad), travelled)
    }

    fun reset() {
        steeringDeg = 0.0; headingRad = 0.0; x = 0.0; y = 0.0
        travelled = 0.0; sinceLastSample = 0.0; totalTurnDegrees = 0.0
        _points.clear(); _points += Point(0.0, 0.0, 0.0, 0.0)
    }
}
