package dev.hudater.localtube.core.data.scan

import dev.hudater.localtube.core.data.db.ChannelDao
import dev.hudater.localtube.core.data.db.ChannelEntity
import dev.hudater.localtube.core.data.db.PlaylistDao
import dev.hudater.localtube.core.data.db.PlaylistEntity
import dev.hudater.localtube.core.data.db.VideoDao
import dev.hudater.localtube.core.data.db.VideoEntity
import dev.hudater.localtube.core.data.db.VideoFingerprint
import dev.hudater.localtube.core.filesystem.FileKind
import dev.hudater.localtube.core.filesystem.RemoteFileEntry
import dev.hudater.localtube.core.filesystem.SourceFileSystem
import dev.hudater.localtube.core.filesystem.SourceUnavailableException
import dev.hudater.localtube.core.metadata.YtDlpMetadataParser
import dev.hudater.localtube.core.model.Source
import dev.hudater.localtube.core.model.SourceId
import dev.hudater.localtube.core.model.SourceType
import java.io.InputStream
import java.io.ByteArrayInputStream
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * These are the regression tests for the scan behaviors that made
 * LocalTube look broken on a 966-video library:
 *
 * - re-scanning an UNCHANGED library must read zero file contents and
 *   write zero rows (it used to re-read every sidecar at ~1 IOPS —
 *   ~35 minutes of invisible work);
 * - a listing failure (revoked SAF folder grant) must abort without
 *   touching the DB ("worked, then everything vanished" — it used to
 *   treat unreadable as empty and delete the library);
 * - only files whose bytes or sidecars actually changed get re-read.
 */
class ScanEngineTest {

    // ── fakes ──────────────────────────────────────────────────────────

    /** In-memory stand-in for Room's VideoDao — no Android, no db. */
    private class FakeVideoDao : VideoDao {
        val videos = mutableMapOf<String, VideoEntity>()
        var upsertCalls = 0
        var deleteCalls = 0

        override suspend fun upsert(video: VideoEntity) { upsertCalls++; videos[video.id] = video }
        override suspend fun upsertAll(newVideos: List<VideoEntity>) { upsertCalls += newVideos.size; newVideos.forEach { videos[it.id] = it } }
        override suspend fun deleteByIds(ids: List<String>) { deleteCalls += ids.size; ids.forEach(videos::remove) }
        override suspend fun getKnownFileFingerprints(sourceId: Long): List<VideoFingerprint> =
            videos.values.filter { it.sourceId == sourceId }.map {
                VideoFingerprint(
                    id = it.id,
                    filePath = it.filePath,
                    fileSizeBytes = it.fileSizeBytes,
                    fileLastModifiedEpochMillis = it.fileLastModifiedEpochMillis,
                    addedAtEpochMillis = it.addedAtEpochMillis,
                    title = it.title,
                    channelId = it.channelId,
                    playlistId = it.playlistId,
                    playlistIndex = it.playlistIndex,
                    description = it.description,
                    durationSeconds = it.durationSeconds,
                    thumbnailPath = it.thumbnailPath,
                    infoJsonSizeBytes = it.infoJsonSizeBytes,
                    infoJsonLastModifiedEpochMillis = it.infoJsonLastModifiedEpochMillis,
                    descriptionSizeBytes = it.descriptionSizeBytes,
                    descriptionLastModifiedEpochMillis = it.descriptionLastModifiedEpochMillis,
                    thumbnailSizeBytes = it.thumbnailSizeBytes,
                    thumbnailLastModifiedEpochMillis = it.thumbnailLastModifiedEpochMillis,
                )
            }

