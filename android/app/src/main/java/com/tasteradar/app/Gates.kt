package com.tasteradar.app

import android.content.Context
import java.util.Calendar

/**
 * The suppression checks the phone owns, because each depends on this moment
 * rather than on history. The server already applied the ones that need history:
 * the score threshold, the 30-day place cooldown, the 90 days after a Not now,
 * the permanent exclusion after a Wrong, and home and work.
 *
 * This is the part of the product that decides whether it gets muted, so it is
 * written to be read and argued with, not to be clever.
 */
object Gates {

    sealed interface Verdict {
        data object Allow : Verdict
        data class Suppress(val because: String) : Verdict
    }

    fun check(
        context: Context,
        speedMs: Float?,
        hasSpeed: Boolean,
        hours: List<Hours.Period>? = null,
        timezone: String? = null,
        now: Long = System.currentTimeMillis(),
    ): Verdict {
        val minutes = minutesIntoDay(now)

        if (inQuietHours(minutes)) return Verdict.Suppress("quiet hours")

        // Only trust a speed the fix actually carried. A missing speed reads as
        // 0.0 on Android, which would let an alert through on a motorway.
        if (hasSpeed && speedMs != null && speedMs > Config.MAX_SPEED_MS) {
            return Verdict.Suppress("moving at ${(speedMs * 3.6).toInt()} km/h")
        }

        // Three states, not two. Unknown hours allow the alert: guessing would
        // silence real matches, and plenty of small places publish nothing.
        when (Hours.isOpenAt(hours, now, timezone)) {
            false -> return Verdict.Suppress("closed right now")
            true -> {
                val left = Hours.minutesUntilClose(hours, now, timezone)
                if (left != null && left < Config.MIN_MINUTES_BEFORE_CLOSING) {
                    return Verdict.Suppress("closes in $left min")
                }
            }
            null -> Unit // hours unknown
        }

        if (!inAnyMealWindow(minutes)) return Verdict.Suppress("not a mealtime")

        val times = FenceCache.alertTimes(context)
        val today = times.count { sameDay(it, now) }
        if (today >= Config.MAX_ALERTS_PER_DAY) {
            return Verdict.Suppress("already $today alerts today")
        }

        val last = times.maxOrNull()
        if (last != null && now - last < Config.MIN_GAP_BETWEEN_ALERTS_MS) {
            val mins = (now - last) / 60_000
            return Verdict.Suppress("only $mins min since the last alert")
        }

        return Verdict.Allow
    }

    fun minutesIntoDay(at: Long): Int {
        val cal = Calendar.getInstance().apply { timeInMillis = at }
        return cal.get(Calendar.HOUR_OF_DAY) * 60 + cal.get(Calendar.MINUTE)
    }

    /** The window wraps midnight, so this is an OR and not a BETWEEN. */
    fun inQuietHours(minutes: Int): Boolean =
        minutes >= Config.QUIET_FROM_MINUTES || minutes < Config.QUIET_TO_MINUTES

    fun inAnyMealWindow(minutes: Int): Boolean =
        Config.MEAL_WINDOWS.any { (from, to) -> minutes in from..to }

    private fun sameDay(a: Long, b: Long): Boolean {
        val ca = Calendar.getInstance().apply { timeInMillis = a }
        val cb = Calendar.getInstance().apply { timeInMillis = b }
        return ca.get(Calendar.YEAR) == cb.get(Calendar.YEAR) &&
            ca.get(Calendar.DAY_OF_YEAR) == cb.get(Calendar.DAY_OF_YEAR)
    }
}
