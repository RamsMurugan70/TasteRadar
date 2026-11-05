package com.tasteradar.app

import org.json.JSONArray
import org.json.JSONObject
import java.util.Calendar

/**
 * Opening hours, evaluated at alert time.
 *
 * The wire format is a list of periods in minutes since Sunday 00:00 local time,
 * where `c` is always greater than `o`. A period running past midnight simply has
 * a `c` beyond its day, and one running past Saturday midnight has a `c` beyond
 * 10080. That removes wrap-around as a special case: a check only has to test
 * `t` and `t + WEEK_MINUTES`.
 *
 * This is a line-by-line port of `workers/hours.py`, which has 16 tests covering
 * the awkward cases - a close after midnight, a Saturday period wrapping into
 * Sunday, and unknown staying distinct from closed. Change one, change both.
 */
object Hours {

    const val WEEK_MINUTES = 7 * 24 * 60

    data class Period(val open: Int, val close: Int)

    /** Null means unknown, which is never the same as closed. */
    fun parse(json: JSONObject?): List<Period>? {
        if (json == null) return null
        val array: JSONArray = json.optJSONArray("periods") ?: return null
        if (array.length() == 0) return null
        return (0 until array.length()).mapNotNull { i ->
            val p = array.optJSONObject(i) ?: return@mapNotNull null
            if (!p.has("o") || !p.has("c")) return@mapNotNull null
            Period(p.getInt("o"), p.getInt("c"))
        }.ifEmpty { null }
    }

    /** Calendar.SUNDAY is 1, so subtracting one gives Sunday = 0. */
    fun minutesSinceSunday(at: Long): Int {
        val cal = Calendar.getInstance().apply { timeInMillis = at }
        val day = cal.get(Calendar.DAY_OF_WEEK) - Calendar.SUNDAY
        return day * 24 * 60 + cal.get(Calendar.HOUR_OF_DAY) * 60 + cal.get(Calendar.MINUTE)
    }

    private fun contains(p: Period, t: Int): Boolean =
        (t >= p.open && t < p.close) ||
            (t + WEEK_MINUTES >= p.open && t + WEEK_MINUTES < p.close)

    /** Null when the hours are unknown. */
    fun isOpenAt(periods: List<Period>?, at: Long): Boolean? {
        if (periods.isNullOrEmpty()) return null
        val t = minutesSinceSunday(at)
        return periods.any { contains(it, t) }
    }

    /** Minutes left in the period currently open; null if unknown or closed. */
    fun minutesUntilClose(periods: List<Period>?, at: Long): Int? {
        if (periods.isNullOrEmpty()) return null
        val t = minutesSinceSunday(at)
        var best: Int? = null
        for (p in periods) {
            for (candidate in intArrayOf(t, t + WEEK_MINUTES)) {
                if (candidate >= p.open && candidate < p.close) {
                    val left = p.close - candidate
                    if (best == null || left < best) best = left
                }
            }
        }
        return best
    }
}
