package dev.hudater.localtube.core.data.scan

import android.content.Context
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import dev.hudater.localtube.core.data.repository.SourceRepository
import kotlinx.coroutines.flow.first

/**
 * Runs [ScanEngine.scan] for every source. Triggered on a periodic
 * schedule, on manual pull-to-refresh, and right after a source is added.
 *
 * A single source failing (e.g. an unreachable SMB share) does not abort
 * the whole scan — see docs/ARCHITECTURE.md, "SMB source unavailable" —
 * so each source is scanned in its own try/catch.
 */
class ScanWorker(
    appContext: Context,
    params: WorkerParameters,
    private val sourceRepository: SourceRepository,
    private val scanEngineFactory: (sourceType: dev.hudater.localtube.core.model.SourceType) -> ScanEngine,
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val sources = sourceRepository.observeAllSources().first().filter { !it.hidden }
        Log.i(TAG, "Scan starting: ${sources.size} source(s)")

        var anyFailure = false
        for (source in sources) {
            try {
                val upserted = scanEngineFactory(source.type).scan(source)
                Log.i(TAG, "Scanned '${source.label}': $upserted video group(s) written")
            } catch (e: Exception) {
                anyFailure = true
                // One unreachable SMB share should not block scanning the
                // rest, but the failure must be visible — silent zero-row
                // scans are indistinguishable from an empty folder.
                Log.e(TAG, "Scan failed for '${source.label}' (${source.type}, ${source.path})", e)
            }
        }

        return if (anyFailure) Result.retry() else Result.success()
    }

    companion object {
        const val UNIQUE_WORK_NAME = "localtube_scan"
        private const val TAG = "LocalTubeScan"
    }
}
