package com.tasteradar.app

import android.content.Context
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject

/**
 * Feedback that has not reached the server yet.
 *
 * Tapping Going, Not now or Wrong has to work the moment it is tapped, whatever
 * the signal is doing — the whole point of the local alert path is that it works
 * in a basement, and feedback from a basement is exactly the feedback worth
 * having. So a tap writes here synchronously and the delivery is somebody else's
 * problem.
 *
 * Retrying is only safe because the server is idempotent: re-delivering the same
 * Wrong leaves one correction, not three.
 */
object FeedbackQueue {

    private const val PREFS = "feedback"
    private const val KEY = "pending"
    private const val TAG = "FeedbackQueue"

    /** Past this, a stale opinion is not worth acting on. */
    private const val MAX_AGE_MS = 7 * 24 * 60 * 60 * 1000L

    /** A runaway queue would mean something is badly wrong; cap it rather than grow forever. */
    private const val MAX_ITEMS = 200

    data class Pending(
        val placeId: String,
        val wantId: String,
        val action: String,
        val queuedAt: Long,
    )

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun pending(context: Context): List<Pending> {
        val raw = prefs(context).getString(KEY, "[]") ?: "[]"
        val array = JSONArray(raw)
        val cutoff = System.currentTimeMillis() - MAX_AGE_MS
        return (0 until array.length()).mapNotNull { i ->
            val o = array.optJSONObject(i) ?: return@mapNotNull null
            val at = o.optLong("queuedAt", 0L)
            if (at < cutoff) return@mapNotNull null
            Pending(
                placeId = o.optString("placeId"),
                wantId = o.optString("wantId"),
                action = o.optString("action"),
                queuedAt = at,
            )
        }
    }

    private fun write(context: Context, items: List<Pending>) {
        val array = JSONArray()
        items.takeLast(MAX_ITEMS).forEach { item ->
            array.put(
                JSONObject()
                    .put("placeId", item.placeId)
                    .put("wantId", item.wantId)
                    .put("action", item.action)
                    .put("queuedAt", item.queuedAt),
            )
        }
        prefs(context).edit().putString(KEY, array.toString()).apply()
    }

    /**
     * One pending item per place and want. Tapping Not now and then Wrong on the
     * same alert should send Wrong, not both in whatever order they drain.
     */
    fun enqueue(context: Context, placeId: String, wantId: String, action: String) {
        val kept = pending(context).filterNot { it.placeId == placeId && it.wantId == wantId }
        write(context, kept + Pending(placeId, wantId, action, System.currentTimeMillis()))
        Log.i(TAG, "queued $action for $placeId (${kept.size + 1} pending)")
    }

    fun remove(context: Context, placeId: String, wantId: String) {
        write(context, pending(context).filterNot { it.placeId == placeId && it.wantId == wantId })
    }

    fun size(context: Context): Int = pending(context).size

    fun clear(context: Context) {
        prefs(context).edit().remove(KEY).apply()
    }

    /**
     * Try to deliver everything. Blocking: worker thread or goAsync only.
     * Returns true when the queue is empty afterwards.
     */
    fun flush(context: Context): Boolean {
        val items = pending(context)
        if (items.isEmpty()) return true

        for (item in items) {
            try {
                // The alert itself may never have reached the server either, in
                // which case create it now so there is something to attach to.
                val alertId = FenceCache.alertId(context, item.placeId) ?: run {
                    val fence = FenceCache.fenceById(context, item.placeId)
                        ?: Fence(item.placeId, item.wantId, "", 0.0, 0.0, 0, "", false, null, null)
                    Api.postAlert(context, fence, null, null)
                        .also { FenceCache.putAlertId(context, item.placeId, it) }
                }
                Api.feedback(context, alertId, item.action)
                remove(context, item.placeId, item.wantId)
                Log.i(TAG, "delivered ${item.action} for ${item.placeId}")
            } catch (e: Api.ApiException) {
                if (e.isPermanent) {
                    // The server has rejected it outright - a deleted want, an
                    // alert it has no record of. Retrying cannot fix that.
                    Log.w(TAG, "dropping ${item.action} for ${item.placeId}: ${e.message}")
                    remove(context, item.placeId, item.wantId)
                } else {
                    Log.w(TAG, "will retry ${item.placeId}: ${e.message}")
                    return false
                }
            } catch (e: Exception) {
                Log.w(TAG, "will retry ${item.placeId}: ${e.message}")
                return false
            }
        }
        return size(context) == 0
    }
}
