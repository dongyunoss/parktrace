package com.parktrace.core

import org.junit.Assert.assertEquals
import org.junit.Test

class FloorEstimatorTest {
    private val e = FloorEstimator()

    @Test fun altitudeAndTurnsAgree() {
        val r = e.estimate(-7.4, 828.0)
        assertEquals("B2", r.label)
        assertEquals(Confidence.HIGH, r.confidence)
    }

    @Test fun altitudeOnly() {
        val r = e.estimate(-3.3, 10.0)
        assertEquals("B1", r.label)
        assertEquals(Confidence.MEDIUM, r.confidence)
    }

    @Test fun turnsOnlyAssumeUnderground() {
        val r = e.estimate(null, 1080.0)
        assertEquals("B3", r.label)
        assertEquals(Confidence.LOW, r.confidence)
    }

    @Test fun goingUp() {
        val r = e.estimate(6.5, 720.0)
        assertEquals("3F", r.label)
    }

    @Test fun noCluesDefaultsToB1() {
        assertEquals("B1", e.estimate(null, 0.0).label)
    }
}
