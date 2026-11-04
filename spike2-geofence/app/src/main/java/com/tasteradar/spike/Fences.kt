package com.tasteradar.spike

import android.Manifest
import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import com.google.android.gms.location.Geofence
import com.google.android.gms.location.GeofencingRequest
import com.google.android.gms.location.LocationServices

object Fences {

    /** 150 m. Below about 100 m the OS gets unreliable, whatever you ask for. */
    const val RADIUS_M = 150f

    /** 0 = report as fast as the OS can. Production would use 30-60s to save battery. */
    const val RESPONSIVENESS_MS = 0

    /** How long you must stay inside before a DWELL fires. */
    const val LOITERING_MS = 60_000

    fun pendingIntent(context: Context): PendingIntent {
        val intent = Intent(context, GeofenceReceiver::class.java)
        // MUTABLE is required: the system writes the transition details into this
        // intent. FLAG_IMMUTABLE here means every event arrives empty.
        val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
        } else {
            PendingIntent.FLAG_UPDATE_CURRENT
        }
        return PendingIntent.getBroadcast(context, 0, intent, flags)
    }

    fun hasLocationPermission(context: Context): Boolean =
        context.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED

    fun hasBackgroundPermission(context: Context): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            context.checkSelfPermission(
                Manifest.permission.ACCESS_BACKGROUND_LOCATION
            ) == PackageManager.PERMISSION_GRANTED
        } else true

    @SuppressLint("MissingPermission")
    fun registerAll(context: Context, onResult: (String) -> Unit) {
        val fences = FenceStore.all(context)
        if (fences.isEmpty()) {
            onResult("No fences yet. Walk somewhere and press Drop fence here.")
            return
        }
        if (!hasLocationPermission(context)) {
            onResult("Location permission missing.")
            return
        }

        val geofences = fences.map { f ->
            Geofence.Builder()
                .setRequestId(f.id)
                .setCircularRegion(f.lat, f.lng, RADIUS_M)
                .setExpirationDuration(Geofence.NEVER_EXPIRE)
                .setTransitionTypes(
                    Geofence.GEOFENCE_TRANSITION_ENTER or
                        Geofence.GEOFENCE_TRANSITION_EXIT or
                        Geofence.GEOFENCE_TRANSITION_DWELL
                )
                .setLoiteringDelay(LOITERING_MS)
                .setNotificationResponsiveness(RESPONSIVENESS_MS)
                .build()
        }

        val request = GeofencingRequest.Builder()
            // Do not fire for fences you are already standing inside.
            .setInitialTrigger(0)
            .addGeofences(geofences)
            .build()

        LocationServices.getGeofencingClient(context)
            .addGeofences(request, pendingIntent(context))
            .addOnSuccessListener {
                FenceLog.note(context, "registered ${geofences.size} fences")
                onResult("Registered ${geofences.size} fences.")
            }
            .addOnFailureListener { e ->
                FenceLog.note(context, "register FAILED ${e.message}")
                onResult("Register failed: ${e.message}")
            }
    }
}
