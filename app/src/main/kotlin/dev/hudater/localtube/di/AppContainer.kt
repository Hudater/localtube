package dev.hudater.localtube.di

import android.content.Context
import dev.hudater.localtube.core.data.db.LocalTubeDatabase
import dev.hudater.localtube.core.data.repository.LibraryRepository
import dev.hudater.localtube.core.data.repository.RoomLibraryRepository
import dev.hudater.localtube.core.data.repository.RoomSourceRepository
import dev.hudater.localtube.core.data.repository.SourceRepository
import dev.hudater.localtube.core.data.scan.ScanEngine
import dev.hudater.localtube.core.data.scan.ThumbnailFallbackGenerator
import dev.hudater.localtube.core.filesystem.LocalSourceFileSystem
import dev.hudater.localtube.core.filesystem.SmbCredentialProvider
import dev.hudater.localtube.core.filesystem.SmbSourceFileSystem
import dev.hudater.localtube.core.filesystem.SourceFileSystem
import dev.hudater.localtube.core.metadata.MetadataParser
import dev.hudater.localtube.core.metadata.YtDlpMetadataParser
import dev.hudater.localtube.core.model.SourceType
import dev.hudater.localtube.core.player.LocalTubePlayerFactory
import dev.hudater.localtube.core.player.SourceResolver
import dev.hudater.localtube.security.EncryptedSmbCredentialStore

/**
 * Manual dependency container — no Hilt/Dagger, per the project's
 * decision to keep this a single-maintainer-friendly codebase (see
 * docs/ARCHITECTURE.md, "Why no DI framework").
 *
 * Everything here is a plain lazily-initialized property. If this ever
 * grows past ~30-40 entries or the app gains multiple contributors,
 * that's the signal to reconsider Hilt — not before.
 */
class AppContainer(private val appContext: Context) {

    val database: LocalTubeDatabase by lazy {
        LocalTubeDatabase.getInstance(appContext)
    }

    // --- Credentials ---

    val smbCredentialStore: EncryptedSmbCredentialStore by lazy {
        EncryptedSmbCredentialStore(appContext)
    }

    private val smbCredentialProvider = SmbCredentialProvider { source ->
        smbCredentialStore.authenticationFor(source)
    }

    // --- Filesystems ---

    val localFileSystem: LocalSourceFileSystem by lazy {
        LocalSourceFileSystem(appContext)
    }

    val smbFileSystem: SmbSourceFileSystem by lazy {
        SmbSourceFileSystem(smbCredentialProvider)
    }

    /** NFS is not yet implemented — see docs/ARCHITECTURE.md open items. */
    val fileSystemsByType: Map<SourceType, SourceFileSystem> by lazy {
        mapOf(
            SourceType.LOCAL to localFileSystem,
            SourceType.SMB to smbFileSystem,
        )
    }

    fun fileSystemFor(type: SourceType): SourceFileSystem =
        fileSystemsByType[type] ?: error("No SourceFileSystem registered for $type")

    // --- Metadata ---

    val metadataParser: MetadataParser by lazy { YtDlpMetadataParser() }

    // --- Repositories ---

    val sourceRepository: SourceRepository by lazy {
        RoomSourceRepository(database.sourceDao())
    }

    val libraryRepository: LibraryRepository by lazy {
        RoomLibraryRepository(database.videoDao(), database.channelDao(), database.playlistDao())
    }

    // --- Scanning ---

    fun scanEngineFor(sourceType: SourceType): ScanEngine = ScanEngine(
        fileSystem = fileSystemFor(sourceType),
        metadataParser = metadataParser,
        videoDao = database.videoDao(),
        channelDao = database.channelDao(),
        playlistDao = database.playlistDao(),
    )

    fun thumbnailFallbackGeneratorFor(sourceType: SourceType): ThumbnailFallbackGenerator =
        ThumbnailFallbackGenerator(
            appContext = appContext,
            fileSystem = fileSystemFor(sourceType),
            videoDao = database.videoDao(),
        )

    // --- Player ---

    val sourceResolver = SourceResolver { sourceId ->
        kotlinx.coroutines.flow.first(sourceRepository.observeAllSources()) { it.id == sourceId }
    }

    val playerFactory: LocalTubePlayerFactory by lazy {
        LocalTubePlayerFactory(appContext, fileSystemsByType, sourceResolver)
    }

    // --- Images ---

    val imageLoader: coil.ImageLoader by lazy {
        coil.ImageLoader.Builder(appContext)
            .components {
                add(dev.hudater.localtube.ui.ThumbnailFetcher.Factory(fileSystemsByType, sourceResolver))
            }
            .build()
    }
}
