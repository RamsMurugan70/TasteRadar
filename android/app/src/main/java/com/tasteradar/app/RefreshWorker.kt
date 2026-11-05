package com.tasteradar.app

import android.content.Context
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.Worker
import androidx.work.WorkerParameters
import java.util.concurrent.TimeUnit

/**
 * The safety net. Every six hours it re-registers the fences and, if the cached
 * set has gone stale, asks for a fresh one.
 *
 * This exists because OEM battery managers kill background work and do not say
 * so. On a stock Pixel it is redundant; on a Xiaomi or OnePlus it is the reason
 * the app still works on day three.
 */
class RefreshWorker(context: Context, params: WorkerParameters) : Worker(context, params) {

    override fun doWork(): Result {
        val context = applicationContext

        // Belt and braces: FeedbackWorker is the primary route, but if its
        // backoff has given up this catches whatever is left.
        FeedbackQueue.flush(context)

        if (!Fences.hasLocationPermission(context)) return Result.success()

        Fences.reregisterFromCache(context)

        val age = System.currentTimeMillis() - FenceCache.savedAt(context)
        if (age > STALE_AFTER_MS || FenceCache.umbrella(context) == null) {
            return if (Fences.refreshHere(context).isSuccess) Result.success() else Result.retry()
        }
        return Result.success()
    }

    companion object {
        private const val NAME = "fence-refresh"
        private const val STALE_AFTER_MS = 12 * 60 * 60 * 1000L

        fun schedule(context: Context) {
            val request = PeriodicWorkRequestBuilder<RefreshWorker>(6, TimeUnit.HOURS)
                .setConstraints(
                    Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build(),
                )
                .build()
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                NAME,
                ExistingPeriodicWorkPolicy.KEEP,
                request,
            )
        }
    }
}
