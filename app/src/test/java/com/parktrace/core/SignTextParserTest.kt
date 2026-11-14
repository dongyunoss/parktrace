package com.parktrace.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SignTextParserTest {

    private fun check(line: String, floor: Int?, spot: String?) {
        val o = SignTextParser.parseLine(line)
        if (floor == null && spot == null) { assertNull(line, o); return }
        assertEquals("floor of '$line'", floor, o?.floorLevel)
        assertEquals("spot of '$line'", spot, o?.spot)
    }

    @Test fun floorOnly() { check("B2", -2, null); check("지하 3층", -3, null) }
    @Test fun spotOnly() { check("C15", null, "C-15"); check("B12", null, "B-12") }
    @Test fun combined() {
        check("B2-C15", -2, "C-15")
        check("지하2층 A-3", -2, "A-3")
        check("B3 가12", -3, "가-12")
        check("2F D7", 2, "D-7")
        check("B1층 E-07", -1, "E-7")
        check("B2-S12", -2, "S-12")
    }
    @Test fun ocrConfusions() { check("CI5", null, "C-15"); check("C1S", null, "C-15") }
    @Test fun ignoresNoise() {
        check("EXIT", null, null)
        check("12가3456", null, null)   // licence plate
        check("출구 →", null, null)
        check("10km/h", null, null)
    }
    @Test fun multiLine() {
        val obs = SignTextParser.parse("B2\nC-15\n주차장")
        assertEquals(2, obs.size)
        assertEquals(-2, obs[0].floorLevel)
        assertEquals("C-15", obs[1].spot)
    }
}
