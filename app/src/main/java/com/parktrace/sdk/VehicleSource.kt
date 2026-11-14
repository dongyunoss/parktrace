package com.parktrace.sdk

import ai.pleos.playground.vehicle.Vehicle
import ai.pleos.playground.vehicle.api.model.area.DoorArea
import ai.pleos.playground.vehicle.api.model.area.SeatArea
import ai.pleos.playground.vehicle.constant.area.DoorValue
import ai.pleos.playground.vehicle.constant.area.SeatValue
import ai.pleos.playground.vehicle.listener.BrakePedalStatusListener
import ai.pleos.playground.vehicle.listener.DistanceDrivenListener
import ai.pleos.playground.vehicle.listener.DoorLockStateListener
import ai.pleos.playground.vehicle.listener.DoorPositionListener
import ai.pleos.playground.vehicle.listener.GearSelectionListener
import ai.pleos.playground.vehicle.constant.control.VehicleGear
import ai.pleos.playground.vehicle.listener.SeatOccupancyListener
import ai.pleos.playground.vehicle.listener.SteeringWheelAngleListener
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.parktrace.core.Signal

/**
 * Pleos Vehicle SDK -> [Signal].
 * Only normal-protection permissions: CAR_DOORS, CAR_DRIVE, CAR_SEATS.
 */