        override suspend fun videosWithoutThumbnail(): List<VideoEntity> = throw UnsupportedOperationException()
        override suspend fun updateThumbnailPath(id: String, path: String) = throw UnsupportedOperationException()
        override suspend fun updateDurationSeconds(id: String, seconds: Long) = throw UnsupportedOperationException()
        override fun observePlaylistVideos(playlistId: String): kotlinx.coroutines.flow.Flow<List<VideoEntity>> = throw UnsupportedOperationException()
        override fun observeStandaloneVideos(sourceIds: List<Long>, channelId: String?): kotlinx.coroutines.flow.Flow<List<VideoEntity>> = throw UnsupportedOperationException()
        override fun observeFeed(sourceIds: List<Long>, channelId: String?): kotlinx.coroutines.flow.Flow<List<VideoEntity>> = throw UnsupportedOperationException()
        override suspend fun getById(id: String): VideoEntity? = videos[id]
    }

    private class FakeChannelDao : ChannelDao {
        val channels = mutableMapOf<String, ChannelEntity>()
        override suspend fun upsert(channel: ChannelEntity) { channels[channel.id] = channel }
        override suspend fun upsertAll(channels: List<ChannelEntity>) { channels.forEach { this.channels[it.id] = it } }
        override fun observeChannelsForSources(sourceIds: List<Long>): kotlinx.coroutines.flow.Flow<List<ChannelEntity>> = throw UnsupportedOperationException()
        override suspend fun getById(id: String): ChannelEntity? = channels[id]
        override suspend fun channelsWithoutIcon(): List<ChannelEntity> = throw UnsupportedOperationException()
        override suspend fun updateChannelIconPath(id: String, path: String) = throw UnsupportedOperationException()
    }

    private class FakePlaylistDao : PlaylistDao {
        val playlists = mutableMapOf<String, PlaylistEntity>()
        override suspend fun upsert(playlist: PlaylistEntity) { playlists[playlist.id] = playlist }
        override fun observePlaylists(sourceIds: List<Long>, channelId: String?): kotlinx.coroutines.flow.Flow<List<PlaylistEntity>> = throw UnsupportedOperationException()
        override suspend fun getById(id: String): PlaylistEntity? = playlists[id]
    }

    /** Deterministic fake tree, plus a read counter for the cost assertions. */
    private class FakeFileSystem(
        private val entries: List<RemoteFileEntry>,
        private val contents: Map<String, String> = emptyMap(),
        private val listError: Exception? = null,
    ) : SourceFileSystem {
        val streamOpens = AtomicInteger(0)

        override suspend fun listFiles(source: Source): List<RemoteFileEntry> {
            listError?.let { throw it }
            return entries
        }

        override suspend fun openInputStream(source: Source, relativePath: String): InputStream {
            streamOpens.incrementAndGet()
            val text = contents[relativePath] ?: throw java.io.FileNotFoundException(relativePath)
            return ByteArrayInputStream(text.toByteArray(Charsets.UTF_8))
        }

        override fun openRandomAccess(source: Source, relativePath: String): dev.hudater.localtube.core.filesystem.RandomAccessHandle = throw UnsupportedOperationException()
        override suspend fun isReachable(source: Source): Boolean = listError == null
        override suspend fun deleteFile(source: Source, relativePath: String) = throw UnsupportedOperationException()
    }

    // ── test data ──────────────────────────────────────────────────────

    private fun source(id: Long = 1) = Source(SourceId(id), SourceType.LOCAL, "tree://test", "Test")

    private fun entry(path: String, size: Long, mtime: Long, kind: FileKind) =
        RemoteFileEntry(path, size, mtime, kind)

    private val INFO_JSON =
        """{"id":"abc123","channel_id":"UCchan1","channel":"Chan One","title":"Best Title","duration":61}"""

    /** video + info.json sidecar, fingerprint-fresh */
    private val twoEntryLibrary = listOf(
        entry("Obsidian - My Setup.mp4", 1000, 1000, FileKind.VIDEO),
        entry("Obsidian - My Setup.info.json", 200, 1001, FileKind.INFO_JSON),
    )
    private val twoEntryContents = mapOf(
        "Obsidian - My Setup.info.json" to INFO_JSON,
    )

