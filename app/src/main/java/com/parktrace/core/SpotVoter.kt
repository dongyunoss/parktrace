package com.parktrace.core

import kotlin.math.exp

/**
 * Aggregates noisy OCR observations collected while driving through the garage
 * and decides which floor / pillar the car finally parked at.
 *
 *  - Floor: weighted vote over every floor code seen since the garage entry,
 *           later sightings weigh more (you pass B1 signs on the way to B2).
 *  - Pillar: only sightings from the last [spotWindowMeters] of travel before the stop count,
 *            weighted by how close (in meters) they were to the final position.
 */
class SpotVoter(
    private val spotWindowMeters: Double = 25.0,
    private val floorHalfLifeMeters: Double = 60.0,
) {
    data class Sighting(val tripMeters: Double, val t: Long, val obs: SignTextParser.Observation)

    data class Decision(
        val floorLevel: Int?,
        val floorVotes: Int,
        val spot: String?,
        val spotVotes: Int,
        val spotShare: Double,
    ) {
        val floorLabel: String? get() = floorLevel?.let { floorLabel(it) }
        val hasSpot: Boolean get() = spot != null
    }

    private val sightings = mutableListOf<Sighting>()

    val count: Int get() = sightings.size

    fun add(tripMeters: Double, t: Long, observations: List<SignTextParser.Observation>) {
        observations.forEach { sightings += Sighting(tripMeters, t, it) }
        // keep memory bounded
        if (sightings.size > 600) sightings.subList(0, sightings.size - 600).clear()
    }

    fun clear() = sightings.clear()

    fun decide(finalTripMeters: Double): Decision {
        val floorScores = HashMap<Int, Double>()
        val floorCounts = HashMap<Int, Int>()
        val spotScores = HashMap<String, Double>()
        val spotCounts = HashMap<String, Int>()

        for (s in sightings) {
            val back = (finalTripMeters - s.tripMeters).coerceAtLeast(0.0)
            s.obs.floorLevel?.let { f ->
                val w = exp(-back * Math.log(2.0) / floorHalfLifeMeters)
                floorScores[f] = (floorScores[f] ?: 0.0) + w
                floorCounts[f] = (floorCounts[f] ?: 0) + 1
            }
            s.obs.spot?.let { sp ->
                if (back <= spotWindowMeters) {
                    val w = 1.0 - back / (spotWindowMeters * 1.25)
                    spotScores[sp] = (spotScores[sp] ?: 0.0) + w
                    spotCounts[sp] = (spotCounts[sp] ?: 0) + 1
                }
            }
        }
        val bestFloor = floorScores.maxByOrNull { it.value }?.key
        val bestSpot = spotScores.maxByOrNull { it.value }
        val totalSpot = spotScores.values.sum()
        return Decision(
            floorLevel = bestFloor,
            floorVotes = bestFloor?.let { floorCounts[it] } ?: 0,
            spot = bestSpot?.key,
            spotVotes = bestSpot?.key?.let { spotCounts[it] } ?: 0,
            spotShare = if (totalSpot > 0 && bestSpot != null) bestSpot.value / totalSpot else 0.0,
        )
    }
}
