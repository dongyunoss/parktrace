package com.parktrace.data

import android.content.Context
import com.parktrace.core.FeePolicy
import org.json.JSONObject

/**
 * Fee tables entered by the driver, one per garage name, plus a default.
 * No fee API is used (공공데이터 API는 민간 주차장 요금이 없어서 직접 입력 방식으로 결정).
 */
class FeeBook(context: Context) {

    private val prefs = context.getSharedPreferences("fees", Context.MODE_PRIVATE)

    fun policyFor(place: String?): FeePolicy = read(place?.let { KEY_PREFIX + it }) ?: read(DEFAULT) ?: FeePolicy()

    fun save(place: String?, p: FeePolicy) {
        prefs.edit().putString(place?.let { KEY_PREFIX + it } ?: DEFAULT, toJson(p).toString()).apply()
    }

    private fun read(key: String?): FeePolicy? = key?.let { k ->
        prefs.getString(k, null)?.let { runCatching { fromJson(JSONObject(it)) }.getOrNull() }
    }

    companion object {
        private const val DEFAULT = "__default__"
        private const val KEY_PREFIX = "place:"

        fun toJson(p: FeePolicy): JSONObject =
            JSONObject().put("f", p.freeMinutes).put("u", p.unitMinutes).put("p", p.unitPrice).put("m", p.dailyMax)

        fun fromJson(o: JSONObject): FeePolicy =
            FeePolicy(o.optInt("f", 30), o.optInt("u", 10), o.optInt("p", 1000), o.optInt("m", 0))
    }
}
