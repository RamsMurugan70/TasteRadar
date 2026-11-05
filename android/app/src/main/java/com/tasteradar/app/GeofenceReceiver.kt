package com.tasteradar.app

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.location.Location
import android.util.Log
import com.google.android.gms.location.Geofence
import com.google.android.gms.location.GeofencingEvent
import kotlin.concurrent.thread

/**
 * Every geofence crossing arrives here.
 *
 * Two jobs, and only one of them touches the network:
 *   umbrella EXIT  -> ask the server for a new set (background, ~10s budget)
 *   place ENTER    -> decide locally whether to interrupt, and interrupt
 *
 * The alert path reads the cache and nothing else, which is why it works in a
 * basement. ENTER tries first; DWELL tries again, which is what rescues the case
 * where you drove past, the speed gate suppressed it, and then you parked.
 */
class GeofenceReceiver : BroadcastReceiver() {

    private companion object {
        const val TAG = "GeofenceReceiver"
    }

    override fun onReceive(context: Context, intent: Intent) {
        val event = GeofencingEvent.fromIntent(intent)
        if (event == null) {
            Log.w(TAG, "null event - is the PendingIntent MUTABLE?")
            return
        }
        if (event.hasError()) {
            Log.w(TAG, "geofence error ${event.errorCode}")
            return
        }

        val transition = event.geofenceTransition
        val triggered = event.triggeringGeofences.orEmpty().map { it.requestId }
        val location = event.triggeringLocation
        Log.i(TAG, "transition=$transition ids=$triggered")

        if (triggered.contains(Fences.UMBRELLA_ID) &&
            transition == Geofence.GEOFENCE_TRANSITION_EXIT
        ) {
            redrawFenceSet(context, location)
        }

        val places = triggered.filter { it != Fences.UMBRELLA_ID }
        if (places.isNotEmpty() &&
            (transition == Geofence.GEOFENCE_TRANSITION_ENTER ||
                transition == Geofence.GEOFENCE_TRANSITION_DWELL)
        ) {
            places.forEach { placeId -> considerAlert(context, placeId, location) }
        }
    }

    /** You left the umbrella, so the set is stale. Network, hence goAsync. */
    private fun redrawFenceSet(context: Context, location: Location?) {
        val pending = goAsync()
        thread {
            try {
                val result = if (location != null) {
                    Fences.refreshAround(context, location.latitude, location.longitude)
                } else {
                    Fences.refreshHere(context)
                }
                result.onSuccess { Log.i(TAG, "redrew: ${it.fences.size} fences") }
            } finally {
                pending.finish()
            }
        }
    }

    /**
     * The whole alert decision, from cache. No network, so this completes inside
     * the receiver's budget and works offline.
     */
    private fun considerAlert(context: Context, placeId: String, location: Location?) {
        val fence = FenceCache.fenceById(context, placeId)
        if (fence == null) {
            Log.w(TAG, "crossed $placeId but it is not in the cache")
            return
        }

        val verdict = Gates.check(
            context = context,
            speedMs = location?.speed,
            hasSpeed = location?.hasSpeed() == true,
            hours = fence.hours,
        )
        if (verdict is Gates.Verdict.Suppress) {
            Log.i(TAG, "suppressed ${fence.name}: ${verdict.because}")
            return
        }

        val distance = location?.let {
            FloatArray(1).also { out ->
                Location.distanceBetween(it.latitude, it.longitude, fence.lat, fence.lng, out)
            }[0].toInt()
        }

        Notifier.show(context, fence, distance)
        FenceCache.recordAlert(context)

        // Tell the server afterwards. If this fails the alert still happened;
        // the cooldown will simply be a little more generous than intended.
        val pending = goAsync()
        thread {
            try {
                val id = Api.postAlert(context, fence, location?.latitude, location?.longitude)
                FenceCache.putAlertId(context, placeId, id)
            } catch (e: Exception) {
                Log.w(TAG, "could not report the alert: ${e.message}")
            } finally {
                pending.finish()
            }
        }
    }
}
