package com.tasteradar.app

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import kotlin.concurrent.thread

/**
 * Android drops every geofence on reboot, silently. Without this the app looks
 * perfectly healthy and simply never fires again.
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        val pending = goAsync()
        thread {
            try {
                val ok = Fences.reregisterFromCache(context)
                Log.i("BootReceiver", "re-registered from cache: $ok")
                RefreshWorker.schedule(context)
            } finally {
                pending.finish()
            }
        }
    }
}
