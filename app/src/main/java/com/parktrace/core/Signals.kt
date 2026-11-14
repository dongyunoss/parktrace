package com.parktrace.core

/**
 * Normalised vehicle/location signals. The detector only depends on these,
 * so it can be unit-tested on the JVM and replayed by the demo scenario.
 *
 * Every signal carries [t] = epoch millis.
 */
sealed interface Signal {
    val t: Long

    /** FusedLocation update. heading in degrees (0 = north, clockwise). */
    data class Location(
        override val t: Long,
        val lat: Double,
        val lon: Double,
        val altitude: Double,
        val heading: Float,
        val confidence: Float,
    ) : Signal

    /** Odometer trip distance in meters (monotonic within a trip). */
    data class TripDistance(override val t: Long, val meters: Float) : Signal

    /** Front-wheel steering angle in degrees. Left is negative (Pleos convention). */
    data class Steering(override val t: Long, val degrees: Float) : Signal

    data class Brake(override val t: Long, val pressed: Boolean) : Signal

    /** Gear selection (Vehicle SDK getGear()). [reverse] makes the dead reckoning move backwards. */
    data class Gear(override val t: Long, val park: Boolean, val reverse: Boolean = false) : Signal

    /** Driver door (ROW_1_LEFT) open/closed. */
    data class DriverDoor(override val t: Long, val open: Boolean) : Signal

    data class DoorLock(override val t: Long, val locked: Boolean) : Signal

    data class DriverSeat(override val t: Long, val occupied: Boolean) : Signal

    /** Geofence ENTER for a known/learned garage. */
    data class GarageGeofence(override val t: Long, val garageName: String?) : Signal

    /** User pressed "주차장 진입" manually. */
    data class ManualGarageEntry(override val t: Long) : Signal

    /** Periodic clock tick (1 Hz) so time-based rules fire without new data. */
    data class Tick(override val t: Long) : Signal
}
