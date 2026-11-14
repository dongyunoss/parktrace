package com.parktrace.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ReverseParkingTest {

    @Test fun reverseStraightGoesBack() {
        val r = DeadReckoner()
        r.advance(10.0)
        r.advance(-4.0)
        r.closePath()
        val last = r.points.last()
        assertEquals(6.0, last.y, 0.01)
        assertEquals(14.0, r.travelledMeters, 0.01) // distance driven, not displacement
    }

    @Test fun reversingWithRightLockSwingsTheRearRight() {
        val r = DeadReckoner()
        r.onSteering(35f)
        r.advance(-6.3) // ~90 degrees with a 2.8 m wheelbase
        r.closePath()
        val last = r.points.last()
        assertTrue("rear went right", last.x > 1.0)
        assertTrue("and backwards", last.y < -1.0)
        assertEquals(-90.0, r.netTurnDegrees, 12.0) // nose turned left = faces the aisle
    }

    @Test fun gearReverseMarksTheParkingAsBackedIn() {
        val d = ParkingDetector(listener = object : ParkingDetector.Listener {})
        var t = 1_000_000L
        var trip = 0f
        fun move(m: Float) { t += 1000; trip += m; d.onSignal(Signal.TripDistance(t, trip)) }
        d.onSignal(Signal.TripDistance(t, trip))
        d.onSignal(Signal.Gear(t, park = false))
        d.onSignal(Signal.ManualGarageEntry(t))
        repeat(4) { move(5f) }
        assertFalse(d.lastMoveReversed)
        d.onSignal(Signal.Gear(t, park = false, reverse = true))
        repeat(3) { move(2f) }
        assertTrue(d.lastMoveReversed)
        // P: parked at once
        t += 1000; d.onSignal(Signal.Gear(t, park = true))
        assertEquals(ParkingDetector.Phase.PARKED, d.phase)
        // path went forward 20 m then back 6 m
        assertEquals(14.0, d.reckoner.points.last().y, 0.6)
    }
}
