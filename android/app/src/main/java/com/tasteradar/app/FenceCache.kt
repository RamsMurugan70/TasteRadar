package com.tasteradar.app

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * The fences the phone currently holds, and the bookkeeping the gates need.
 *
 * Fifteen rows of JSON in SharedPreferences. The spec said Room; Room means an
 * annotation processor and a schema for a list that never exceeds fifteen
 * entries, which is a build step bought for nothing.
 *
 * This cache is why an alert works with no signal: by the time you reach a place,
 * its name, score and reason are already on the device.
 */
object FenceCache {

    private const val PREFS = "fences"
    private const val KEY_FENCES = "fences"
    private const val KEY_ALERTS = "alertLog"

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun save(context: Context, set: FenceSet) {
        val array = JSONArray()
        set.fences.forEach { f ->
            array.put(
                JSONObject()
                    .put("placeId", f.placeId)
                    .put("wantId", f.wantId)
                    .put("name", f.name)
                    .put("lat", f.lat)
                    .put("lng", f.lng)
                    .put("score", f.score)
                    .put("reason", f.reason)
                    .put("seenBefore", f.seenBefore)
                    .put("hours", hoursToJson(f.hours)),
            )
        }
        prefs(context).edit()
            .putString(KEY_FENCES, array.toString())
            .putFloat("umbrellaLat", set.umbrella.lat.toFloat())
            .putFloat("umbrellaLng", set.umbrella.lng.toFloat())
            .putFloat("umbrellaRadius", set.umbrella.radiusM)
            .putFloat("fenceRadius", set.fenceRadiusM)
            .putLong("savedAt", System.currentTimeMillis())
            .apply()
    }

    fun fences(context: Context): List<Fence> {
        val raw = prefs(context).getString(KEY_FENCES, "[]") ?: "[]"
        val array = JSONArray(raw)
        return (0 until array.length()).map { i ->
            val f = array.getJSONObject(i)
            Fence(
                placeId = f.getString("placeId"),
                wantId = f.getString("wantId"),
                name = f.getString("name"),
                lat = f.getDouble("lat"),
                lng = f.getDouble("lng"),
                score = f.getInt("score"),
                reason = f.optString("reason"),
                seenBefore = f.optBoolean("seenBefore", false),
                hours = Hours.parse(f.optJSONObject("hours")),
            )
        }
    }

    private fun hoursToJson(periods: List<Hours.Period>?): JSONObject? {
        if (periods == null) return null
        val array = JSONArray()
        periods.forEach { array.put(JSONObject().put("o", it.open).put("c", it.close)) }
        return JSONObject().put("periods", array)
    }

    fun fenceById(context: Context, placeId: String): Fence? =
        fences(context).firstOrNull { it.placeId == placeId }

    fun umbrella(context: Context): Umbrella? {
        val p = prefs(context)
        if (!p.contains("umbrellaLat")) return null
        return Umbrella(
            lat = p.getFloat("umbrellaLat", 0f).toDouble(),
            lng = p.getFloat("umbrellaLng", 0f).toDouble(),
            radiusM = p.getFloat("umbrellaRadius", 3000f),
        )
    }

    fun fenceRadiusM(context: Context): Float = prefs(context).getFloat("fenceRadius", 150f)

    fun savedAt(context: Context): Long = prefs(context).getLong("savedAt", 0L)

    fun clear(context: Context) {
        prefs(context).edit().clear().apply()
    }

    // ---- Alert bookkeeping, for the rate limit gate ----

    /** Timestamps of alerts actually shown, newest last, trimmed to two days. */
    fun alertTimes(context: Context): List<Long> {
        val raw = prefs(context).getString(KEY_ALERTS, "[]") ?: "[]"
        val array = JSONArray(raw)
        val cutoff = System.currentTimeMillis() - 2 * 86_400_000L
        return (0 until array.length()).map { array.getLong(it) }.filter { it > cutoff }
    }

    fun recordAlert(context: Context, at: Long = System.currentTimeMillis()) {
        val times = alertTimes(context) + at
        val array = JSONArray()
        times.forEach { array.put(it) }
        prefs(context).edit().putString(KEY_ALERTS, array.toString()).apply()
    }

    // ---- The server's id for an alert we have already shown ----
    //
    // The notification goes up before the server hears about it, so that an alert
    // still works with no signal. The id arrives a moment later, or not at all.

    fun putAlertId(context: Context, placeId: String, alertId: String) {
        prefs(context).edit().putString("alert:$placeId", alertId).apply()
    }

    fun alertId(context: Context, placeId: String): String? =
        prefs(context).getString("alert:$placeId", null)
}
