package com.parktrace.core

import kotlin.math.abs
import kotlin.math.roundToInt

enum class Confidence { HIGH, MEDIUM, LOW }

data class FloorEstimate(
    /** Negative = basement (B1 = -1), positive = above ground (1F = 1, 2F = 2). */
    val level: Int,
    val confidence: Confidence,
    /** Human readable reasoning, shown in the UI / PT demo. */
    val method: String,
) {
    val label: String get() = floorLabel(level)
}

fun floorLabel(level: Int): String = when {
    level < 0 -> "B${-level}"
    level == 0 -> "1F"
    else -> "${level}F"
}

/**
 * Cross-checks two independent cues:
 *  1. altitude change reported by FusedLocation (GPS + IMU fusion)
 *  2. number of ramp loops counted by the dead reckoner (net turn / degrees per level)
 *
 * Both are noisy, so the final answer is always confirmed by voice when possible.
 */
class FloorEstimator(
    private val floorHeightMeters: Double = 3.2,
    private val degreesPerLevel: Double = 360.0,
    private val assumeUnderground: Boolean = true,
) {
    fun estimate(altitudeDelta: Double?, netTurnDegrees: Double): FloorEstimate {
        val altLevels: Int? = altitudeDelta
            ?.takeIf { !it.isNaN() && abs(it) >= 1.5 }
            ?.let { (it / floorHeightMeters).roundToInt() }
            ?.takeIf { it != 0 }

        val loops = ((abs(netTurnDegrees) + degreesPerLevel * 0.25) / degreesPerLevel).toInt()
        val goingDown = when {
            altitudeDelta != null && !altitudeDelta.isNaN() && abs(altitudeDelta) >= 1.0 -> altitudeDelta < 0
            else -> assumeUnderground
        }
        val turnLevels: Int? = if (loops > 0) (if (goingDown) -loops else loops) else null

        return when {
            altLevels != null && turnLevels != null && altLevels == turnLevels ->
                FloorEstimate(altLevels.toBasementAware(), Confidence.HIGH, "고도 변화와 램프 회전수가 일치")
            altLevels != null && turnLevels != null && abs(altLevels - turnLevels) == 1 ->
                FloorEstimate(altLevels.toBasementAware(), Confidence.MEDIUM, "고도 변화 기준 (회전수와 1개 층 차이)")
            altLevels != null && turnLevels != null ->
                FloorEstimate(altLevels.toBasementAware(), Confidence.LOW, "고도 변화와 회전수가 크게 다름")
            altLevels != null ->
                FloorEstimate(altLevels.toBasementAware(), Confidence.MEDIUM, "고도 변화 기준")
            turnLevels != null ->
                FloorEstimate(turnLevels.toBasementAware(), Confidence.LOW, "램프 회전수 기준")
            else ->
                FloorEstimate(if (goingDown) -1 else 0, Confidence.LOW, "단서 부족, 기본값")
        }
    }

    /** Going up from ground floor by 1 level means 2F; going down 1 level means B1. */
    private fun Int.toBasementAware(): Int = if (this > 0) this + 1 else this
}
