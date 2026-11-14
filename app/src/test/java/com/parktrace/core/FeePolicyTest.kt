package com.parktrace.core

import com.parktrace.core.FeePolicy.Companion.MIN
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class FeePolicyTest {

    private val p = FeePolicy(freeMinutes = 30, unitMinutes = 10, unitPrice = 1000)
    private val t0 = 1_700_000_000_000L

    @Test fun freeTimeCostsNothing() {
        assertEquals(t0 + 30 * MIN, p.freeUntil(t0))
        assertEquals(0, p.feeAt(t0, t0))
        assertEquals(0, p.feeAt(t0, t0 + 30 * MIN))
    }

    @Test fun everyStartedUnitIsCharged() {
        assertEquals(1000, p.feeAt(t0, t0 + 30 * MIN + 1_000))
        assertEquals(1000, p.feeAt(t0, t0 + 40 * MIN))
        assertEquals(2000, p.feeAt(t0, t0 + 40 * MIN + 1_000))
        assertEquals(9000, p.feeAt(t0, t0 + 120 * MIN))
    }

    @Test fun dailyCapPerStartedDay() {
        val capped = p.copy(dailyMax = 5000)
        assertEquals(5000, capped.feeAt(t0, t0 + 120 * MIN))
        assertEquals(10000, capped.feeAt(t0, t0 + 25 * 60 * MIN))
        assertNull(capped.nextIncreaseAt(t0, t0 + 120 * MIN))
    }

    @Test fun nextIncrease() {
        assertEquals(t0 + 30 * MIN, p.nextIncreaseAt(t0, t0 + 5 * MIN))
        assertEquals(t0 + 50 * MIN, p.nextIncreaseAt(t0, t0 + 41 * MIN))
        assertNull(FeePolicy(unitPrice = 0).nextIncreaseAt(t0, t0))
    }

    @Test fun describe() {
        assertEquals("최초 30분 무료 · 이후 10분당 1,000원", p.describe())
        assertEquals("최초 2시간 무료 · 이후 30분당 1,500원 · 1일 최대 20,000원", FeePolicy(120, 30, 1500, 20000).describe())
    }
}
