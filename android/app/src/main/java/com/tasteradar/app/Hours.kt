package com.tasteradar.app

import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant
import java.time.ZoneId

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

    /**
     * Wall-clock position in the week, in the zone the hours were published in.
     *
     * Opening hours are wall clock: 11:00 means eleven in the morning where the
     * place is, whatever the clock in your pocket says. Judging them against the
     * phone's own zone is right only while you are in the same city as the place.
     *
     * A null or unrecognised zone falls back to the device's, which is the old
     * behaviour and still correct for a single-city install.
     */
    fun minutesSinceSunday(at: Long, timezone: String? = null): Int {
        val zone = zoneOf(timezone)
        val local = Instant.ofEpochMilli(at).atZone(zone)
        // DayOfWeek is MONDAY=1 .. SUNDAY=7, so modulo 7 puts Sunday at 0.
        val day = local.dayOfWeek.value % 7
        return day * 24 * 60 + local.hour * 60 + local.minute
    }

    private fun zoneOf(timezone: String?): ZoneId {
        if (timezone.isNullOrBlank()) return ZoneId.systemDefault()
        return try {
            ZoneId.of(timezone)
        } catch (e: Exception) {
            Log.w("Hours", "unknown zone \"$timezone\", using the device's")
            ZoneId.systemDefault()
        }
    }

    private fun contains(p: Period, t: Int): Boolean =
        (t >= p.open && t < p.close) ||
            (t + WEEK_MINUTES >= p.open && t + WEEK_MINUTES < p.close)

    /** Null when the hours are unknown. */
    fun isOpenAt(periods: List<Period>?, at: Long, timezone: String? = null): Boolean? {
        if (periods.isNullOrEmpty()) return null
        val t = minutesSinceSunday(at, timezone)
        return periods.any { contains(it, t) }
    }

    /** Minutes left in the period currently open; null if unknown or closed. */
    fun minutesUntilClose(periods: List<Period>?, at: Long, timezone: String? = null): Int? {
        if (periods.isNullOrEmpty()) return null
        val t = minutesSinceSunday(at, timezone)
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
