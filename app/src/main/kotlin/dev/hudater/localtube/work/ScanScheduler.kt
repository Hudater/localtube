package dev.hudater.localtube.work

import android.content.Context
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.Constraints
import dev.hudater.localtube.core.data.scan.ScanWorker
import java.util.concurrent.TimeUnit

/**
 * Entry point for triggering scans, used by both a periodic background
 * schedule (set up once at app start) and manual pull-to-refresh in the
 * UI (see ui/home/HomeViewModel).
 */
object ScanScheduler {

    private val constraints = Constraints.Builder()
        .setRequiredNetworkType(NetworkType.CONNECTED) // needed for SMB sources; harmless no-op impact for LOCAL-only setups
        .build()

    fun schedulePeriodic(context: Context) {
        val request = PeriodicWorkRequestBuilder<ScanWorker>(6, TimeUnit.HOURS)
            .setConstraints(constraints)
            .build()

        WorkManager.getInstance(context).enqueueUniquePeriodicWork(
            ScanWorker.UNIQUE_WORK_NAME,
            ExistingPeriodicWorkPolicy.KEEP,
            request,
        )
    }

    fun scanNow(context: Context) {
        val request = OneTimeWorkRequestBuilder<ScanWorker>()
            .setConstraints(constraints)
            .build()

        WorkManager.getInstance(context).enqueueUniqueWork(
            "${ScanWorker.UNIQUE_WORK_NAME}_manual",
            ExistingWorkPolicy.REPLACE,
            request,
        )
    }
}
