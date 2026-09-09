package dev.hudater.localtube.core.player

import android.content.Context
import androidx.media3.datasource.DataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import dev.hudater.localtube.core.filesystem.SourceFileSystem
import dev.hudater.localtube.core.model.SourceType

/**
 * Routes to the correct [SourceFileSystem] by [SourceType] so a single
 * ExoPlayer instance can play videos from LOCAL and SMB sources
 * interchangeably. NFS, when added, plugs in here the same way.
 */
class RoutingSourceFileSystem(
    private val fileSystemsByType: Map<SourceType, SourceFileSystem>,
    private val sourceResolver: SourceResolver,
) {
    suspend fun resolveFileSystemFor(sourceId: dev.hudater.localtube.core.model.SourceId): SourceFileSystem {
        val source = sourceResolver.resolve(sourceId)
            ?: error("Unknown source id: ${sourceId.value}")
        return fileSystemsByType[source.type]
            ?: error("No SourceFileSystem registered for type ${source.type}")
    }
}

/**
 * Builds [ExoPlayer] instances wired to read through our [SourceFileSystem]
 * abstraction instead of ExoPlayer's default HTTP/file data sources.
 *
 * One instance of this factory is created in the app module's DI setup
 * (see :app/di) with all [SourceFileSystem] implementations registered.
 */
class LocalTubePlayerFactory(
    private val appContext: Context,
    private val fileSystemsByType: Map<SourceType, SourceFileSystem>,
    private val sourceResolver: SourceResolver,
) {
    fun create(): ExoPlayer {
        val dataSourceFactory = DataSource.Factory {
            // A single DataSource instance may be asked to open() several
            // different sourceIds over its lifetime (ExoPlayer reuses
            // DataSource instances across MediaItems in some cases), so
            // resolution happens per-open(), not per-factory — see
            // SourceFileSystemDataSource.open().
            DispatchingDataSource(fileSystemsByType, sourceResolver)
        }

        val mediaSourceFactory = ProgressiveMediaSource.Factory(dataSourceFactory)

        return ExoPlayer.Builder(appContext)
            .setMediaSourceFactory(mediaSourceFactory)
            .build()
    }
}

/**
 * A [androidx.media3.datasource.DataSource] that resolves which concrete
 * [SourceFileSystem] to delegate to at `open()` time, based on the
 * [SourceType] of the URI's encoded source id. This is what lets one
 * ExoPlayer/media source factory play videos that live across LOCAL and
 * SMB sources without the player layer needing to know which is which
 * ahead of time.
 */
private class DispatchingDataSource(
    private val fileSystemsByType: Map<SourceType, SourceFileSystem>,
    private val sourceResolver: SourceResolver,
) : androidx.media3.datasource.BaseDataSource(true) {

    private var delegate: SourceFileSystemDataSource? = null

    override fun open(dataSpec: androidx.media3.datasource.DataSpec): Long {
        val sourceId = LocalTubeMediaItems.sourceIdFrom(dataSpec.uri)
        val source = kotlinx.coroutines.runBlocking { sourceResolver.resolve(sourceId) }
            ?: throw java.io.IOException("Unknown source id: ${sourceId.value}")
        val fileSystem = fileSystemsByType[source.type]
            ?: throw java.io.IOException("No SourceFileSystem for type ${source.type}")

        val newDelegate = SourceFileSystemDataSource(fileSystem, sourceResolver)
        delegate = newDelegate
        return newDelegate.open(dataSpec)
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int =
        delegate?.read(buffer, offset, length)
            ?: throw java.io.IOException("DataSource not opened")

    override fun getUri() = delegate?.uri

    override fun close() {
        delegate?.close()
        delegate = null
    }
}
