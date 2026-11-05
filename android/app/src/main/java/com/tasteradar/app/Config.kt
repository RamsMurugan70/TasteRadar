package com.tasteradar.app

import android.content.Context

/**
 * Settings that live on the device. The matching thresholds live on the server
 * (src/config.ts) so they can be tuned without shipping a build; what is here is
 * only what the phone itself has to decide, plus where the server is.
 */
object Config {

    private const val PREFS = "config"

    /** 10.0.2.2 is how the emulator reaches the host machine. */
    const val DEFAULT_SERVER = "http://10.0.2.2:5090"

    fun serverUrl(context: Context): String =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString("serverUrl", DEFAULT_SERVER) ?: DEFAULT_SERVER

    fun setServerUrl(context: Context, url: String) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putString("serverUrl", url.trimEnd('/')).apply()
    }

    // ---- Gates the phone owns, because they depend on this moment ----

    /** Above this you cannot stop, so there is nothing to tell you about. */
    const val MAX_SPEED_MS = 6.94f // 25 km/h

    /** Food is worth interrupting for around mealtimes and not otherwise. */
    val MEAL_WINDOWS = listOf(11 * 60 to 14 * 60 + 30, 18 * 60 + 30 to 22 * 60 + 30)

    /** Nothing at all between 22:30 and 08:00. */
    const val QUIET_FROM_MINUTES = 22 * 60 + 30
    const val QUIET_TO_MINUTES = 8 * 60

    const val MAX_ALERTS_PER_DAY = 2
    const val MIN_GAP_BETWEEN_ALERTS_MS = 90 * 60 * 1000L

    /** A place fence you merely pass is not an opportunity; dwell means stopped. */
    const val LOITERING_MS = 60_000
    const val RESPONSIVENESS_MS = 30_000
}
