package com.parktrace.sdk

/**
 * Anything that can read text off garage pillars.
 *
 * Implementations:
 *  - [SimulatedSignReader]: no camera. Sign text is injected by the demo scenario or the
 *    SIM_SIGN debug broadcast. This is what the contest build uses.
 *  - Future (향후 계획, not implemented): a vehicle-camera reader + on-device OCR, once a camera API
 *    is open to App Market apps. Only that one class needs to be added;
 *    detection / voting / UI stay the same.
 */
interface SignReader {
    /** Short status for the UI, e.g. "모의 인식 대기 중 (카메라 미구현)". */
    val status: String

    /**
     * Start reading. [onText] receives the recognised lines of one frame and the frame time.
     * Called on a background thread.
     */
    fun start(onText: (lines: List<String>, t: Long) -> Unit)

    fun stop()

    fun release()
}
