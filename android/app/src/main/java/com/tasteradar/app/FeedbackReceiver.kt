package com.tasteradar.app

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import kotlin.concurrent.thread

/**
 * Going / Not now / Wrong, tapped on the notification.
 *
 * Wrong is the valuable one: the server drops that place's score to zero for
 * good and keeps it as a negative example for later extraction.
 */
class FeedbackReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Notifier.ACTION_FEEDBACK) return
        val placeId = intent.getStringExtra("placeId") ?: return
        val wantId = intent.getStringExtra("wantId") ?: return
        val action = intent.getStringExtra("feedback") ?: return

        Notifier.cancel(context, placeId)

        val pending = goAsync()
        thread {
            try {
                // The alert may not have reached the server yet - no signal at the
                // time, or the report is still in flight. Create it now if so.
                val alertId = FenceCache.alertId(context, placeId) ?: run {
                    val fence = FenceCache.fenceById(context, placeId)
                        ?: Fence(placeId, wantId, "", 0.0, 0.0, 0, "", false)
                    Api.postAlert(context, fence, null, null)
                        .also { FenceCache.putAlertId(context, placeId, it) }
                }
                Api.feedback(context, alertId, action)
                Log.i("FeedbackReceiver", "$action recorded for $placeId")
            } catch (e: Exception) {
                // Dropped on the floor on purpose. A retry queue is worth building
                // once the app is in daily use and the loss is measurable; until
                // then it is speculative complexity.
                Log.w("FeedbackReceiver", "feedback lost: ${e.message}")
            } finally {
                pending.finish()
            }
        }
    }
}
