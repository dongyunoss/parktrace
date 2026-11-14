package com.parktrace

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager

class ParkTraceApp : Application() {
    override fun onCreate() {
        super.onCreate()
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "주차 위치 기억", NotificationManager.IMPORTANCE_LOW)
        )
        // Garage entry: heads-up + full-screen intent so the app comes to the front by itself
        nm.createNotificationChannel(
            NotificationChannel(ALERT_CHANNEL_ID, "주차장 진입 알림", NotificationManager.IMPORTANCE_HIGH)
        )
    }

    companion object { const val CHANNEL_ID = "parktrace_monitor"; const val ALERT_CHANNEL_ID = "parktrace_entry" }
}
