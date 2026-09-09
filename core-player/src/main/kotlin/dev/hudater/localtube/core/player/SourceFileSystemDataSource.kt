package dev.hudater.localtube.core.player

import androidx.media3.common.C
import androidx.media3.datasource.BaseDataSource
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import dev.hudater.localtube.core.filesystem.RandomAccessHandle
import dev.hudater.localtube.core.filesystem.SourceFileSystem
import dev.hudater.localtube.core.model.Source
import java.io.IOException
import kotlin.math.min

/**
 * Resolves the [Source] a media URI's authority (a [SourceId]) refers to.
 * Implemented in the app module against [dev.hudater.localtube.core.data.repository.SourceRepository]
 * — kept as an interface here so core-player never depends on core-data
 * or Room directly.
 */
fun interface SourceResolver {
    suspend fun resolve(sourceId: dev.hudater.localtube.core.model.SourceId): Source?
}

/**
 * Media3 [DataSource] that streams from any [SourceFileSystem] (LOCAL,
 * SMB, or NFS) via its [RandomAccessHandle] contract, rather than
 * assuming a plain java.io.File or an HTTP URL.
 *
 * This is the ONE place ExoPlayer touches source-type-specific I/O — the
 * player UI and everything above it just deals in [dev.hudater.localtube.core.model.Video],
 * unaware of where the bytes actually come from.
 */
class SourceFileSystemDataSource(
    private val fileSystem: SourceFileSystem,
    private val sourceResolver: SourceResolver,
) : BaseDataSource(/* isNetwork= */ true) {

    private var handle: RandomAccessHandle? = null
    private var source: Source? = null
    private var relativePath: String? = null
    private var position: Long = 0
    private var bytesRemaining: Long = 0

    override fun open(dataSpec: DataSpec): Long {
        val sourceId = LocalTubeMediaItems.sourceIdFrom(dataSpec.uri)
        val path = LocalTubeMediaItems.relativePathFrom(dataSpec.uri)
        val openedSource = kotlinx.coroutines.runBlocking { sourceResolver.resolve(sourceId) }
            ?: throw IOException("Unknown source id: ${sourceId.value}")
        source = openedSource
        relativePath = path

        val opened = fileSystem.openRandomAccess(openedSource, path)
        handle = opened

        position = dataSpec.position
        bytesRemaining = if (dataSpec.length != C.LENGTH_UNSET.toLong()) {
            dataSpec.length
        } else {
            opened.lengthBytes - position
        }

        if (bytesRemaining < 0) {
            throw IOException("Requested position $position beyond file length ${opened.lengthBytes}")
        }

        transferInitializing(dataSpec)
        transferStarted(dataSpec)
        return bytesRemaining
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        if (length == 0) return 0
        if (bytesRemaining == 0L) return C.RESULT_END_OF_INPUT

        val toRead = min(length.toLong(), bytesRemaining).toInt()
        val handleRef = handle ?: throw IOException("DataSource not opened")
        val bytesRead = handleRef.readAt(position, buffer, offset, toRead)
        if (bytesRead == -1) return C.RESULT_END_OF_INPUT

        position += bytesRead
        bytesRemaining -= bytesRead
        bytesTransferred(bytesRead)
        return bytesRead
    }

    override fun getUri() = source?.let { LocalTubeMediaItems.toUri(it, relativePath.orEmpty()) }

    override fun close() {
        try {
            handle?.close()
        } finally {
            handle = null
            source = null
            relativePath = null
            transferEnded()
        }
    }
}

/**
 * Factory Media3 calls into to build [SourceFileSystemDataSource]
 * instances. Registered once against ExoPlayer's MediaSource.Factory —
 * see [LocalTubePlayerFactory].
 */
class SourceFileSystemDataSourceFactory(
    private val fileSystem: SourceFileSystem,
    private val sourceResolver: SourceResolver,
) : DataSource.Factory {
    override fun createDataSource(): DataSource = SourceFileSystemDataSource(fileSystem, sourceResolver)
}