class VehicleSource(
    context: Context,
    /** Thread for SDK calls (each register/unregister is an IPC; keep it off the main thread). */
    looper: Looper,
    private val emit: (Signal) -> Unit,
) {

    private val vehicle = Vehicle(context.applicationContext)
    private val worker = Handler(looper)
    private val driverDoor = DoorArea(DoorValue.ROW_1_LEFT)
    private val driverSeat = SeatArea(SeatValue.ROW_1_LEFT)
    private var retries = 0
    @Volatile private var retryPending = false
    private var started = false

    private fun now() = System.currentTimeMillis()

    private fun failed(what: String, e: Exception) {
        Log.w(TAG, "$what failed: ${e.message}")
        // Not supported on this vehicle / emulator: re-registering everything would not help.
        if (e.message?.contains("NOT_SUPPORTED") == true) return
        // The vehicle service binds asynchronously; retry registration a few times (one pending retry at a time).
        if (started && retries < 3 && !retryPending) {
            retries++
            retryPending = true
            worker.postDelayed({ retryPending = false; registerAll() }, 2_000L * retries)
        }
    }

    /** false once the vehicle says it has no trip distance (e.g. the Pleos emulator); then the odometer is used. */
    @Volatile private var tripSupported = true

    private val tripListener = object : DistanceDrivenListener {
        override fun onDistanceDrivenUpdated(value: Float?) {
            value ?: return
            tripSupported = true
            emit(Signal.TripDistance(now(), value))
        }
        override fun onFailed(e: Exception) {
            if (e.message?.contains("NOT_SUPPORTED") == true) {
                tripSupported = false
                Log.i(TAG, "trip distance not supported: falling back to the odometer (PERF_ODOMETER, km)")
            }
            failed("trip distance", e)
        }
    }

    /** Total odometer in km. Only used when the trip distance is not available. Float km -> m loses precision on
     *  real cars with a high mileage, which is why the trip distance stays the first choice. */
    private val odometerListener = object : DistanceDrivenListener {
        override fun onDistanceDrivenUpdated(value: Float?) {
            value ?: return
            Log.d(TAG, "odometer $value km (trip supported=$tripSupported)")
            if (!tripSupported) emit(Signal.TripDistance(now(), value * 1000f))
        }
        override fun onFailed(e: Exception) = failed("odometer", e)
    }
    private val steeringListener = object : SteeringWheelAngleListener {
        override fun onSteeringWheelAngleUpdated(angle: Float?) {
            angle ?: return
            Log.d(TAG, "steering $angle")
            emit(Signal.Steering(now(), angle))
        }
        override fun onFailed(e: Exception) = failed("steering", e)
    }
    private val brakeListener = object : BrakePedalStatusListener {
        override fun onBrakePedalStatusUpdated(status: Boolean?) { status?.let { emit(Signal.Brake(now(), it)) } }
        override fun onFailed(e: Exception) = failed("brake", e)
    }
    /** P confirms the stop after 3 s (instead of 8 s without a gear); R makes the path go backwards. */
    private val gearListener = object : GearSelectionListener {
        override fun onSelected(gear: VehicleGear?) {
            Log.d(TAG, "gear $gear")
            when (gear) {
                null, VehicleGear.GEAR_UNKNOWN -> Unit // unknown: keep the no-gear rules
                else -> emit(Signal.Gear(now(), park = gear == VehicleGear.GEAR_PARK, reverse = gear == VehicleGear.GEAR_REVERSE))
            }
        }
        override fun onFailed(e: Exception) = failed("gear", e)
    }

    private val doorPositionListener = object : DoorPositionListener {
        override fun onPositionUpdated(pos: Int?) { pos?.let { emit(Signal.DriverDoor(now(), it > 0)) } }
        override fun onFailed(e: Exception) = failed("door position", e)
    }
    private val doorLockListener = object : DoorLockStateListener {
        override fun onDoorLockStateUpdated(state: Boolean?) { state?.let { emit(Signal.DoorLock(now(), it)) } }
        override fun onFailed(e: Exception) = failed("door lock", e)
    }
    private val seatListener = object : SeatOccupancyListener {
        override fun onSeatOccupancyUpdated(occupancy: Boolean?) { occupancy?.let { emit(Signal.DriverSeat(now(), it)) } }
        override fun onFailed(e: Exception) = failed("seat occupancy", e)
    }

    fun start() {
        if (started) return
        started = true
        vehicle.initialize()
        worker.postDelayed({ registerAll() }, 500)
    }

    private fun registerAll() {
        runCatching {
            unregisterAll()
            vehicle.getOdometer().registerTripCurrentDrivingDistance(tripListener) // meters
            vehicle.getOdometer().registerDistanceDriven(odometerListener) // km, fallback only
            vehicle.getSteeringWheel().registerSteeringWheelAngle(steeringListener) // front-wheel deg, left < 0
            vehicle.getBrake().registerBrakePedalStatus(brakeListener)
            vehicle.getGear().registerGearSelection(gearListener)
            vehicle.getDoor().registerDoorPosition(driverDoor, doorPositionListener)
            vehicle.getDoor().registerDoorLockState(driverDoor, doorLockListener)
            vehicle.getSeat().registerSeatOccupancy(driverSeat, seatListener)
        }.onFailure { Log.e(TAG, "register failed", it) }
    }

    private fun unregisterAll() {
        runCatching { vehicle.getOdometer().unregisterTripCurrentDrivingDistance(tripListener) }
        runCatching { vehicle.getOdometer().unregisterDistanceDriven(odometerListener) }
        runCatching { vehicle.getSteeringWheel().unregisterSteeringWheelAngle(steeringListener) }
        runCatching { vehicle.getBrake().unregisterBrakePedalStatus(brakeListener) }
        runCatching { vehicle.getGear().unregisterGearSelection(gearListener) }
        runCatching { vehicle.getDoor().unregisterDoorPosition(driverDoor, doorPositionListener) }
        runCatching { vehicle.getDoor().unregisterDoorLockState(driverDoor, doorLockListener) }
        runCatching { vehicle.getSeat().unregisterSeatOccupancy(driverSeat, seatListener) }
    }

    fun stop() {
        if (!started) return
        started = false
        worker.removeCallbacksAndMessages(null)
        unregisterAll()
        runCatching { vehicle.release() }
    }

    companion object { private const val TAG = "ParkTrace/Vehicle" }
}
