package com.tasteradar.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import kotlin.math.abs

/** The alert itself, and the three buttons that teach it. */
object Notifier {

    private const val CHANNEL_ID = "matches"
    const val ACTION_FEEDBACK = "com.tasteradar.app.FEEDBACK"

    fun ensureChannel(context: Context) {
        val manager = context.getSystemService(NotificationManager::class.java)
        if (manager.getNotificationChannel(CHANNEL_ID) != null) return
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                "Matches nearby",
                NotificationManager.IMPORTANCE_HIGH,
            ).apply { description = "Fires when you pass somewhere that fits a want" },
        )
    }

    fun notificationId(placeId: String): Int = abs(placeId.hashCode())

    private fun feedbackIntent(context: Context, fence: Fence, action: String): PendingIntent {
        val intent = Intent(context, FeedbackReceiver::class.java).apply {
            this.action = ACTION_FEEDBACK
            putExtra("placeId", fence.placeId)
            putExtra("wantId", fence.wantId)
            putExtra("feedback", action)
        }
        return PendingIntent.getBroadcast(
            context,
            (fence.placeId + action).hashCode(),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    fun show(context: Context, fence: Fence, distanceM: Int?) {
        ensureChannel(context)

        // Tapping the body opens the place in whatever maps app is installed.
        val mapIntent = Intent(
            Intent.ACTION_VIEW,
            Uri.parse("geo:${fence.lat},${fence.lng}?q=${Uri.encode(fence.name)}"),
        )
        val openMaps = PendingIntent.getActivity(
            context,
            notificationId(fence.placeId),
            mapIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        val subtitle = buildString {
            if (distanceM != null) append("${distanceM} m")
            if (fence.reason.isNotBlank()) {
                if (isNotEmpty()) append(" · ")
                append(fence.reason)
            }
        }

        val notification: Notification = Notification.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_dialog_map)
            .setContentTitle(fence.name)
            .setContentText(subtitle)
            .setStyle(Notification.BigTextStyle().bigText(subtitle))
            .setContentIntent(openMaps)
            .setAutoCancel(true)
            .addAction(
                Notification.Action.Builder(null, "Going", feedbackIntent(context, fence, "going")).build(),
            )
            .addAction(
                Notification.Action.Builder(null, "Not now", feedbackIntent(context, fence, "not_now")).build(),
            )
            .addAction(
                Notification.Action.Builder(null, "Wrong", feedbackIntent(context, fence, "wrong")).build(),
            )
            .build()

        context.getSystemService(NotificationManager::class.java)
            .notify(notificationId(fence.placeId), notification)
    }

    fun cancel(context: Context, placeId: String) {
        context.getSystemService(NotificationManager::class.java)
            .cancel(notificationId(placeId))
    }
}
