package com.parktrace.core

import org.junit.Assert.assertEquals
import org.junit.Test

class SpotVoterTest {

    private fun obs(text: String) = SignTextParser.parse(text)

    @Test fun picksPillarSeenLastAndFloorSeenMost() {
        val v = SpotVoter()
        v.add(10.0, 0, obs("B1"))          // passed B1 on the ramp
        v.add(60.0, 0, obs("B2"))
        v.add(80.0, 0, obs("B2\nC-12"))     // too far back -> ignored for the pillar
        v.add(95.0, 0, obs("C-13"))
        v.add(105.0, 0, obs("C-14"))
        v.add(110.0, 0, obs("C-15"))
        v.add(114.0, 0, obs("B2\nCI5"))
        val d = v.decide(116.0)
        assertEquals("B2", d.floorLabel)
        assertEquals("C-15", d.spot)
        assertEquals(2, d.spotVotes)
    }

    @Test fun noPillarWhenNothingNearby() {
        val v = SpotVoter()
        v.add(0.0, 0, obs("B3\nA-1"))
        val d = v.decide(100.0)
        assertEquals("B3", d.floorLabel)
        assertEquals(null, d.spot)
    }
}
