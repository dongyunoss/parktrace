package com.parktrace.sdk

/**
 * Stand-in for the vehicle camera. It does NOT open any camera.
 *
 * Camera access is not available to contest apps yet, so camera recognition is a "향후 계획" item.
 * Until then, sign text is fed by the demo scenario or by the SIM_SIGN debug broadcast.
 * The text goes through exactly the same path the real camera reader will use later
 * (SignTextParser -> SpotVoter -> ParkingRecord), so every feature after "the camera read a sign"
 * can be demonstrated today.
 */
class SimulatedSignReader : SignReader {

    @Volatile private var sink: ((lines: List<String>, t: Long) -> Unit)? = null

    override val status: String
        get() = if (sink != null) "모의 인식 대기 중 (카메라 미구현)" else "꺼짐 (카메라 미구현)"

    override fun start(onText: (lines: List<String>, t: Long) -> Unit) { sink = onText }

    override fun stop() { sink = null }

    override fun release() { sink = null }

    /**
     * Pretend the camera just read [text]. "|" separates lines, e.g. "B2|C-15".
     * Returns false when the reader is not started (the car is not inside a garage).
     */
    fun feed(text: String, t: Long = System.currentTimeMillis()): Boolean {
        val s = sink ?: return false
        s(text.split("|").map { it.trim() }.filter { it.isNotEmpty() }, t)
        return true
    }
}
