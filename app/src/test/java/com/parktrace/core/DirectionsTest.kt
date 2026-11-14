package com.parktrace.core

import org.junit.Assert.assertEquals
import org.junit.Test

class DirectionsTest {

    private fun reckon(vararg legs: Pair<Double, Float>): DeadReckoner {
        val r = DeadReckoner()
        for ((meters, steering) in legs) { r.onSteering(steering); r.advance(meters) }
        r.closePath()
        return r
    }

    @Test fun spiralThenTurn() {
        val r = reckon(20.0 to 0f, 63.0 to 30f, 30.0 to 0f, 8.0 to 35f, 10.0 to 0f)
        val steps = Directions.from(r.points, -2)
        assertEquals(listOf("입구에서 직진 20m", "나선 램프 2바퀴 (내려감)", "직진 30m", "우회전 후 9m", "주차"), steps)
    }

    @Test fun simpleLeftTurn() {
        val r = reckon(40.0 to 0f, 7.0 to -35f, 25.0 to 0f)
        assertEquals(listOf("입구에서 직진 40m", "좌회전 후 24m", "주차"), Directions.from(r.points, -1))
    }

    @Test fun sixtyThreeMetersAtThirtyDegreesIsTwoLoops() {
        val r = reckon(63.0 to 30f)
        assertEquals(720.0, r.netTurnDegrees, 10.0)
    }
}
