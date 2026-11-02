package dev.hudater.localtube.core.data.scan

import dev.hudater.localtube.core.data.db.ChannelDao
import dev.hudater.localtube.core.data.db.ChannelEntity
import dev.hudater.localtube.core.data.db.PlaylistDao
import dev.hudater.localtube.core.data.db.PlaylistEntity
import dev.hudater.localtube.core.data.db.VideoDao
import dev.hudater.localtube.core.data.db.VideoEntity
import dev.hudater.localtube.core.data.db.VideoFingerprint
import dev.hudater.localtube.core.filesystem.FileKind
import dev.hudater.localtube.core.filesystem.FileKindClassifier
import dev.hudater.localtube.core.filesystem.RemoteFileEntry
import dev.hudater.localtube.core.filesystem.SourceFileSystem
import dev.hudater.localtube.core.filesystem.SourceUnavailableException
import dev.hudater.localtube.core.metadata.MetadataParser
import dev.hudater.localtube.core.metadata.ParsedVideoMetadata
import dev.hudater.localtube.core.model.Source
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

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
 * COST SHAPE — the previous implementation re-read every group's
 * .info.json and .description on EVERY scan before fingerprinting; at
 * SAF/FUSE round-trip speeds (~1 read/sec) that made a 966-video
 * library take ~35 minutes per scan with zero user-visible progress.
 * Three fixes live here:
 *  1. Sidecar fingerprints (size+mtime per sidecar, stamped on the row
 *     at scan time) gate the reads: a sidecar whose fingerprint matches
 *     is never re-read — its content already lives on the row (id,
 *     title, channel, description, duration).
 *  2. A changed video file with UNCHANGED sidecars doesn't re-read
 *     either: row identity and derived fields carry from the previous
 *     row, only file fingerprints are replaced.
 *  3. The remaining reads run parallel (bounded) and report progress
 *     via [onProgress] so the UI shows a real counter.
 *
 * Rows written before the sidecar-fingerprint columns existed (v2
 * schema) have null fingerprints and get exactly one full re-read to
 * acquire them.
 *
 * A listing that THROWS aborts before any diffing: "unreadable source"
 * (revoked SAF grant, dead SMB share) surfaces as
 * [SourceUnavailableException] and must NEVER be interpreted as "empty
 * folder" — that interpretation is what once deleted an entire library
 * on permission revocation.
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

    /**
     * @param onProgress invoked with (groupsResolved, totalGroups) as
     *   resolution advances — throttled (see PROGRESS_EVERY) with one
     *   final call. Consumed by ScanWorker → WorkManager setProgress.
     * @return the number of video rows written/updated for this source.
     */
    suspend fun scan(
        source: Source,
        onProgress: suspend (done: Int, total: Int) -> Unit = { _, _ -> },
    ): Int {
        val allFiles = try {
            fileSystem.listFiles(source)
        } catch (t: Throwable) {
            if (t is SourceUnavailableException) throw t
            throw SourceUnavailableException(
                "Listing '${source.label}' failed: ${t.message ?: t.javaClass.simpleName}",
                t,
            )
        }
        val groups = groupByBaseName(allFiles)
        val totalGroups = groups.count { it.video != null }

        val existingFingerprints = videoDao.getKnownFileFingerprints(source.id.value)
            .associateBy { it.id }
        // Path → previous row. Row identity for unchanged/re-downloaded
        // files resolves from here instead of re-reading info.json.
        val existingByPath = existingFingerprints.values.associateBy { it.filePath }

        // Directory-as-playlist ordering: pure filename math, so it runs
        // on every scan. A video's playlistIndex is its alphabetical
        // position among ALL of its directory's videos (unchanged ones
        // included) — adding or renaming one file re-orders the rest
        // through the diff pass instead of leaving stale positions behind.
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

        // Resolution phase: the only file-content reads in a scan, gated
        // per group by sidecar fingerprints and run in parallel — each
        // read is an independent FUSE/provider round-trip, and sequential
        // round-trips at ~1/sec are exactly what made scans take half an
        // hour. Children are collected and joined EXPLICITLY (joinAll):
        // this test environment has shown implicit scope-wait to be
        // unreliable, and explicit joining is semantically identical.
        val resolved = coroutineScope {
            val results = arrayOfNulls<ResolvedVideo>(groups.size)
            val readLimiter = Semaphore(SIDECAR_READ_PARALLELISM)
            val progressCounter = AtomicInteger(0)
            val jobs = ArrayList<Job>()
            for ((index, group) in groups.withIndex()) {
                if (group.video == null) continue // sidecar with no matching video file
                jobs += launch(Dispatchers.IO) {
                    readLimiter.withPermit {
                        val resolvedOne =
                            resolveGroup(source, group, existingByPath, playlistIndexByPath)
                        results[index] = resolvedOne
                        val done = progressCounter.incrementAndGet()
                        // Throttled: enough granularity to look alive, few
                        // enough calls to add no measurable cost.
                        if (done == totalGroups || done % PROGRESS_EVERY == 0) {
                            onProgress(done, totalGroups)
                        }
                    }
                }
            }
            jobs.joinAll()
            results.filterNotNull()
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
            val channelId = video.display.channelId ?: continue
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
            // Lookup by ID (not path): addedAt of the same video survives
            // renames/moves, matching the original engine's semantics.
            val existing = existingFingerprints[video.id]

            if (video.display.channelId != null && video.display.channelName != null) {
                // Carry forward an avatar already fetched by
                // ChannelIconWorker — a blind REPLACE would wipe it on
                // every scan and re-trigger the network pass.
                val iconPath = channelDao.getById(video.display.channelId)?.iconPath
                channelsToUpsert[video.display.channelId] = ChannelEntity(
                    id = video.display.channelId,
                    name = video.display.channelName,
                    thumbnailPath = null, // channel avatar sourcing is a later enhancement — see docs/ARCHITECTURE.md open items
                    iconPath = iconPath,
                )
            }

            val row = video.toRow(source.id.value, existing?.addedAtEpochMillis)
            if (existing == null || existing.rowDiffersFrom(row)) {
                videosToUpsert += row
            }
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

    private suspend fun resolveGroup(
        source: Source,
        group: FileGroup,
        existingByPath: Map<String, VideoFingerprint>,
        playlistIndexByPath: Map<String, Int>,
    ): ResolvedVideo {
        val videoFile = group.video ?: error("resolveGroup called on a video-less group")
        val existing = existingByPath[videoFile.relativePath]

        val infoJsonUnchanged = existing?.infoJsonMatches(group.infoJson) == true
        val descriptionUnchanged = existing?.descriptionMatches(group.description) == true

        // ── sidecar reads, only where fingerprints changed ──
        val parsed: ParsedVideoMetadata? = infoJsonUnchanged.let { unchanged ->
            // unchanged → no read; the existing row already holds every
            // field an identical parse would produce.
            if (unchanged) return@let null
            val text = group.infoJson?.let { entry ->
                runCatching {
                    fileSystem.openInputStream(source, entry.relativePath).use {
                        it.readBytes().decodeToString()
                    }
                }.getOrNull()
            }
            text?.let { metadataParser.parse(it) }
        }

        // yt-dlp --write-description writes "<basename>.description"
        // next to the video: UTF-8 text, read best-effort, truncated
        // defensively — descriptions are display text.
        // null ⇔ unchanged ⇒ the row's description carries; "" ⇔ the
        // sidecar vanished or became unreadable ⇒ row text clears.
        val descriptionText: String? = when {
            descriptionUnchanged -> null
            else -> group.description?.let { entry ->
                runCatching {
                    fileSystem.openInputStream(source, entry.relativePath).use {
                        it.readBytes().decodeToString()
                    }
                }.getOrNull()
            }?.take(DESCRIPTION_MAX_CHARS) ?: ""
        }

        // Full fast-path check: video bytes AND both sidecars unchanged.
        // Nearly every stable-library row takes this; zero content reads.
        val fullyCarried = existing != null &&
            existing.fileSizeBytes == videoFile.sizeBytes &&
            existing.fileLastModifiedEpochMillis == videoFile.lastModifiedEpochMillis &&
            infoJsonUnchanged &&
            descriptionUnchanged

        // Display/identity fallbacks: without a fresh parse, carry the
        // existing row's values (a re-downloaded file with identical
        // sidecars keeps its id/title/channel/duration), and only fall
        // further back to yt-dlp's "Author - Title.ext" filename
        // convention when there IS no previous row (bare download
        // folders still get channels and clean titles).
        val filenameDisplay = authorTitleFromFilename(titleBaseOf(videoFile.relativePath))
        val display = when {
            parsed != null -> ScanDisplay(
                channelId = parsed.channelId ?: existing?.channelId,
                channelName = parsed.channelName ?: filenameDisplay.channelName,
                title = parsed.title ?: filenameDisplay.title,
            )
            existing != null -> ScanDisplay(
                channelId = existing.channelId,
                // null name → channel row untouched (name unknown this scan)
                channelName = null,
                title = existing.title,
            )
            else -> ScanDisplay(
                channelId = filenameDisplay.channelId,
                channelName = filenameDisplay.channelName,
                title = filenameDisplay.title,
            )
        }

        // On-disk folder structure wins over info.json playlist
        // references: a nested video is a member of its directory's
        // playlist; only a root-level video falls back to an info.json
        // playlist — never both. playlistIndex comes from filename math
        // for directory playlists, else from the sidecar.
        val directorySegment = directorySegmentOf(videoFile.relativePath)
        val playlistId = directorySegment
            ?.let { derivedPlaylistId(source.id.value, it) }
            ?: parsed?.playlistId
            ?: existing?.playlistId
        val playlistIndex = if (directorySegment != null) {
            playlistIndexByPath[videoFile.relativePath]
        } else {
            parsed?.playlistIndex ?: existing?.playlistIndex
        }

        return ResolvedVideo(
            videoFile = videoFile,
            infoJsonEntry = group.infoJson,
            descriptionEntry = group.description,
            thumbnailEntry = group.thumbnail,
            // Identity precedence: fresh parse (sidecar changed) → same
            // sidecar's previous row → path hash (brand-new file, no
            // info.json). The middle case is WHY unchanged sidecars
            // don't need re-reading: identity survives re-downloads.
            id = parsed?.platformVideoId ?: existing?.id
                ?: deriveIdFromPath(source.id.value, videoFile.relativePath),
            parsed = parsed,
            display = display,
            descriptionText = if (fullyCarried) null else descriptionText,
            carriedDescription = existing?.description,
            playlistId = playlistId,
            playlistName = directorySegment ?: parsed?.playlistName,
            playlistIndex = playlistIndex,
            durationCarried = existing?.durationSeconds,
            thumbnailCarried = existing?.thumbnailPath,
            thumbnailUnchanged = existing?.thumbnailMatches(group.thumbnail) == true,
            fullyCarried = fullyCarried,
        )
    }

    private fun VideoFingerprint.infoJsonMatches(entry: RemoteFileEntry?): Boolean =
        (entry?.sizeBytes ?: -1L) == (infoJsonSizeBytes ?: -1L) &&
            (entry?.lastModifiedEpochMillis ?: -1L) == (infoJsonLastModifiedEpochMillis ?: -1L)

    private fun VideoFingerprint.descriptionMatches(entry: RemoteFileEntry?): Boolean =
        (entry?.sizeBytes ?: -1L) == (descriptionSizeBytes ?: -1L) &&
            (entry?.lastModifiedEpochMillis ?: -1L) == (descriptionLastModifiedEpochMillis ?: -1L)

    private fun VideoFingerprint.thumbnailMatches(entry: RemoteFileEntry?): Boolean =
        (entry?.sizeBytes ?: -1L) == (thumbnailSizeBytes ?: -1L) &&
            (entry?.lastModifiedEpochMillis ?: -1L) == (thumbnailLastModifiedEpochMillis ?: -1L)

    /** Materializes the row; null descriptionText ⇔ carry, see [ResolvedVideo]. */
    private fun ResolvedVideo.toRow(sourceId: Long, addedAtFallback: Long?): VideoEntity =
        VideoEntity(
            id = id,
            sourceId = sourceId,
            channelId = display.channelId,
            playlistId = playlistId,
            playlistIndex = playlistIndex,
            filePath = videoFile.relativePath,
            title = display.title,
            durationSeconds = parsed?.durationSeconds ?: durationCarried,
            // Unchanged thumbnail sidecar → the row's path carries (which
            // may be an app-internal extraction courtesy of
            // ThumbnailFallbackWorker); changed/new → stamp the SAF path.
            thumbnailPath = if (thumbnailUnchanged) thumbnailCarried
            else thumbnailEntry?.relativePath ?: thumbnailCarried,
            description = descriptionText ?: carriedDescription
                ?: "", // no row and no sidecar — genuinely no description
            addedAtEpochMillis = addedAtFallback ?: System.currentTimeMillis(),
            fileSizeBytes = videoFile.sizeBytes,
            fileLastModifiedEpochMillis = videoFile.lastModifiedEpochMillis,
            infoJsonSizeBytes = infoJsonEntry?.sizeBytes,
            infoJsonLastModifiedEpochMillis = infoJsonEntry?.lastModifiedEpochMillis,
            descriptionSizeBytes = descriptionEntry?.sizeBytes,
            descriptionLastModifiedEpochMillis = descriptionEntry?.lastModifiedEpochMillis,
            thumbnailSizeBytes = thumbnailEntry?.sizeBytes,
            thumbnailLastModifiedEpochMillis = thumbnailEntry?.lastModifiedEpochMillis,
        )

    /**
     * Compares a freshly-resolved row against the existing fingerprint —
     * the write gate. In a stable library nothing differs and nothing is
     * written; a transient read failure hence does not smash the row.
     */
    private fun VideoFingerprint.rowDiffersFrom(row: VideoEntity): Boolean =
        channelId != row.channelId ||
            playlistId != row.playlistId ||
            playlistIndex != row.playlistIndex ||
            filePath != row.filePath ||
            title != row.title ||
            durationSeconds != row.durationSeconds ||
            thumbnailPath != row.thumbnailPath ||
            description != row.description ||
            fileSizeBytes != row.fileSizeBytes ||
            fileLastModifiedEpochMillis != row.fileLastModifiedEpochMillis ||
            infoJsonSizeBytes != row.infoJsonSizeBytes ||
            infoJsonLastModifiedEpochMillis != row.infoJsonLastModifiedEpochMillis ||
            descriptionSizeBytes != row.descriptionSizeBytes ||
            descriptionLastModifiedEpochMillis != row.descriptionLastModifiedEpochMillis ||
            thumbnailSizeBytes != row.thumbnailSizeBytes ||
            thumbnailLastModifiedEpochMillis != row.thumbnailLastModifiedEpochMillis

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

    /** Filename base ("Author - Title"), the small title display fallback. */
    private fun titleBaseOf(relativePath: String): String {
        val name = relativePath.substringAfterLast('/').substringBeforeLast('.')
        return name.replace('_', ' ').trim()
    }

    private data class ScanDisplay(
        val channelId: String?,
        /** Non-null only when a fresh display name is known this scan. */
        val channelName: String?,
        val title: String,
    )

    /**
     * Splits a yt-dlp style filename "Author - Title" into a stable
     * channel id, the channel name, and the remaining title. No match →
     * no channel, whole name as title.
     */
    private fun authorTitleFromFilename(baseName: String): ScanDisplay {
        val idx = baseName.indexOf(" - ")
        if (idx <= 0 || idx >= baseName.length - 3) return ScanDisplay(null, null, baseName)
        val author = baseName.substring(0, idx).trim()
        val title = baseName.substring(idx + 3).trim()
        if (author.isEmpty() || title.isEmpty()) return ScanDisplay(null, null, baseName)
        return ScanDisplay(
            channelId = "fname:" + author.lowercase().replace(Regex("\\s+"), "-"),
            channelName = author,
            title = title,
        )
    }

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
     * Stable id fallback when no info.json is available: SHA-256 of
     * source id + relative path. Stable across scans as long as the file
     * doesn't move, which is the best guarantee available without
     * metadata.
     */
    private fun deriveIdFromPath(sourceId: Long, relativePath: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
            .digest("$sourceId:$relativePath".toByteArray())
        return digest.joinToString("") { "%02x".format(it) }.take(32)
    }

    /**
     * One video file with everything resolved from sidecars, names, or
     * (where fingerprints matched) the previous row.
     */
    private data class ResolvedVideo(
        val videoFile: RemoteFileEntry,
        val infoJsonEntry: RemoteFileEntry?,
        val descriptionEntry: RemoteFileEntry?,
        val thumbnailEntry: RemoteFileEntry?,
        val id: String,
        val parsed: ParsedVideoMetadata?,
        val display: ScanDisplay,
        /** null ⇔ row text carries (sidecar unchanged); empty ⇔ sidecar gone. */
        val descriptionText: String?,
        val carriedDescription: String?,
        val playlistId: String?,
        val playlistName: String?,
        val playlistIndex: Int?,
        val durationCarried: Long?,
        val thumbnailCarried: String?,
        val thumbnailUnchanged: Boolean,
        val fullyCarried: Boolean,
    )

    private data class FileGroup(
        val video: RemoteFileEntry?,
        val infoJson: RemoteFileEntry?,
        val thumbnail: RemoteFileEntry?,
        val description: RemoteFileEntry?,
    )

    private companion object {
        /** Ceiling on concurrent sidecar reads during the resolve phase. */
        const val SIDECAR_READ_PARALLELISM = 8

        /** Progress callback granularity — see [scan]. */
        const val PROGRESS_EVERY = 25

        /** Sidecar descriptions are stored truncated — display text, not search input. */
        const val DESCRIPTION_MAX_CHARS = 5000
    }
}
