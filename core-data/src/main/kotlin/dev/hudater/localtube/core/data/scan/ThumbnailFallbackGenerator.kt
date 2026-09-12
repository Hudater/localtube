package dev.hudater.localtube.core.data.scan

import android.content.Context
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import dev.hudater.localtube.core.data.db.VideoDao
import dev.hudater.localtube.core.filesystem.SourceFileSystem
import dev.hudater.localtube.core.model.Source
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream

/**
 * For videos with no sidecar thumbnail (see [dev.hudater.localtube.core.filesystem.FileKind.THUMBNAIL]
 * absent in their [dev.hudater.localtube.core.data.scan.ScanEngine] file
 * group), grabs a single frame and caches it to app-internal storage.
 * The same retriever pass doubles as the duration fallback for rows
 * indexed without one — bare downloads embed duration metadata in the
 * container, so no sidecar is needed to read it.
 *
 * Deliberately NOT part of [ScanEngine]: frame extraction requires
 * opening the video file itself (slow, especially over SMB), so it runs
 * as a lower-priority background pass that can lag behind indexing
 * without blocking the feed from showing (title-only) results.
 */
class ThumbnailFallbackGenerator(
    private val appContext: Context,
    private val fileSystem: SourceFileSystem,
    private val videoDao: VideoDao,
) {
    private val cacheDir: File by lazy {
        File(appContext.cacheDir, "thumbnails").apply { mkdirs() }
    }

    /**
     * @return the cache file path if a thumbnail was generated, or null
     * if extraction failed (e.g. corrupt file, unreachable SMB source).
     * Failure here should never crash a scan — see [ScanWorker].
     */
    suspend fun generateFor(source: Source, video: dev.hudater.localtube.core.model.Video): String? =
        withContext(Dispatchers.IO) {
            if (video.thumbnailPath != null) return@withContext video.thumbnailPath

            val outputFile = File(cacheDir, "${video.id.value}.jpg")
            if (outputFile.exists()) return@withContext outputFile.absolutePath

            val retriever = MediaMetadataRetriever()
            try {
                fileSystem.openRandomAccess(source, video.filePath).use { handle ->
                    retriever.setDataSource(RandomAccessMediaDataSource(handle))

                    // Duration fallback: yt-dlp embeds the duration in
                    // the container metadata, so bare downloads (no
                    // info.json) can still get their duration chip filled
                    // here. Reported as a string in milliseconds;
                    // persisted as whole seconds.
                    if (video.durationSeconds == null) {
                        retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                            ?.toLongOrNull()
                            ?.takeIf { it > 0L }
                            ?.let { videoDao.updateDurationSeconds(video.id.value, it / 1000L) }
                    }

                    // 1s in, not frame 0 — frame 0 on many downloads is a
                    // black/blank frame.
                    val frame = retriever.getFrameAtTime(
                        1_000_000L,
                        MediaMetadataRetriever.OPTION_CLOSEST_SYNC,
                    ) ?: return@withContext null

                    FileOutputStream(outputFile).use { out ->
                        frame.compress(Bitmap.CompressFormat.JPEG, 85, out)
                    }
                }
                outputFile.absolutePath
            } catch (_: Exception) {
                null
            } finally {
                retriever.release()
            }
        }
}
