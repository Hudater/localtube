package dev.hudater.localtube.core.data.scan

import android.content.Context
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import dev.hudater.localtube.core.data.db.VideoDao
import dev.hudater.localtube.core.data.db.toDomain
import dev.hudater.localtube.core.model.SourceId
import dev.hudater.localtube.core.model.SourceType
import dev.hudater.localtube.core.data.repository.SourceRepository

/**
 * Background pass that generates thumbnails for videos indexed without a
 * sidecar image (bare yt-dlp downloads): extracts one frame via
 * [ThumbnailFallbackGenerator] and caches it app-internally. Scheduled
 * after every scan pass (see [ScanWorker]) — best-effort, never blocks
 * the feed from showing title-only rows first.
 */
class ThumbnailFallbackWorker(
    appContext: Context,
    params: WorkerParameters,
    private val sourceRepository: SourceRepository,
    private val generatorFactory: (sourceType: SourceType) -> ThumbnailFallbackGenerator,
    private val videoDao: VideoDao,
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val pending = videoDao.videosWithoutThumbnail()
        if (pending.isEmpty()) return Result.success()

        Log.i(TAG, "Thumbnail pass: ${pending.size} video(s) without a thumbnail")
        val bySource = pending.groupBy { it.sourceId }
        var generated = 0
        var failed = 0

        for ((sourceId, videos) in bySource) {
            val source = sourceRepository.getSource(SourceId(sourceId))
            if (source == null) {
                failed += videos.size
                continue
            }
            try {
                val generator = generatorFactory(source.type)
                for (entity in videos) {
                    val path = generator.generateFor(source, entity.toDomain())
                    if (path != null) {
                        videoDao.updateThumbnailPath(entity.id, path)
                        generated++
                    } else {
                        failed++
                    }
                }
            } catch (e: Exception) {
                failed += videos.size
                Log.e(TAG, "Thumbnail pass failed for '${source.label}'", e)
            }
        }

        Log.i(TAG, "Thumbnail pass done: $generated generated, $failed failed")
        // Always success: this is a best-effort enrichment pass. Failed
        // items stay without a thumbnail and are retried on the next scan
        // (ScanWorker re-enqueues this worker every time).
        return Result.success()
    }

    companion object {
        const val UNIQUE_WORK_NAME = "localtube_thumbnails"
        private const val TAG = "LocalTubeScan"
    }
}
