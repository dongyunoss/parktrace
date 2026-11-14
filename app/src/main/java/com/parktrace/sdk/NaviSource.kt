package com.parktrace.sdk

import ai.pleos.playground.navi.constants.NaviErrorCode
import ai.pleos.playground.navi.data.CurrentLocationInfo
import ai.pleos.playground.navi.data.DestinationInfo
import ai.pleos.playground.navi.helper.NaviHelper
import ai.pleos.playground.navi.helper.listener.NaviHelperEventListener
import android.content.Context
import android.util.Log

/**
 * Pleos NaviHelper SDK: gives the record a human-friendly place name
 * ("스타필드 하남 지하주차장") from the current destination / current address.
 */
class NaviSource(context: Context) {

    private val navi = NaviHelper(context.applicationContext)
    private var connected = false

    @Volatile var destinationName: String? = null; private set
    @Volatile var destinationAddress: String? = null; private set
    @Volatile var currentAddress: String? = null; private set

    private val listener = object : NaviHelperEventListener {
        override fun onDestinationInfo(destinationInfo: DestinationInfo) {
            destinationName = destinationInfo.name.takeIf { it.isNotBlank() }
            destinationAddress = destinationInfo.address.takeIf { it.isNotBlank() }
        }
        override fun onCurrentLocationInfo(currentLocationInfo: CurrentLocationInfo) {
            currentAddress = currentLocationInfo.address.takeIf { it.isNotBlank() }
        }
        override fun onError(errorCode: NaviErrorCode) {
            Log.w(TAG, "navi error: $errorCode")
        }
    }

    fun start() {
        runCatching {
            navi.initialize(
                onConnected = {
                    connected = true
                    navi.addListener(listener)
                    refresh()
                },
                onFailed = { t -> Log.w(TAG, "NaviHelper connection failed: ${t.message}") },
            )
        }.onFailure { Log.w(TAG, "NaviHelper init failed", it) }
    }

    /**
     * Results arrive asynchronously through the listener. The request itself is an IPC to the navigation app,
     * which can take seconds when the head unit is busy, so it never runs on the main thread.
     */
    fun refresh() {
        if (!connected) return
        io.execute {
            val t0 = System.currentTimeMillis()
            runCatching { navi.getDestinationInfo() }
            runCatching { navi.getCurrentLocationInfo() }
            Log.d(TAG, "navi refresh took ${System.currentTimeMillis() - t0} ms")
        }
    }

    private val io = java.util.concurrent.Executors.newSingleThreadExecutor()

    /** Best label for "where is this garage". */
    fun placeName(): String? = destinationName ?: currentAddress ?: destinationAddress

    fun stop() {
        if (!connected) return
        runCatching { navi.removeListener(listener) }
        runCatching { navi.release() }
        connected = false
    }

    companion object { private const val TAG = "ParkTrace/Navi" }
}
