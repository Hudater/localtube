package dev.hudater.localtube.core.data.scan

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import dev.hudater.localtube.core.data.repository.SourceRepository
import kotlinx.coroutines.flow.first

/**
 * Runs [ScanEngine.scan] for every source. Triggered on a periodic
 * schedule and on manual pull-to-refresh (see :app's di/WorkScheduler).
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

        var anyFailure = false
        for (source in sources) {
            try {
                scanEngineFactory(source.type).scan(source)
            } catch (e: Exception) {
                anyFailure = true
                // Intentionally swallowed per-source: one unreachable SMB
                // share should not block scanning the rest. Surfacing
                // per-source failure state to the UI is tracked as an
                // open item in docs/ARCHITECTURE.md.
            }
        }

        return if (anyFailure) Result.retry() else Result.success()
    }

    companion object {
        const val UNIQUE_WORK_NAME = "localtube_scan"
    }
}
