package com.tasteradar.spike

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * Android drops every geofence on reboot, silently. Without this receiver the
 * app looks fine and simply never fires again - which is the single most common
 * way a geofencing app dies in the wild.
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        FenceLog.note(context, "boot - re-registering")
        Fences.registerAll(context) { }
    }
}
