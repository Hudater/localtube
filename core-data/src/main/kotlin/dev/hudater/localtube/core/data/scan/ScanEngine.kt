package dev.hudater.localtube.core.data.scan

import dev.hudater.localtube.core.data.db.ChannelDao
import dev.hudater.localtube.core.data.db.ChannelEntity
import dev.hudater.localtube.core.data.db.PlaylistDao
import dev.hudater.localtube.core.data.db.PlaylistEntity
import dev.hudater.localtube.core.data.db.VideoDao
import dev.hudater.localtube.core.data.db.VideoEntity
import dev.hudater.localtube.core.filesystem.FileKind
import dev.hudater.localtube.core.filesystem.FileKindClassifier
import dev.hudater.localtube.core.filesystem.RemoteFileEntry
import dev.hudater.localtube.core.filesystem.SourceFileSystem
import dev.hudater.localtube.core.metadata.MetadataParser
import dev.hudater.localtube.core.model.Source
import java.security.MessageDigest

/**
 * Scans one [Source], groups files by base name into video+sidecar
 * groups, parses metadata where available, and diffs the result against
 * what's already in Room — inserting new videos, updating changed ones,
 * and removing ones no longer present.
 *
 * Deliberately does NOT touch thumbnail generation for missing
 * thumbnails — see [dev.hudater.localtube.core.data.scan.ThumbnailFallbackWorker]
 * (app module schedules it) for that, kept separate because frame
 * extraction is slow and shouldn't block indexing.
 */
class ScanEngine(
    private val fileSystem: SourceFileSystem,
    private val metadataParser: MetadataParser,
    private val videoDao: VideoDao,
    private val channelDao: ChannelDao,
    private val playlistDao: PlaylistDao,
) {

    suspend fun scan(source: Source) {
        val allFiles = fileSystem.listFiles(source)
        val groups = groupByBaseName(allFiles)

        val existingFingerprints = videoDao.getKnownFileFingerprints(source.id.value)
            .associateBy { it.id }

        val videosToUpsert = mutableListOf<VideoEntity>()
        val channelsToUpsert = mutableMapOf<String, ChannelEntity>()
        val playlistsToUpsert = mutableMapOf<String, PlaylistEntity>()
        val seenIds = mutableSetOf<String>()

        for (group in groups) {
            val videoFile = group.video ?: continue // sidecar with no matching video file — skip

            val infoJsonText = group.infoJson?.let { entry ->
                runCatching {
                    fileSystem.openInputStream(source, entry.relativePath).use { it.readBytes().decodeToString() }
                }.getOrNull()
            }
            val parsed = infoJsonText?.let { metadataParser.parse(it) }

            val videoId = parsed?.platformVideoId
                ?: deriveIdFromPath(source.id.value, videoFile.relativePath)
            seenIds += videoId

            // Skip re-processing files whose size+mtime haven't changed
            // since the last scan — see docs/ARCHITECTURE.md, "Scan
            // diffing strategy", for why this uses size+mtime, not a hash.
            val existing = existingFingerprints[videoId]
            val unchanged = existing != null &&
                existing.fileSizeBytes == videoFile.sizeBytes &&
                existing.fileLastModifiedEpochMillis == videoFile.lastModifiedEpochMillis
            if (unchanged) continue

            val channelId = parsed?.channelId
            if (channelId != null && parsed.channelName != null) {
                channelsToUpsert[channelId] = ChannelEntity(
                    id = channelId,
                    name = parsed.channelName,
                    thumbnailPath = null, // channel avatar sourcing is a later enhancement — see docs/ARCHITECTURE.md open items
                )
            }

            val playlistId = parsed?.playlistId
            if (playlistId != null) {
                playlistsToUpsert[playlistId] = PlaylistEntity(
                    id = playlistId,
                    sourceId = source.id.value,
                    channelId = channelId,
                    name = parsed.playlistName ?: playlistId,
                    thumbnailPath = null, // defaults to first video's thumbnail at read time — see LibraryRepository/UI layer
                )
            }

            videosToUpsert += VideoEntity(
                id = videoId,
                sourceId = source.id.value,
                channelId = channelId,
                playlistId = playlistId,
                playlistIndex = parsed?.playlistIndex,
                filePath = videoFile.relativePath,
                title = parsed?.title ?: titleFromFilename(videoFile.relativePath),
                durationSeconds = parsed?.durationSeconds,
                thumbnailPath = group.thumbnail?.relativePath,
                addedAtEpochMillis = existing?.addedAtEpochMillis ?: System.currentTimeMillis(),
                fileSizeBytes = videoFile.sizeBytes,
                fileLastModifiedEpochMillis = videoFile.lastModifiedEpochMillis,
            )
        }

        val staleIds = existingFingerprints.keys - seenIds

        channelDao.upsertAll(channelsToUpsert.values.toList())
        playlistsToUpsert.values.forEach { playlistDao.upsert(it) }
        videoDao.upsertAll(videosToUpsert)
        if (staleIds.isNotEmpty()) {
            videoDao.deleteByIds(staleIds.toList())
        }
    }

    private fun groupByBaseName(files: List<RemoteFileEntry>): List<FileGroup> {
        return files.groupBy { FileKindClassifier.baseName(it.relativePath) }
            .map { (_, entries) ->
                FileGroup(
                    video = entries.firstOrNull { it.kind == FileKind.VIDEO },
                    infoJson = entries.firstOrNull { it.kind == FileKind.INFO_JSON },
                    thumbnail = entries.firstOrNull { it.kind == FileKind.THUMBNAIL },
                )
            }
    }

    private fun titleFromFilename(relativePath: String): String {
        val name = relativePath.substringAfterLast('/').substringBeforeLast('.')
        return name.replace('_', ' ').trim()
    }

    /**
     * Stable fallback ID when no info.json (or no `id` field in it) is
     * available: hash of source id + relative path. Stable across scans
     * as long as the file doesn't move, which is the best guarantee
     * available without metadata.
     */
    private fun deriveIdFromPath(sourceId: Long, relativePath: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
            .digest("$sourceId:$relativePath".toByteArray())
        return digest.joinToString("") { "%02x".format(it) }.take(32)
    }

    private data class FileGroup(
        val video: RemoteFileEntry?,
        val infoJson: RemoteFileEntry?,
        val thumbnail: RemoteFileEntry?,
    )
}