    private fun engine(
        fileSystem: SourceFileSystem,
        dao: FakeVideoDao,
        channelDao: FakeChannelDao = FakeChannelDao(),
    ): ScanEngine = ScanEngine(
        fileSystem = fileSystem,
        metadataParser = YtDlpMetadataParser(),
        videoDao = dao,
        channelDao = channelDao,
        playlistDao = FakePlaylistDao(),
    )

    // ── tests ──────────────────────────────────────────────────────────

    @Test
    fun `parses info json into channel title duration and stable id`() = runBlocking {
        val dao = FakeVideoDao()
        val fs = FakeFileSystem(twoEntryLibrary, twoEntryContents)
        engine(fs, dao).scan(source())

        val row = dao.videos.values.single()
        assertEquals("abc123", row.id)
        assertEquals("UCchan1", row.channelId)
        assertEquals("Best Title", row.title)
        assertEquals(61L, row.durationSeconds)
        assertEquals(1, fs.streamOpens.get()) // info.json only — no .description sidecar present
        assertEquals(200L, row.infoJsonSizeBytes)
        assertEquals(1001L, row.infoJsonLastModifiedEpochMillis)
    }

    @Test
    fun `unchanged rescan reads nothing and writes nothing`() = runBlocking {
        val dao = FakeVideoDao()
        val fs = FakeFileSystem(twoEntryLibrary, twoEntryContents)
        val engine = engine(fs, dao)

        engine.scan(source())
        val readsFirst = fs.streamOpens.get()
        val writesFirst = dao.upsertCalls

        engine.scan(source())

        assertEquals(1, readsFirst) // info.json only — no .description sidecar present
        assertEquals(1, writesFirst)
        assertEquals(0, fs.streamOpens.get() - readsFirst) // zero re-reads
        assertEquals(0, dao.upsertCalls - writesFirst)        // zero rewrites
        assertEquals(0, dao.deleteCalls)
    }

    @Test
    fun `re-downloaded video keeps identity without sidecar reads`() = runBlocking {
        val dao = FakeVideoDao()
        val fs = FakeFileSystem(twoEntryLibrary, twoEntryContents)
        val engine = engine(fs, dao)
        engine.scan(source())
        val writesBefore = dao.upsertCalls

        val reDownloaded = listOf(
            entry("Obsidian - My Setup.mp4", 2400, 9999, FileKind.VIDEO), // new bytes
            entry("Obsidian - My Setup.info.json", 200, 1001, FileKind.INFO_JSON), // same sidecar
        )
        val fs2 = FakeFileSystem(reDownloaded, twoEntryContents)
        val engine2 = engine(fs2, dao)
        engine2.scan(source())

        assertEquals(0, fs2.streamOpens.get()) // sidecar untouched → zero reads
        val row = dao.videos.values.single()
        assertEquals("abc123", row.id)      // identity survived
        assertEquals("Best Title", row.title)
        assertEquals(2400L, row.fileSizeBytes)
        assertEquals(9999L, row.fileLastModifiedEpochMillis)
        assertEquals(writesBefore + 1, dao.upsertCalls) // exactly the re-downloaded row rewritten
    }

    @Test
    fun `changed info json is re-read and fields update`() = runBlocking {
        val dao = FakeVideoDao()
        val fs = FakeFileSystem(twoEntryLibrary, twoEntryContents)
        val engine = engine(fs, dao)
        engine.scan(source())

        val retitled = mapOf(
            "Obsidian - My Setup.info.json" to
                """{"id":"abc123","channel_id":"UCchan1","channel":"Chan One","title":"NEW TITLE","duration":120}""",
        )
        val fs2 = FakeFileSystem(
            listOf(
                entry("Obsidian - My Setup.mp4", 1000, 1000, FileKind.VIDEO),
                entry("Obsidian - My Setup.info.json", 210, 2002, FileKind.INFO_JSON),
            ),
            retitled,
        )
        engine(fs2, dao).scan(source())

        assertEquals(1, fs2.streamOpens.get()) // only info.json; description absent
        assertEquals("NEW TITLE", dao.videos.values.single().title)
        assertEquals(120L, dao.videos.values.single().durationSeconds)
    }

