package com.tasteradar.app

import android.Manifest
import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import com.google.android.gms.location.Geofence
import com.google.android.gms.location.GeofencingRequest
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.google.android.gms.tasks.Tasks
import java.util.concurrent.TimeUnit

/**
 * Registering and re-drawing the fence set.
 *
 * One umbrella fence watched for EXIT, and up to fifteen place fences watched
 * for ENTER and DWELL. Leaving the umbrella is the only thing that causes a
 * round trip to the server, which on a normal day is three or four times.
 */
object Fences {

    const val UMBRELLA_ID = "umbrella"
    private const val TAG = "Fences"

    fun hasLocationPermission(context: Context): Boolean =
        context.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED

    fun hasBackgroundPermission(context: Context): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            context.checkSelfPermission(Manifest.permission.ACCESS_BACKGROUND_LOCATION) ==
                PackageManager.PERMISSION_GRANTED
        } else true

    private fun pendingIntent(context: Context): PendingIntent {
        val intent = Intent(context, GeofenceReceiver::class.java)
        // MUTABLE is required: the system writes the transition details into this
        // intent. With FLAG_IMMUTABLE every event arrives empty and the app looks
        // broken for no visible reason.
        val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
        } else {
            PendingIntent.FLAG_UPDATE_CURRENT
        }
        return PendingIntent.getBroadcast(context, 0, intent, flags)
    }

    /**
     * Ask the server what to watch around a point, cache it, and register it.
     * Blocking: call from a worker thread or a receiver's goAsync() block.
     */
    fun refreshAround(context: Context, lat: Double, lng: Double): Result<FenceSet> =
        runCatching {
            val set = Api.fenceSet(context, lat, lng)
            FenceCache.save(context, set)
            register(context, set)
            set
        }.onFailure { Log.w(TAG, "refresh failed: ${it.message}") }

    /** Same, but finds out where it is first. Also blocking. */
    @SuppressLint("MissingPermission")
    fun refreshHere(context: Context): Result<FenceSet> {
        if (!hasLocationPermission(context)) {
            return Result.failure(IllegalStateException("no location permission"))
        }
        return runCatching {
            val client = LocationServices.getFusedLocationProviderClient(context)
            // Balanced, not high accuracy: a fence set is drawn around a
            // neighbourhood, so metres do not matter and battery does.
            val task = client.getCurrentLocation(Priority.PRIORITY_BALANCED_POWER_ACCURACY, null)
            val location = Tasks.await(task, 30, TimeUnit.SECONDS)
                ?: throw IllegalStateException("no location fix")
            location
        }.mapCatching { location ->
            refreshAround(context, location.latitude, location.longitude).getOrThrow()
        }.onFailure { Log.w(TAG, "refreshHere failed: ${it.message}") }
    }

    /** Re-register whatever is in the cache, without asking the server. */
    fun reregisterFromCache(context: Context): Boolean {
        val umbrella = FenceCache.umbrella(context) ?: return false
        val set = FenceSet(umbrella, FenceCache.fenceRadiusM(context), FenceCache.fences(context))
        return runCatching { register(context, set) }.isSuccess
    }

    @SuppressLint("MissingPermission")
    private fun register(context: Context, set: FenceSet) {
        if (!hasLocationPermission(context)) {
            throw IllegalStateException("no location permission")
        }
        val client = LocationServices.getGeofencingClient(context)

        // Replace wholesale. Adding by the same requestId would update in place,
        // but a place that dropped out of the set has to actually go.
        Tasks.await(client.removeGeofences(pendingIntent(context)), 15, TimeUnit.SECONDS)

        val geofences = mutableListOf<Geofence>()

        geofences += Geofence.Builder()
            .setRequestId(UMBRELLA_ID)
            .setCircularRegion(set.umbrella.lat, set.umbrella.lng, set.umbrella.radiusM)
            .setExpirationDuration(Geofence.NEVER_EXPIRE)
            .setTransitionTypes(Geofence.GEOFENCE_TRANSITION_EXIT)
            .setNotificationResponsiveness(Config.RESPONSIVENESS_MS)
            .build()

        set.fences.forEach { f ->
            geofences += Geofence.Builder()
                .setRequestId(f.placeId)
                .setCircularRegion(f.lat, f.lng, set.fenceRadiusM)
                .setExpirationDuration(Geofence.NEVER_EXPIRE)
                .setTransitionTypes(
                    Geofence.GEOFENCE_TRANSITION_ENTER or Geofence.GEOFENCE_TRANSITION_DWELL,
                )
                .setLoiteringDelay(Config.LOITERING_MS)
                .setNotificationResponsiveness(Config.RESPONSIVENESS_MS)
                .build()
        }

        val request = GeofencingRequest.Builder()
            // No alert for a fence you are already standing inside, including the
            // umbrella you were just given.
            .setInitialTrigger(0)
            .addGeofences(geofences)
            .build()

        Tasks.await(client.addGeofences(request, pendingIntent(context)), 15, TimeUnit.SECONDS)
        Log.i(TAG, "registered umbrella + ${set.fences.size} place fences")
    }
}
