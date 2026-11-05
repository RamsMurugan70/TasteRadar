package com.tasteradar.app

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.Worker
import androidx.work.WorkerParameters
import java.util.concurrent.TimeUnit

/**
 * Drains the feedback queue when there is a network to drain it over.
 *
 * WorkManager is doing the hard part: it waits for connectivity, backs off
 * between attempts, and survives both the app being killed and the phone being
 * rebooted. Writing that by hand is how you end up with a retry loop that
 * flattens the battery on a train.
 */
class FeedbackWorker(context: Context, params: WorkerParameters) : Worker(context, params) {

    override fun doWork(): Result =
        if (FeedbackQueue.flush(applicationContext)) Result.success() else Result.retry()

    companion object {
        private const val NAME = "feedback-flush"

        fun schedule(context: Context) {
            val request = OneTimeWorkRequestBuilder<FeedbackWorker>()
                .setConstraints(
                    Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build(),
                )
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
                .build()
            // REPLACE, not APPEND: one drain of the whole queue is all that is
            // ever needed, and queueing one job per tap would pile up offline.
            WorkManager.getInstance(context)
                .enqueueUniqueWork(NAME, ExistingWorkPolicy.REPLACE, request)
        }
    }
}
