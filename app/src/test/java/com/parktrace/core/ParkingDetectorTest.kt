package com.parktrace.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Replays the same scenario as the in-app demo, without Android. */
class ParkingDetectorTest {

    private class Recorder : ParkingDetector.Listener {
        val phases = mutableListOf<ParkingDetector.Phase>()
        var cue: ParkingDetector.EntryCue? = null
        var stopped: FloorEstimate? = null
        var parked: ParkingDetector.Session? = null
        override fun onPhaseChanged(phase: ParkingDetector.Phase) { phases += phase }
        override fun onGarageEntered(cue: ParkingDetector.EntryCue, entrance: ParkingDetector.Anchor?, garageName: String?) { this.cue = cue }
        override fun onStopped(estimate: FloorEstimate) { stopped = estimate }
        override fun onParked(session: ParkingDetector.Session) { parked = session }
    }

    private var clock = 0L
    private var trip = 0f
    private var lat = 37.5
    private var alt = 32.0

    private fun ParkingDetector.drive(meters: Float, steering: Float, conf: Float, dAlt: Double = 0.0) {
        clock += 1000
        onSignal(Signal.Steering(clock, steering))
        trip += meters
        onSignal(Signal.TripDistance(clock, trip))
        if (conf >= 0.6f) lat += meters / 111_000.0
        alt += dAlt
        onSignal(Signal.Location(clock, lat, 127.0, alt, 0f, conf))
    }

    private fun ParkingDetector.idle(sec: Int) = repeat(sec) { clock += 1000; onSignal(Signal.Tick(clock)) }

    @Test fun garageScenario() {
        val rec = Recorder()
        val d = ParkingDetector(listener = rec)
        repeat(4) { d.drive(5f, 0f, 0.95f) }
        repeat(3) { d.drive(5f, 0f, 0.1f, -0.3) }
        assertEquals(ParkingDetector.Phase.IN_GARAGE, d.phase)
        assertEquals(ParkingDetector.EntryCue.LOW_CONFIDENCE, rec.cue)

        repeat(13) { d.drive(4.85f, 30f, 0.15f, -0.5) }
        repeat(6) { d.drive(5f, 0f, 0.15f) }
        d.drive(4f, 35f, 0.15f); d.drive(4f, 35f, 0.15f); d.drive(3f, 0f, 0.15f)

        d.idle(1)
        // shifting to P is the parking: saved right then, before any door opens
        clock += 1000; d.onSignal(Signal.Gear(clock, true))
        assertEquals(ParkingDetector.Phase.PARKED, d.phase)
        assertNotNull(rec.parked)

        // the door opening afterwards changes nothing
        clock += 1000; d.onSignal(Signal.DriverDoor(clock, true))
        assertEquals(ParkingDetector.Phase.PARKED, d.phase)
        assertNotNull(rec.parked)
        val s = rec.parked!!
        assertTrue(s.inGarage)
        assertEquals("B2", s.estimate?.label)
        assertTrue(s.travelledInGarage > 100)
    }

    @Test fun carMovingAgainCancelsStop() {
        val rec = Recorder()
        val d = ParkingDetector(listener = rec)
        d.drive(5f, 0f, 0.9f)
        d.onSignal(Signal.ManualGarageEntry(clock))
        d.drive(5f, 0f, 0.1f)
        d.idle(9)
        assertEquals(ParkingDetector.Phase.STOPPED, d.phase)
        d.drive(6f, 0f, 0.1f)
        assertEquals(ParkingDetector.Phase.IN_GARAGE, d.phase)
    }

    @Test fun outdoorParkingStillSaved() {
        val rec = Recorder()
        val d = ParkingDetector(listener = rec)
        repeat(3) { d.drive(10f, 0f, 0.95f) }
        d.idle(3)
        clock += 1000; d.onSignal(Signal.DoorLock(clock, true))
        assertEquals(false, rec.parked?.inGarage)
    }
}
