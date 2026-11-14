package com.parktrace.demo

import android.os.Handler
import android.os.Looper
import com.parktrace.core.Signal
import com.parktrace.service.ParkingMonitorService

/**
 * Deterministic PT demo (≈ 20 s): road → public car park gate → straight ramp down to B1 → B1 aisle → right turn → park at C-15.
 *
 * Uses a synthetic clock (1 step = 1 s of vehicle time) so detector timers behave exactly like
 * on the road, while the playback itself is sped up. Pillar signs are injected as text,
 * i.e. exactly what a camera reader would deliver later (the camera itself is not implemented).
 *
 * For a live demo, skip this: change steering / odometer / doors in the Pleos Connect Virtual Console
 * and tap the "모의 표지판" buttons on the main screen.
 */
class DemoScenario(private val service: ParkingMonitorService, private val onDone: () -> Unit) {

    private val main = Handler(Looper.getMainLooper())
    private val steps = mutableListOf<() -> Unit>()
    private var clock = System.currentTimeMillis()
    private var trip = 0f
    private var fastFrom = Int.MAX_VALUE
    private var lat = 37.5450
    private var lon = 127.2240
    private var alt = 32.0

    private fun t(): Long { clock += 1000; return clock }

    private fun drive(meters: Float, steering: Float, confidence: Float, altPerStep: Double = 0.0, sign: String? = null) {
        steps += {
            val now = t()
            service.inject(Signal.Steering(now, steering))
            trip += meters
            service.inject(Signal.TripDistance(now, trip))
            if (confidence >= 0.6f) { lat += meters / 111_000.0 } // GPS only moves while it is good
            alt += altPerStep
            service.inject(Signal.Location(now, lat, lon, alt, 0f, confidence))
            sign?.let { service.onSignText(it.split("|"), now) }
        }
    }

    private fun idle(seconds: Int, block: ((Long) -> Unit)? = null) {
        repeat(seconds) { steps += { val now = t(); block?.invoke(now); service.inject(Signal.Tick(now)) } }
    }

    fun start() {
        service.setDemoMode(true)
        // 1. road, good GPS
        repeat(4) { drive(5f, 0f, 0.95f) }
        // 2. public car park entrance: GPS confidence collapses under the canopy -> garage entry (3rd weak fix)
        repeat(3) { i -> drive(3f, 0f, 0.1f, altPerStep = -0.05, sign = if (i == 0) "공영주차장 입구|높이제한 2.3M" else null) }
        //    6 m further in: stop at the barrier (plate recognition), then it opens
        repeat(2) { drive(3f, 0f, 0.1f) }
        idle(2) { now -> service.inject(Signal.Brake(now, true)) }
        idle(1) { now -> service.inject(Signal.Brake(now, false)); service.onSignText(listOf("입차 완료"), now) }
        drive(4f, 0f, 0.1f)
        // 3. straight ramp down to B1, right turn into the aisle
        repeat(4) { i -> drive(4.5f, 0f, 0.15f, altPerStep = -0.8, sign = if (i == 3) "B1" else null) }
        drive(4f, 0f, 0.15f)
        repeat(2) { i -> drive(3.95f, 30f, 0.15f, sign = if (i == 1) "B1|C-1" else null) }
        // 4. B1 aisle, pillars C-12 .. C-14
        repeat(6) { i ->
            val sign = when (i) { 1 -> "B1|C-12"; 3 -> "B1-C13"; 5 -> "C14"; else -> null }
            drive(5f, 0f, 0.15f, sign = sign)
        }
        // 5. drive just past the free bay (2.87 m, so the 90 deg reverse arc ends in its middle) and stop
        drive(2.87f, 0f, 0.15f, sign = "C-15")
        idle(1) { now -> service.inject(Signal.Brake(now, true)); service.onSignText(listOf("B1", "CI5"), now) }
        // 6. back in: gear R, wheel to the right, a quarter circle (4.14 m radius), then straight back
        idle(1) { now -> service.inject(Signal.Gear(now, park = false, reverse = true)); service.inject(Signal.Brake(now, false)) }
        // from here the car's camera view is in front and the manoeuvre is not visible: the rest is played in one
        // go, so the parking is saved by the time the camera view closes (~3 s)
        fastFrom = steps.size
        repeat(4) { drive(1.626f, 35f, 0.15f) }
        drive(1.36f, 0f, 0.15f, sign = "C15")
        // 7. gear P -> saved and announced; then the driver gets out
        idle(1) { now -> service.inject(Signal.Brake(now, true)); service.inject(Signal.Gear(now, park = true)) }
        idle(1) { now -> service.inject(Signal.DriverDoor(now, true)) }
        idle(1) { now -> service.inject(Signal.DriverDoor(now, false)) }
        play(0)
    }

    private fun play(i: Int) {
        if (i >= steps.size) {
            main.postDelayed({ service.setDemoMode(false); onDone() }, 1500)
            return
        }
        if (i >= fastFrom) {
            for (j in i until steps.size) steps[j]()
            play(steps.size)
            return
        }
        steps[i]()
        main.postDelayed({ play(i + 1) }, STEP_MS)
    }

    fun cancel() {
        main.removeCallbacksAndMessages(null)
        service.setDemoMode(false)
    }

    companion object { private const val STEP_MS = 450L }
}
