package dev.hudater.localtube.work

import android.content.Context
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import dev.hudater.localtube.core.data.scan.ScanWorker
import java.util.concurrent.TimeUnit

/**
 * Entry point for triggering scans, used by a periodic background
 * schedule (set up once at app start) and manual pull-to-refresh in the
 * UI (see ui/home/HomeViewModel).
 *
 * Design notes:
 * - NO network constraint. It used to be required for unreliably-placed
 *   SMB sources but made a LOCAL-only scan silently queue forever
 *   whenever the device was offline, which the user read as "scanning
 *   broken". An unreachable share is now a per-source error instead
 *   (see docs/ARCHITECTURE.md, "Scan failure vs empty folder").
 * - KEEP, not REPLACE: REPLACE cancelled the in-flight walk of a large
 *   library every time someone pulled to refresh again, which made
 *   long scans never finish. A manual refresh while a scan runs just
 *   no-ops now, and the in-flight worker refuses duplicates itself
 *   (ScanWorker's single-flight mutex) since periodic and manual use
 *   different unique names.
 */
object ScanScheduler {

    /** Unique name for one-shot (manual/add) scans — observed by the home screen. */
    const val MANUAL_UNIQUE_WORK_NAME = "localtube_scan_manual"

    fun schedulePeriodic(context: Context) {
        val request = PeriodicWorkRequestBuilder<ScanWorker>(6, TimeUnit.HOURS)
            .build()

        WorkManager.getInstance(context).enqueueUniquePeriodicWork(
            ScanWorker.UNIQUE_WORK_NAME,
            ExistingPeriodicWorkPolicy.KEEP,
            request,
        )
    }

    /**
     * Enqueues a one-shot scan. Returns the request id so callers can
     * observe completion; note that under KEEP the returned request may
     * be discarded in favor of an already-running scan — the home
     * screen therefore observes by [MANUAL_UNIQUE_WORK_NAME], not by
     * this id.
     */
    fun scanNow(context: Context): java.util.UUID {
        val request = OneTimeWorkRequestBuilder<ScanWorker>().build()

        WorkManager.getInstance(context).enqueueUniqueWork(
            MANUAL_UNIQUE_WORK_NAME,
            ExistingWorkPolicy.KEEP,
            request,
        )
        return request.id
    }
}
