package dev.hudater.localtube.work

import android.content.Context
import androidx.work.WorkerFactory
import androidx.work.WorkerParameters
import dev.hudater.localtube.core.data.scan.ScanWorker
import dev.hudater.localtube.di.AppContainer

/**
 * Wires [ScanWorker]'s extra constructor parameters (repository + scan
 * engine factory) to [AppContainer], since WorkManager's default
 * reflection-based instantiation only supports a no-arg constructor.
 *
 * Add a case here for any future Worker that needs container
 * dependencies (e.g. ThumbnailFallbackWorker).
 */
class LocalTubeWorkerFactory(
    private val container: AppContainer,
) : WorkerFactory() {

    override fun createWorker(
        appContext: Context,
        workerClassName: String,
        workerParameters: WorkerParameters,
    ) = when (workerClassName) {
        ScanWorker::class.java.name -> ScanWorker(
            appContext = appContext,
            params = workerParameters,
            sourceRepository = container.sourceRepository,
            scanEngineFactory = { sourceType -> container.scanEngineFor(sourceType) },
        )
        else -> null // fall back to WorkManager's default factory for anything else
    }
}
