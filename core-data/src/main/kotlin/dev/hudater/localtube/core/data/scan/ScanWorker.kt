package dev.hudater.localtube.core.data.scan

import android.content.Context
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import dev.hudater.localtube.core.data.repository.SourceRepository
import dev.hudater.localtube.core.filesystem.SourceUnavailableException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex

/**
 * Runs [ScanEngine.scan] for every source. Triggered by the periodic
 * schedule, on manual pull-to-refresh, and right after a source is added.
 *
 * Two failure axes are handled deliberately:
 *
 * 1. One source failing (revoked SAF grant, unreachable SMB share) does
 *    not abort the whole scan, but it MUST be recorded — the exception
 *    per-source aborts that source before any diffing (so it can never
 *    read as "empty folder", which is what once deleted a whole library),
 *    and the error text lands on the source row for the UI.
 *
 * 2. A scan already running is not restarted. Callers used to enqueue
 *    with ExistingWorkPolicy.REPLACE, which cancelled the in-flight walk
 *    of a large library repeatedly — that churn (plus the enqueue
 *    racing itself at app start) is why scans "never finished". Now
 *    enqueues use KEEP, and additionally a mutex makes a second
 *    concurrent ScanWorker a polite no-op rather than a race on Room.
 */
class ScanWorker(
    appContext: Context,
    params: WorkerParameters,
    private val sourceRepository: SourceRepository,
    private val scanEngineFactory: (sourceType: dev.hudater.localtube.core.model.SourceType) -> ScanEngine,
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        if (!scanMutex.tryLock()) {
            Log.i(TAG, "Scan already in progress — skipping this request")
            return Result.success()
        }
        try {
            return scanAllSources()
        } finally {
            scanMutex.unlock()
        }
    }

    private suspend fun scanAllSources(): Result {
        val sources = sourceRepository.observeAllSources().first().filter { !it.hidden }
        Log.i(TAG, "Scan starting: ${sources.size} source(s)")

        var anyFailure = false
        for (source in sources) {
            val engine = scanEngineFactory(source.type)
            var resolvedCount = 0
            try {
                // setProgress surfaces live resolution counts; the home
                // screen reads it as "Scanning N of M".
                val onProgress: suspend (done: Int, total: Int) -> Unit = { done, total ->
                    resolvedCount = done
                    setProgress(scanProgress(done, total, sourceId = source.id.value))
                }
                val written = engine.scan(source, onProgress)
                sourceRepository.setScanError(source.id, null)
                Log.i(TAG, "Scanned '${source.label}': resolved $resolvedCount group(s), $written video row(s) written")
            } catch (e: CancellationException) {
                // Preempted (worker cancelled). Do not record as failure —
                // the source isn't broken, the run was.
                Log.w(TAG, "Scan of '${source.label}' cancelled mid-run", e)
                throw e
            } catch (e: SourceUnavailableException) {
                anyFailure = true
                sourceRepository.setScanError(source.id, e.message)
                Log.e(TAG, "Source unavailable: '${source.label}' (${source.path})", e)
            } catch (e: Exception) {
                anyFailure = true
                sourceRepository.setScanError(source.id, "${e.javaClass.simpleName}: ${e.message}")
                Log.e(TAG, "Scan failed for '${source.label}' (${source.type}, ${source.path})", e)
            }
        }

        // Kick off the enrichment passes regardless of scan outcome — new
        // videos show title-first, thumbnails and channel icons arrive
        // after. The icon pass depends on the channel rows the scan above
        // just wrote, and both are best-effort background work.
        WorkManager.getInstance(applicationContext).enqueueUniqueWork(
            ThumbnailFallbackWorker.UNIQUE_WORK_NAME,
            ExistingWorkPolicy.REPLACE,
            OneTimeWorkRequestBuilder<ThumbnailFallbackWorker>().build(),
        )
        WorkManager.getInstance(applicationContext).enqueueUniqueWork(
            ChannelIconWorker.UNIQUE_WORK_NAME,
            ExistingWorkPolicy.REPLACE,
            OneTimeWorkRequestBuilder<ChannelIconWorker>().build(),
        )

        return if (anyFailure) Result.retry() else Result.success()
    }

    /** Progress payload consumed by the home screen. */
    private fun scanProgress(done: Int, total: Int, sourceId: Long): Data = workDataOf(
        KEY_DONE to done,
        KEY_TOTAL to total,
        KEY_SOURCE_ID to sourceId,
    )

    companion object {
        const val UNIQUE_WORK_NAME = "localtube_scan"
        const val KEY_DONE = "groups_resolved"
        const val KEY_TOTAL = "groups_total"
        const val KEY_SOURCE_ID = "source_id"
        private const val TAG = "LocalTubeScan"

        /** One scan at a time across every trigger (periodic + manual). */
        private val scanMutex = Mutex()
    }
}
