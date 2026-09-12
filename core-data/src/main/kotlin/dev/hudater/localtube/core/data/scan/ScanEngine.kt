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
import dev.hudater.localtube.core.metadata.ParsedVideoMetadata
import dev.hudater.localtube.core.model.Source
import java.security.MessageDigest

/**
 * Scans one [Source], groups files by base name into video+sidecar
 * groups, parses metadata where available, and diffs the result against
 * what's already in Room — inserting new videos, updating changed ones,
 * and removing ones no longer present.
 *
 * Folder structure is the library's organization: a video file nested in
 * a subdirectory belongs to that directory-as-playlist ("macOS
 * internals/foo.mp4" → playlist "macOS internals") while root-level
 * files stay standalone, which is what the Home feed shows (see
 * VideoDao.observeFeed). A `.description` sidecar (yt-dlp
 * --write-description) is read into the video row when present.
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

        // Directory-as-playlist ordering: pure filename math, so it runs
        // on every scan like the title heuristic below. A video's
        // playlistIndex is its alphabetical position among ALL of its
        // directory's videos (unchanged ones included) — adding or
        // renaming one file re-orders the rest through the diff pass
        // instead of leaving stale positions behind.
        val playlistIndexByPath = allFiles
            .filter { it.kind == FileKind.VIDEO }
            .groupBy { directorySegmentOf(it.relativePath) }
            .filterKeys { it != null }
            .flatMap { (_, videos) ->
                videos
                    .sortedBy { it.relativePath.substringAfterLast('/') }
                    .mapIndexed { index, entry -> entry.relativePath to index }
            }
            .toMap()

        // Resolve every group once: sidecar reads (the only file-content
        // reads in a scan) plus cheap name-based derivation. The diff
        // pass further down decides what actually gets written.
        val resolved = groups.mapNotNull { group ->
            val videoFile = group.video ?: return@mapNotNull null // sidecar with no matching video file — skip

            val infoJsonText = group.infoJson?.let { entry ->
                runCatching {
                    fileSystem.openInputStream(source, entry.relativePath).use { it.readBytes().decodeToString() }
                }.getOrNull()
            }
            val parsed = infoJsonText?.let { metadataParser.parse(it) }

            // yt-dlp --write-description writes "<basename>.description"
            // next to the video: UTF-8 text, read best-effort and
            // truncated defensively — descriptions are display text.
            val description = group.description?.let { entry ->
                runCatching {
                    fileSystem.openInputStream(source, entry.relativePath).use { it.readBytes().decodeToString() }
                }.getOrNull()
            }?.take(DESCRIPTION_MAX_CHARS)

            val videoId = parsed?.platformVideoId
                ?: deriveIdFromPath(source.id.value, videoFile.relativePath)

            // Display fields: metadata wins; without a sidecar, fall back
            // to the yt-dlp output filename convention "Author - Title.ext"
            // so bare download folders still get channels and clean titles.
            val baseName = titleFromFilename(videoFile.relativePath)
            val (channelId, channelName, title) = if (parsed != null) {
                ParsedDisplay(parsed.channelId, parsed.channelName, parsed.title ?: baseName)
            } else {
                authorTitleFromFilename(baseName)
            }

            // On-disk folder structure wins over info.json playlist
            // references: a nested video is a member of its directory's
            // playlist; only a root-level video falls back to an
            // info.json playlist — never both.
            val directorySegment = directorySegmentOf(videoFile.relativePath)
            val playlistId = directorySegment
                ?.let { derivedPlaylistId(source.id.value, it) }
                ?: parsed?.playlistId

            ResolvedVideo(
                videoFile = videoFile,
                thumbnailEntry = group.thumbnail,
                id = videoId,
                parsed = parsed,
                channelId = channelId,
                channelName = channelName,
                title = title,
                description = description,
                playlistId = playlistId,
                playlistName = directorySegment ?: parsed?.playlistName,
                playlistIndex = if (directorySegment != null) {
                    playlistIndexByPath[videoFile.relativePath]
                } else {
                    parsed?.playlistIndex
                },
            )
        }

        // Playlist rows are (re)derived every scan for every playlist
        // with at least one video this scan — still cheap filename math,
        // and it lets a playlist's channel follow file moves and renames.
        // channelId is the majority vote among the playlist's videos, or
        // null when none of them resolves to a channel.
        val playlistNames = mutableMapOf<String, String>()
        val channelVotesByPlaylist = mutableMapOf<String, MutableMap<String, Int>>()
        for (video in resolved) {
            val playlistId = video.playlistId ?: continue
            if (!playlistNames.containsKey(playlistId)) {
                playlistNames[playlistId] = video.playlistName ?: playlistId
            }
            val channelId = video.channelId ?: continue
            val votes = channelVotesByPlaylist.getOrPut(playlistId) { mutableMapOf() }
            votes[channelId] = (votes[channelId] ?: 0) + 1
        }
        val majorityChannelByPlaylist = channelVotesByPlaylist.mapValues { (_, votes) ->
            votes.maxByOrNull { it.value }?.key
        }

        val videosToUpsert = mutableListOf<VideoEntity>()
        val channelsToUpsert = mutableMapOf<String, ChannelEntity>()
        val seenIds = mutableSetOf<String>()

        for (video in resolved) {
            seenIds += video.id

            // Skip re-processing files whose size+mtime haven't changed
            // since the last scan — see docs/ARCHITECTURE.md, "Scan
            // diffing strategy", for why this uses size+mtime, not a hash.
            // Display fields (title/channel), playlist placement, and the
            // sidecar description are re-derived cheaply every scan so
            // heuristic improvements, file moves, and sidecar edits apply
            // to existing rows too; the fingerprint carries those fields
            // so a change in them re-writes the row.
            val existing = existingFingerprints[video.id]
            val contentUnchanged = existing != null &&
                existing.fileSizeBytes == video.videoFile.sizeBytes &&
                existing.fileLastModifiedEpochMillis == video.videoFile.lastModifiedEpochMillis
            val displayUnchanged = existing != null &&
                existing.title == video.title && existing.channelId == video.channelId &&
                existing.playlistId == video.playlistId &&
                existing.playlistIndex == video.playlistIndex &&
                existing.description == video.description
            if (contentUnchanged && displayUnchanged) continue

            val channelId = video.channelId
            val channelName = video.channelName
            if (channelId != null && channelName != null) {
                // Carry forward an avatar already fetched by
                // ChannelIconWorker — a blind REPLACE would wipe it on
                // every scan and re-trigger the network pass.
                val iconPath = channelDao.getById(channelId)?.iconPath
                channelsToUpsert[channelId] = ChannelEntity(
                    id = channelId,
                    name = channelName,
                    thumbnailPath = null, // channel avatar sourcing is a later enhancement — see docs/ARCHITECTURE.md open items
                    iconPath = iconPath,
                )
            }

            videosToUpsert += VideoEntity(
                id = video.id,
                sourceId = source.id.value,
                channelId = video.channelId,
                playlistId = video.playlistId,
                playlistIndex = video.playlistIndex,
                filePath = video.videoFile.relativePath,
                title = video.title,
                durationSeconds = video.parsed?.durationSeconds,
                thumbnailPath = video.thumbnailEntry?.relativePath,
                description = video.description,
                addedAtEpochMillis = existing?.addedAtEpochMillis ?: System.currentTimeMillis(),
                fileSizeBytes = video.videoFile.sizeBytes,
                fileLastModifiedEpochMillis = video.videoFile.lastModifiedEpochMillis,
            )
        }

        val playlistUpserts = playlistNames.map { (playlistId, name) ->
            PlaylistEntity(
                id = playlistId,
                sourceId = source.id.value,
                channelId = majorityChannelByPlaylist[playlistId],
                name = name,
                thumbnailPath = null, // defaults to first video's thumbnail at read time — see LibraryRepository/UI layer
            )
        }

        val staleIds = existingFingerprints.keys - seenIds

        channelDao.upsertAll(channelsToUpsert.values.toList())
        playlistUpserts.forEach { playlistDao.upsert(it) }
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
                    description = entries.firstOrNull { it.kind == FileKind.DESCRIPTION },
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
     * First path segment of a nested file, or null for a root-level
     * file: "macOS internals/foo.mp4" → "macOS internals", "foo.mp4" →
     * null. The first segment is the playlist a nested video belongs to.
     */
    private fun directorySegmentOf(relativePath: String): String? {
        val segment = relativePath.substringBefore('/', missingDelimiterValue = "")
        return segment.ifEmpty { null }
    }

    /**
     * Stable id for a directory-derived playlist. The segment is
     * sanitized (lowercase, whitespace → dash, mirroring the channel id
     * heuristic) so the id stays stable across scans; the human-readable
     * form is kept verbatim as the row's name.
     */
    private fun derivedPlaylistId(sourceId: Long, segment: String): String =
        "dir:$sourceId:" + segment.lowercase().replace(Regex("\\s+"), "-")

    /**
     * One video file with everything derived from names and sidecars,
     * before the diff pass decides whether any of it gets written.
     */
    private data class ResolvedVideo(
        val videoFile: RemoteFileEntry,
        val thumbnailEntry: RemoteFileEntry?,
        val id: String,
        val parsed: ParsedVideoMetadata?,
        val channelId: String?,
        val channelName: String?,
        val title: String,
        val description: String?,
        val playlistId: String?,
        val playlistName: String?,
        val playlistIndex: Int?,
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
        val description: RemoteFileEntry?,
    )

    private companion object {
        /** Sidecar descriptions are stored truncated — display text, not search input. */
        const val DESCRIPTION_MAX_CHARS = 5000
    }
}
