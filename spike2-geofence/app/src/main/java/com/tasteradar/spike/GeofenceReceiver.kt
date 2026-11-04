package com.tasteradar.spike

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.google.android.gms.location.Geofence
import com.google.android.gms.location.GeofencingEvent

class GeofenceReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val event = GeofencingEvent.fromIntent(intent)
        if (event == null) {
            FenceLog.note(context, "event was null - check PendingIntent is MUTABLE")
            return
        }
        if (event.hasError()) {
            FenceLog.note(context, "error code ${event.errorCode}")
            return
        }

        val transition = when (event.geofenceTransition) {
            Geofence.GEOFENCE_TRANSITION_ENTER -> "ENTER"
            Geofence.GEOFENCE_TRANSITION_EXIT -> "EXIT"
            Geofence.GEOFENCE_TRANSITION_DWELL -> "DWELL"
            else -> "UNKNOWN_${event.geofenceTransition}"
        }

        val loc = event.triggeringLocation
        // The number the spike exists to measure: how long after you actually
        // crossed did the OS bother to tell the app.
        val delayMs = if (loc != null) System.currentTimeMillis() - loc.time else -1L
        val names = FenceStore.all(context).associate { it.id to it.name }

        event.triggeringGeofences?.forEach { g ->
            FenceLog.append(
                context = context,
                fenceId = g.requestId,
                fenceName = names[g.requestId] ?: "?",
                transition = transition,
                reportDelayMs = delayMs,
                accuracyM = loc?.accuracy ?: -1f,
                lat = loc?.latitude ?: 0.0,
                lng = loc?.longitude ?: 0.0
            )
        }
    }
}
