package com.parktrace.service

import android.os.Handler
import android.os.Looper
import com.parktrace.core.DeadReckoner
import com.parktrace.core.FeePolicy
import com.parktrace.core.FloorEstimate
import com.parktrace.core.ParkingDetector
import com.parktrace.core.ParkingScene
import com.parktrace.core.SpotVoter
import com.parktrace.data.ParkingRecord
import java.util.concurrent.CopyOnWriteArrayList

/** Process-wide observable state shared by the service and the UI. Always mutated on the main thread. */
object ParkTraceState {

    data class Ui(
        val phase: ParkingDetector.Phase = ParkingDetector.Phase.DRIVING,
        val entryCue: ParkingDetector.EntryCue? = null,
        val placeName: String? = null,
        val estimate: FloorEstimate? = null,
        val path: List<DeadReckoner.Point> = emptyList(),
        val liveDecision: SpotVoter.Decision? = null,
        val lastSignText: String? = null,
        val cameraStatus: String = "꺼짐 (카메라 미구현)",
        val demoRunning: Boolean = false,
        val latest: ParkingRecord? = null,
        val message: String? = null,
        /** The car's camera view is in front of this app: nothing is drawn meanwhile. */
        val cameraUp: Boolean = false,
        /** Metres along the path to the entrance barrier, when the garage was entered by geofence. */
        val gateMeters: Double? = null,
        /** Backing into a bay (gear R): the car's surround-camera screen is in front. */
        val parkingMode: Boolean = false,
        /** Garage entry time of the current visit (fee clock), null outside a garage. */
        val enteredAt: Long? = null,
        val feePolicy: FeePolicy? = null,
        /** What the 3D view draws: live path while driving, surroundings once stopped / parked. */
        val scene: ParkingScene? = null,
    )

    private val main = Handler(Looper.getMainLooper())
    private val listeners = CopyOnWriteArrayList<(Ui) -> Unit>()

    @Volatile var ui = Ui()
        private set

    fun update(block: (Ui) -> Ui) {
        if (Looper.myLooper() != Looper.getMainLooper()) { main.post { update(block) }; return }
        ui = block(ui)
        listeners.forEach { it(ui) }
    }

    fun observe(l: (Ui) -> Unit) { listeners += l; l(ui) }
    fun remove(l: (Ui) -> Unit) { listeners -= l }

    /** True while MainActivity is on screen (no need to auto-launch it). */
    @Volatile var uiVisible = false

    /** Set by the running service so the UI / demo can talk to it. */
    @Volatile var service: ParkingMonitorService? = null
}
