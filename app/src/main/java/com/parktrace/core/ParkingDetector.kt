package com.parktrace.core

import kotlin.math.abs
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Camera-free parking detector.
 *
 *  DRIVING ──(garage entry cue)──► IN_GARAGE ──(stopped)──► STOPPED ──(driver leaves)──► PARKED
 *     ▲                               │  ▲                      │
 *     └──(good GPS again, moved away)─┘  └──(car moves again)───┘
 *
 * Garage entry cues (any one):
 *  - geofence ENTER of a known / learned garage
 *  - GPS "stall": odometer says we moved ≥ [Config.stallMeters] but the fused position moved < 35 % of that
 *  - altitude drop ≥ [Config.altitudeDropMeters] within the last 200 m at garage speed
 *  - FusedLocation confidence below [Config.lowConfidence] for [Config.lowConfidenceCount] fixes in a row
 *  - manual button
 *
 * "Driver leaves" cues: driver door opened, doors locked, or driver seat empty.
 * Uses only Pleos APIs with normal protection level (no telltales / parking brake).
 */
class ParkingDetector(
    private val config: Config = Config(),
    private val listener: Listener,
    private val floorEstimator: FloorEstimator = FloorEstimator(),
) {
    data class Config(
        val stallMeters: Double = 40.0,
        val altitudeDropMeters: Double = 2.5,
        /** Calibrate on the real car: unit of LocationData.confidence is not documented. */
        val lowConfidence: Float = 0.3f,
        val lowConfidenceCount: Int = 3,
        val goodConfidence: Float = 0.6f,
        val stopSecondsWithGear: Int = 3,
        val stopSecondsWithoutGear: Int = 8,
        val restartMeters: Double = 4.0,
        val exitGarageMeters: Double = 60.0,
    )

    enum class Phase { DRIVING, IN_GARAGE, STOPPED, PARKED }

    enum class EntryCue(val korean: String) {
        GEOFENCE("등록된 주차장 진입"),
        GPS_STALL("GPS 위치 정체"),
        ALTITUDE_DROP("고도 급강하"),
        LOW_CONFIDENCE("위치 신뢰도 하락"),
        MANUAL("수동 입력"),
    }

    data class Anchor(val lat: Double, val lon: Double, val altitude: Double?, val heading: Float, val t: Long)

    data class Session(
        val inGarage: Boolean,
        val entryCue: EntryCue?,
        val garageName: String?,
        val entrance: Anchor?,
        val estimate: FloorEstimate?,
        val path: List<DeadReckoner.Point>,
        val travelledInGarage: Double,
        val parkedAt: Long,
        val lastFix: Anchor?,
        /** Backed into the spot (gear R on the last movement): the car faces the aisle. */
        val reversedIn: Boolean = false,
    )

    interface Listener {
        fun onPhaseChanged(phase: Phase) {}
        fun onGarageEntered(cue: EntryCue, entrance: Anchor?, garageName: String?) {}
        /** Car stopped inside the garage: good moment to ask the driver by voice. */
        fun onStopped(estimate: FloorEstimate) {}
        fun onRestarted() {}
        fun onParked(session: Session) {}
        fun onLeftGarage() {}
        /** Live update for the UI (path + estimate). */
        fun onTrace(points: List<DeadReckoner.Point>, estimate: FloorEstimate) {}
    }

    var phase: Phase = Phase.DRIVING
        private set

    val reckoner = DeadReckoner()

    // latest raw state
    private var lastTripMeters: Double? = null
    private var lastMoveAt: Long = 0
    private var gearPark: Boolean? = null
    private var reversing = false
    /** Direction of the last real movement: true when the car backed into its spot. */
    var lastMoveReversed = false
        private set
    private var brakePressed: Boolean? = null

    // location history
    private var lastFix: Signal.Location? = null
    private var lastGoodFix: Signal.Location? = null
    private var lowConfidenceRun = 0
    private val altWindow = ArrayDeque<Pair<Double, Double>>() // (tripMeters, altitude)
    private var stallStartTrip: Double? = null
    private var stallStartFix: Signal.Location? = null

    // garage session
    private var entryCue: EntryCue? = null
    private var garageName: String? = null
    private var entrance: Anchor? = null
    private var stoppedAt: Long = 0
    private var parkedTripMeters: Double = 0.0
    private var metersSinceGoodFixInGarage = 0.0

    fun onSignal(s: Signal) {
        when (s) {
            is Signal.Location -> onLocation(s)
            is Signal.TripDistance -> onTrip(s)
            is Signal.Steering -> reckoner.onSteering(s.degrees)
            is Signal.Brake -> brakePressed = s.pressed
            is Signal.Gear -> {
                // the point where the car changes direction is part of the path (it is where a back-in starts)
                if (s.reverse != reversing && (phase == Phase.IN_GARAGE || phase == Phase.STOPPED)) reckoner.closePath()
                val wasPark = gearPark == true
                gearPark = s.park; reversing = s.reverse
                // Shifting to P inside a garage is the parking itself: save and announce now, not when the door opens.
                if (s.park && !wasPark && (phase == Phase.IN_GARAGE || phase == Phase.STOPPED)) {
                    reckoner.closePath(); finish(s.t, inGarage = true)
                } else checkStop(s.t)
            }
            is Signal.DriverDoor -> if (s.open) driverLeaving(s.t)
            is Signal.DoorLock -> if (s.locked) driverLeaving(s.t)
            is Signal.DriverSeat -> if (!s.occupied) driverLeaving(s.t)
            is Signal.GarageGeofence -> enterGarage(EntryCue.GEOFENCE, s.t, s.garageName)
            is Signal.ManualGarageEntry -> {
                // The driver says "주차장 진입": after a previous parking we must be driving again,
                // even if no odometer change arrived (e.g. the emulator has no trip distance). Only for the
                // explicit cue; automatic cues keep requiring DRIVING so GPS jitter cannot reset a parking.
                if (phase == Phase.PARKED) resetToDriving()
                enterGarage(EntryCue.MANUAL, s.t, null)
            }
            is Signal.Tick -> checkStop(s.t)
        }
    }

    // ---------------------------------------------------------------- location

    private fun onLocation(s: Signal.Location) {
        lastFix = s
        val good = s.confidence >= config.goodConfidence
        if (good) lastGoodFix = s
        lowConfidenceRun = if (s.confidence < config.lowConfidence) lowConfidenceRun + 1 else 0

        lastTripMeters?.let { trip ->
            altWindow.addLast(trip to s.altitude)
            while (altWindow.size > 2 && trip - altWindow.first().first > 200.0) altWindow.removeFirst()
        }

        when (phase) {
            Phase.DRIVING -> {
                if (lowConfidenceRun >= config.lowConfidenceCount) enterGarage(EntryCue.LOW_CONFIDENCE, s.t, null)
                else if (altitudeDropped()) enterGarage(EntryCue.ALTITUDE_DROP, s.t, null)
            }
            Phase.IN_GARAGE -> {
                if (good) {
                    // Good GPS again while still driving -> we probably left (or never entered) the garage.
                    if (metersSinceGoodFixInGarage > config.exitGarageMeters && entryCue != EntryCue.MANUAL) leaveGarage()
                    metersSinceGoodFixInGarage = 0.0
                }
            }
            else -> Unit
        }
    }

    private fun altitudeDropped(): Boolean {
        if (altWindow.size < 2) return false
        val maxAlt = altWindow.maxOf { it.second }
        val current = altWindow.last().second
        return maxAlt - current >= config.altitudeDropMeters
    }

    // ---------------------------------------------------------------- odometer

    private fun onTrip(s: Signal.TripDistance) {
        val meters = s.meters.toDouble()
        val prev = lastTripMeters
        lastTripMeters = meters
        if (prev == null) { lastMoveAt = s.t; return }
        val ds = meters - prev
        if (ds < 0) return // trip reset
        if (ds > 0.3) { lastMoveAt = s.t; lastMoveReversed = reversing }
        val signed = if (reversing) -ds else ds

        when (phase) {
            Phase.DRIVING -> {
                detectStall(meters)
            }
            Phase.IN_GARAGE -> {
                reckoner.advance(signed)
                metersSinceGoodFixInGarage += ds
                listener.onTrace(reckoner.points, currentEstimate())
            }
            Phase.STOPPED -> {
                reckoner.advance(signed)
                if (meters - parkedTripMeters > config.restartMeters) {
                    setPhase(Phase.IN_GARAGE)
                    listener.onRestarted()
                }
            }
            Phase.PARKED -> {
                if (meters - parkedTripMeters > config.restartMeters * 5) resetToDriving()
            }
        }
    }

    private fun detectStall(trip: Double) {
        val fix = lastFix ?: return
        val startTrip = stallStartTrip
        val startFix = stallStartFix
        if (startTrip == null || startFix == null) {
            stallStartTrip = trip; stallStartFix = fix; return
        }
        val odoMoved = trip - startTrip
        if (odoMoved < config.stallMeters) return
        val gpsMoved = haversine(startFix.lat, startFix.lon, fix.lat, fix.lon)
        if (gpsMoved < odoMoved * 0.35) {
            enterGarage(EntryCue.GPS_STALL, fix.t, null)
        } else {
            stallStartTrip = trip; stallStartFix = fix
        }
    }

    // ---------------------------------------------------------------- transitions

    private fun enterGarage(cue: EntryCue, t: Long, name: String?) {
        if (phase != Phase.DRIVING) {
            if (name != null) garageName = name
            return
        }
        reckoner.reset()
        entryCue = cue
        garageName = name
        metersSinceGoodFixInGarage = 0.0
        // Entrance = last trustworthy fix (before the signal degraded).
        val ref = lastGoodFix ?: lastFix
        entrance = ref?.let { Anchor(it.lat, it.lon, it.altitude.takeIf { a -> !a.isNaN() }, it.heading, it.t) }
        setPhase(Phase.IN_GARAGE)
        lastMoveAt = t
        listener.onGarageEntered(cue, entrance, name)
    }

    private fun checkStop(now: Long) {
        if (phase != Phase.IN_GARAGE) return
        val need = if (gearPark != null) config.stopSecondsWithGear else config.stopSecondsWithoutGear
        val stoppedFor = (now - lastMoveAt) / 1000
        val parkedGearOk = gearPark != false // true or unknown
        if (stoppedFor >= need && parkedGearOk) {
            reckoner.closePath()
            stoppedAt = now
            parkedTripMeters = lastTripMeters ?: 0.0
            setPhase(Phase.STOPPED)
            listener.onStopped(currentEstimate())
        }
    }

    private fun driverLeaving(t: Long) {
        when (phase) {
            Phase.STOPPED -> finish(t, inGarage = true)
            Phase.IN_GARAGE -> {
                // door opened right after stopping (before the stop timer fired)
                if ((t - lastMoveAt) >= 1500) { reckoner.closePath(); finish(t, inGarage = true) }
            }
            Phase.DRIVING -> {
                // Outdoor parking: still save the GPS position so the app is useful everywhere.
                if ((t - lastMoveAt) >= 1500 && lastFix != null) finish(t, inGarage = false)
            }
            Phase.PARKED -> Unit
        }
    }

    private fun finish(t: Long, inGarage: Boolean) {
        parkedTripMeters = lastTripMeters ?: 0.0
        val fix = (lastGoodFix ?: lastFix)?.let { Anchor(it.lat, it.lon, it.altitude, it.heading, it.t) }
        val session = Session(
            inGarage = inGarage,
            entryCue = if (inGarage) entryCue else null,
            garageName = garageName,
            entrance = if (inGarage) entrance else fix,
            estimate = if (inGarage) currentEstimate() else null,
            path = if (inGarage) reckoner.points.toList() else emptyList(),
            travelledInGarage = if (inGarage) reckoner.travelledMeters else 0.0,
            parkedAt = t,
            lastFix = fix,
            reversedIn = inGarage && lastMoveReversed,
        )
        setPhase(Phase.PARKED)
        listener.onParked(session)
    }

    private fun leaveGarage() {
        resetToDriving()
        listener.onLeftGarage()
    }

    private fun resetToDriving() {
        entryCue = null; garageName = null; entrance = null
        stallStartTrip = null; stallStartFix = null
        lowConfidenceRun = 0; altWindow.clear()
        reckoner.reset()
        setPhase(Phase.DRIVING)
    }

    fun currentEstimate(): FloorEstimate {
        val entAlt = entrance?.altitude
        val curAlt = lastFix?.altitude
        val delta = if (entAlt != null && curAlt != null && !curAlt.isNaN()) curAlt - entAlt else null
        return floorEstimator.estimate(delta, reckoner.netTurnDegrees)
    }

    private fun setPhase(p: Phase) {
        if (phase == p) return
        phase = p
        listener.onPhaseChanged(p)
    }

    companion object {
        fun haversine(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
            val r = 6_371_000.0
            val dLat = Math.toRadians(lat2 - lat1)
            val dLon = Math.toRadians(lon2 - lon1)
            val a = sin(dLat / 2).pow(2) + cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) * sin(dLon / 2).pow(2)
            return 2 * r * asin(sqrt(a))
        }

        @Suppress("unused")
        private fun Double.near(other: Double, eps: Double) = abs(this - other) <= eps
    }
}
