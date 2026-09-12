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

    /** @return the number of video groups written for this source. */
    suspend fun scan(source: Source): Int {
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

            // Display fields: metadata wins; without a sidecar, fall back
            // to the yt-dlp output filename convention "Author - Title.ext"
            // so bare download folders still get channels and clean titles.
            val baseName = titleFromFilename(videoFile.relativePath)
            val (channelId, channelName, title) = if (parsed != null) {
                ParsedDisplay(parsed.channelId, parsed.channelName, parsed.title ?: baseName)
            } else {
                authorTitleFromFilename(baseName)
            }

            // Skip re-processing files whose size+mtime haven't changed
            // since the last scan — see docs/ARCHITECTURE.md, "Scan
            // diffing strategy", for why this uses size+mtime, not a hash.
            // Display fields (title/channel) are re-derived cheaply every
            // scan so heuristic improvements apply to existing rows too.
            val existing = existingFingerprints[videoId]
            val contentUnchanged = existing != null &&
                existing.fileSizeBytes == videoFile.sizeBytes &&
                existing.fileLastModifiedEpochMillis == videoFile.lastModifiedEpochMillis
            val displayUnchanged = existing != null &&
                existing.title == title && existing.channelId == channelId
            if (contentUnchanged && displayUnchanged) continue

            if (channelId != null && channelName != null) {
                channelsToUpsert[channelId] = ChannelEntity(
                    id = channelId,
                    name = channelName,
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
                title = title,
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
        return videosToUpsert.size
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
     * Splits a yt-dlp style filename "Author - Title" into a stable
     * channel id, the channel name, and the remaining title. No match →
     * no channel, whole name as title.
     */
    private fun authorTitleFromFilename(baseName: String): ParsedDisplay {
        val idx = baseName.indexOf(" - ")
        if (idx <= 0 || idx >= baseName.length - 3) return ParsedDisplay(null, null, baseName)
        val author = baseName.substring(0, idx).trim()
        val title = baseName.substring(idx + 3).trim()
        if (author.isEmpty() || title.isEmpty()) return ParsedDisplay(null, null, baseName)
        return ParsedDisplay(
            channelId = "fname:" + author.lowercase().replace(Regex("\\s+"), "-"),
            channelName = author,
            title = title,
        )
    }

    private data class ParsedDisplay(
        val channelId: String?,
        val channelName: String?,
        val title: String,
    )

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
