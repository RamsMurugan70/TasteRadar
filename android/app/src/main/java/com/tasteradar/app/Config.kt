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

    /** The server's AUTH_TOKEN. Without it every request comes back 401. */
    fun authToken(context: Context): String =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString("authToken", "") ?: ""

    fun setAuthToken(context: Context, token: String) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putString("authToken", token.trim()).apply()
    }

    enum class Exposure {
        /** Encrypted, or never leaves the device. */
        SAFE,

        /** Plain HTTP to a private address: sniffable by anyone on the same wifi. */
        PRIVATE_CLEARTEXT,

        /** Plain HTTP to a public address: sniffable by anyone in between. */
        PUBLIC_CLEARTEXT,
    }

    /**
     * How exposed the token is with the current server URL.
     *
     * This lives in code because the network security config cannot express it:
     * its <domain> entries match exact hosts, with no CIDR and no wildcards, so
     * "private addresses only" is not something that file can say. Here it is a
     * few lines of arithmetic.
     */
    fun exposure(context: Context): Exposure {
        val url = serverUrl(context)
        if (!url.startsWith("http://")) return Exposure.SAFE
        val host = url.removePrefix("http://").substringBefore(':').substringBefore('/')
        if (host == "localhost" || host == "127.0.0.1" || host == "10.0.2.2") return Exposure.SAFE
        return if (isPrivateAddress(host)) Exposure.PRIVATE_CLEARTEXT else Exposure.PUBLIC_CLEARTEXT
    }

    /** RFC 1918 and link-local, the ranges a home router hands out. */
    fun isPrivateAddress(host: String): Boolean {
        val parts = host.split('.')
        if (parts.size != 4) return false
        val octets = parts.map { it.toIntOrNull() ?: return false }
        if (octets.any { it !in 0..255 }) return false
        val a = octets[0]
        val b = octets[1]
        return when {
            a == 10 -> true
            a == 192 && b == 168 -> true
            a == 172 && b in 16..31 -> true
            a == 169 && b == 254 -> true
            else -> false
        }
    }

    // ---- Gates the phone owns, because they depend on this moment ----

    /** Above this you cannot stop, so there is nothing to tell you about. */
    const val MAX_SPEED_MS = 6.94f // 25 km/h

    /** Food is worth interrupting for around mealtimes and not otherwise. */
    val MEAL_WINDOWS = listOf(11 * 60 to 14 * 60 + 30, 18 * 60 + 30 to 22 * 60 + 30)

    /** Nothing at all between 22:30 and 08:00. */
    const val QUIET_FROM_MINUTES = 22 * 60 + 30
    const val QUIET_TO_MINUTES = 8 * 60

    /** No point sending you somewhere that is about to shut. */
    const val MIN_MINUTES_BEFORE_CLOSING = 30

    const val MAX_ALERTS_PER_DAY = 2
    const val MIN_GAP_BETWEEN_ALERTS_MS = 90 * 60 * 1000L

    /** A place fence you merely pass is not an opportunity; dwell means stopped. */
    const val LOITERING_MS = 60_000
    const val RESPONSIVENESS_MS = 30_000
}
