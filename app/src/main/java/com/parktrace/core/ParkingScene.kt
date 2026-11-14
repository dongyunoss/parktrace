package com.parktrace.core

/**
 * What is around the car, in the dead-reckoning frame (meters, x = right, y = forward from the entrance).
 *
 * The car pose and path are real (Vehicle SDK steering + odometer). Bays, neighbouring cars and pillars
 * come from a [com.parktrace.sdk.SurroundVision]; in this build that is a simulation (camera = 향후 계획).
 */
data class ParkingScene(
    val car: Pose,
    /** Last part of the driven path, oldest first. */
    val path: List<Pair<Double, Double>>,
    val bays: List<Bay>,
    val pillars: List<Pillar>,
    /** false while still driving: only the path and the car are known. */
    val surroundKnown: Boolean,
    /** Garage structure seen while driving (simulated): ramp side walls as polylines, oldest point first. */
    val walls: List<List<Pair<Double, Double>>> = emptyList(),
    /** Driving aisle centre line (start, end) once the car is on a parking level. */
    val aisle: Pair<Pair<Double, Double>, Pair<Double, Double>>? = null,
    /** Entrance gate (barrier + booth) at the start of the path, facing into the garage. */
    val gate: Pose? = null,
    /** Outer walls of the parking level (polylines), with openings where the car drove in. */
    val structure: List<List<Pair<Double, Double>>> = emptyList(),
    /** Other driving lanes of the level (centre lines): side aisles and the cross aisle. */
    val lanes: List<Pair<Pair<Double, Double>, Pair<Double, Double>>> = emptyList(),
    /** Floor slab of the parking level. */
    val floor: Floor? = null,
) {
    /** Rectangle: corner (x, y), unit vector (ux, uy) along its length, width to the right of it. */
    data class Floor(val x: Double, val y: Double, val ux: Double, val uy: Double, val length: Double, val width: Double)

    /** Position plus unit heading vector (hx, hy). */
    data class Pose(val x: Double, val y: Double, val hx: Double, val hy: Double)

    data class Bay(
        val x: Double, val y: Double, val hx: Double, val hy: Double,
        val occupied: Boolean, val mine: Boolean,
        /** Index into the neighbour car palette. */
        val shade: Int,
        /** Detection score shown next to the box (simulated). */
        val confidence: Float,
    )

    data class Pillar(val x: Double, val y: Double, val label: String?, val mine: Boolean)

    val detectedCars: Int get() = bays.count { it.occupied && !it.mine }
}
