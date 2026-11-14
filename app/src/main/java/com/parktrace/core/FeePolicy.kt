package com.parktrace.core

import kotlin.math.max
import kotlin.math.min

/**
 * The usual Korean garage tariff: "최초 N분 무료, 이후 M분당 P원, 1일 최대 X원".
 *
 * Every started unit is charged (free time + 10 min 1 s = 2 units), which is how most garages bill.
 * Pure logic: the phone page (web/index.html) has the same formula in JavaScript; change both together.
 */
data class FeePolicy(
    val freeMinutes: Int = 30,
    val unitMinutes: Int = 10,
    val unitPrice: Int = 1000,
    /** 0 = no daily cap. The cap applies per started 24 h since entry. */
    val dailyMax: Int = 0,
) {
    init {
        require(freeMinutes >= 0 && unitMinutes > 0 && unitPrice >= 0 && dailyMax >= 0) { "invalid fee policy" }
    }

    private val unitMs: Long get() = unitMinutes * MIN

    fun freeUntil(enteredAt: Long): Long = enteredAt + freeMinutes * MIN

    /** Fee owed if the car left at [now]. */
    fun feeAt(enteredAt: Long, now: Long): Int {
        val over = now - freeUntil(enteredAt)
        if (over <= 0) return 0
        val units = (over + unitMs - 1) / unitMs
        var fee = units * unitPrice
        if (dailyMax > 0) {
            val days = max(1L, (now - enteredAt + DAY - 1) / DAY)
            fee = min(fee, days * dailyMax)
        }
        return fee.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
    }

    /** The moment after which the fee goes up next, or null when it no longer rises today (free garage or cap reached). */
    fun nextIncreaseAt(enteredAt: Long, now: Long): Long? {
        if (unitPrice == 0) return null
        val free = freeUntil(enteredAt)
        val next = if (now < free) free else free + ((now - free + unitMs - 1) / unitMs) * unitMs
        return if (feeAt(enteredAt, next + 1) > feeAt(enteredAt, now)) next else null
    }

    /** "최초 30분 무료 · 이후 10분당 1,000원 · 1일 최대 30,000원" */
    fun describe(): String = buildList {
        add(if (freeMinutes > 0) "최초 ${minutesText(freeMinutes)} 무료" else "무료 시간 없음")
        add(if (unitPrice > 0) "이후 ${minutesText(unitMinutes)}당 ${won(unitPrice)}" else "이후에도 무료")
        if (dailyMax > 0) add("1일 최대 ${won(dailyMax)}")
    }.joinToString(" · ")

    companion object {
        const val MIN = 60_000L
        const val DAY = 24 * 60 * MIN

        fun won(v: Int): String = String.format(java.util.Locale.KOREA, "%,d원", v)

        fun minutesText(m: Int): String = when {
            m >= 60 && m % 60 == 0 -> "${m / 60}시간"
            m > 60 -> "${m / 60}시간 ${m % 60}분"
            else -> "${m}분"
        }
    }
}
