package com.tasteradar.app

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * Going / Not now / Wrong, tapped on the notification.
 *
 * This does no network at all. It writes the tap to the queue, dismisses the
 * notification and asks WorkManager to deliver it when there is a connection.
 * A tap in a basement is recorded exactly like a tap on wifi, which matters
 * because an alert that fired in a basement is one the local path was built for.
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
        FeedbackQueue.enqueue(context, placeId, wantId, action)
        FeedbackWorker.schedule(context)
    }
}
