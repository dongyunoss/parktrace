package com.parktrace.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * Test / demo hooks for adb (tools/emulator-drive.ps1). Protected by android.permission.DUMP, so only
 * adb shell (or the system) can send them:
 *
 *   # simulated "주차장 진입" while the app is in the background (shows the auto launch)
 *   adb shell am broadcast -a com.parktrace.action.SIM_GARAGE_ENTRY -n com.parktrace/.service.DebugEntryReceiver
 *   # what the (future) camera would have read on a pillar sign; "|" separates lines
 *   adb shell am broadcast -a com.parktrace.action.SIM_SIGN --es text "B2|C-15" -n com.parktrace/.service.DebugEntryReceiver
 */
class DebugEntryReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val svc = ParkTraceState.service
        when (intent.action) {
            ParkingMonitorService.ACTION_SIM_SIGN -> intent.getStringExtra("text")?.let { svc?.simulateSign(it) }
            else -> if (svc != null) {
                svc.manualGarageEntry()
            } else {
                runCatching {
                    context.startForegroundService(
                        Intent(context, ParkingMonitorService::class.java).setAction(ParkingMonitorService.ACTION_SIM_ENTRY)
                    )
                }
            }
        }
    }
}
