package com.parktrace.sdk

import ai.pleos.playground.tts.TextToSpeech
import ai.pleos.playground.tts.constant.ErrorType
import ai.pleos.playground.tts.constant.Mode
import ai.pleos.playground.tts.listener.OnInitializeListener
import android.content.Context
import android.util.Log
import java.util.Locale

/**
 * One-way spoken confirmation ("지하 2층 C-15 기둥에 주차했어요").
 * The driver never has to answer. ON_DEVICE mode -> no Pleos app registration needed.
 */
class Announcer(context: Context) {

    private val tts = TextToSpeech(context.applicationContext, Mode.ON_DEVICE)
    private var ready = false
    private var pending: String? = null
    var enabled = true

    fun start() {
        runCatching {
            tts.initialize(object : OnInitializeListener {
                override fun onSuccess() {
                    ready = true
                    runCatching { tts.setLocale(Locale.KOREA) }
                    pending?.let { speak(it) }
                    pending = null
                }
                override fun onFailure(errorType: ErrorType) {
                    Log.w(TAG, "TTS init failed: $errorType")
                }
            })
        }.onFailure { Log.w(TAG, "TTS init error", it) }
    }

    fun speak(text: String) {
        if (!enabled) return
        if (!ready) { pending = text; return }
        runCatching { tts.speak(text) }.onFailure { Log.w(TAG, "speak failed", it) }
    }

    fun stop() {
        runCatching { tts.stop() }
        runCatching { tts.release() }
        ready = false
    }

    companion object { private const val TAG = "ParkTrace/TTS" }
}