    @Test
    fun `directory rename respected via playlist id - folder insertion shifts indexes`() = runBlocking {
        val dao = FakeVideoDao()
        val lib = listOf(
            entry("NixOS — Setup/1 - Install.mp4", 10, 10, FileKind.VIDEO),
            entry("NixOS — Setup/2 - Configure.mp4", 10, 10, FileKind.VIDEO),
        )
        val fs = FakeFileSystem(lib)
        engine(fs, dao).scan(source())
        val rows = dao.videos.values.associateBy { it.filePath }
        assertEquals(0, rows["NixOS — Setup/1 - Install.mp4"]!!.playlistIndex)
        assertEquals(1, rows["NixOS — Setup/2 - Configure.mp4"]!!.playlistIndex)

        // A NEW file comes first alphabetically: every existing sibling
        // shifts down — the diff pass must rewrite them all (no reads
        // involved; pure filename math).
        val grown = lib + entry("NixOS — Setup/0 - Intro.mp4", 10, 11, FileKind.VIDEO)
        val fs2 = FakeFileSystem(grown)
        fs2.let { engine(it, dao).scan(source()) }

        val after = dao.videos.values.associateBy { it.filePath }
        assertEquals(0, after["NixOS — Setup/0 - Intro.mp4"]!!.playlistIndex)
        assertEquals(1, after["NixOS — Setup/1 - Install.mp4"]!!.playlistIndex)
        assertEquals(2, after["NixOS — Setup/2 - Configure.mp4"]!!.playlistIndex)
        assertEquals("Intro", after["NixOS — Setup/0 - Intro.mp4"]!!.title) // "0 - Intro" splits into author "0" + title "Intro"
    }

    @Test
    fun `unreadable listing throws without touching the db`() = runBlocking {
        val dao = FakeVideoDao()
        // Seed an existing row: the "scan worked previously" state.
        dao.videos["seed"] = VideoEntity(
            id = "seed", sourceId = 1, channelId = null, playlistId = null, playlistIndex = null,
            filePath = "file.mp4", title = "file", durationSeconds = null, thumbnailPath = null,
            addedAtEpochMillis = 0, fileSizeBytes = 1, fileLastModifiedEpochMillis = 1,
        )
        val fs = FakeFileSystem(emptyList(), listError = SourceUnavailableException("SAF grant revoked"))

        val error = runCatching { engine(fs, dao).scan(source()) }
            .exceptionOrNull()

        assertTrue(error is SourceUnavailableException)
        // NOT wiped: the single seeded row still exists, zero deletes.
        assertNotNull(dao.videos["seed"])
        assertEquals(0, dao.deleteCalls)
        assertEquals(0, dao.upsertCalls)
    }

    @Test
    fun `genuinely empty folder deletes stale rows`() = runBlocking {
        val dao = FakeVideoDao()
        dao.videos["gone"] = VideoEntity(
            id = "gone", sourceId = 1, channelId = null, playlistId = null, playlistIndex = null,
            filePath = "deleted.mp4", title = "deleted", durationSeconds = null, thumbnailPath = null,
            addedAtEpochMillis = 0, fileSizeBytes = 1, fileLastModifiedEpochMillis = 1,
        )
        val fs = FakeFileSystem(emptyList())
        engine(fs, dao).scan(source())

        assertNull(dao.videos["gone"])
        assertEquals(1, dao.deleteCalls)
    }

    @Test
    fun `bare filename without info json falls back to author - title`() = runBlocking {
        val dao = FakeVideoDao()
        val channelDao = FakeChannelDao()
        val fs = FakeFileSystem(listOf(entry("MKBHD - Review.mp4", 100, 100, FileKind.VIDEO)))
        engine(fs, dao, channelDao).scan(source())

        val row = dao.videos.values.single()
        assertEquals("fname:mkbhd", row.channelId) // stable filename-derived channel id
        assertEquals("MKBHD", channelDao.channels.values.single().name)
        assertEquals("Review", row.title)
    }
}
