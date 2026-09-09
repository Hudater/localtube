package dev.hudater.localtube.core.filesystem

import dev.hudater.localtube.core.model.Source
import java.io.InputStream

/**
 * One entry found while listing a [Source]: either a video file or a
 * sidecar (thumbnail / info.json) sitting next to it. The scan engine in
 * core-data groups these by base filename.
 */
data class RemoteFileEntry(
    /** Path relative to the source root, forward-slash separated. */
    val relativePath: String,
    val sizeBytes: Long,
    val lastModifiedEpochMillis: Long,
    val kind: FileKind,
)

enum class FileKind {
    VIDEO,
    INFO_JSON,
    THUMBNAIL,
    OTHER,
}

/**
 * Abstraction over "list what's in this source" and "open a stream to
 * read one file from it", implemented once per [dev.hudater.localtube.core.model.SourceType].
 *
 * This interface is the seam to reimplement (not reuse) if this app's
 * logic is ever ported to run server-side for a web client — a server
 * would talk to SMB/NFS directly without Android's SAF constraints, but
 * would want the exact same method shapes so the scan engine in
 * core-data doesn't need to change.
 */
interface SourceFileSystem {
    /**
     * Lists every file under [source], recursively. Implementations
     * should NOT read file contents here except for the small sidecar
     * files (.info.json) the caller explicitly opens afterward via
     * [openInputStream] — listing must stay cheap even for large sources.
     */
    suspend fun listFiles(source: Source): List<RemoteFileEntry>

    /**
     * Opens a stream for a single file. Caller is responsible for closing
     * it. For SMB sources this holds a live network connection open for
     * the duration of the read — see core-player's SmbDataSource for how
     * ExoPlayer consumes this for video playback specifically (video
     * playback does NOT go through this method directly; it uses a
     * lower-level seekable handle — see [SourceFileSystem.openRandomAccess]).
     */
    suspend fun openInputStream(source: Source, relativePath: String): InputStream

    /**
     * Opens a seekable handle for streaming playback (ExoPlayer needs
     * seek, not just sequential read). LOCAL sources can satisfy this
     * trivially via a file descriptor; SMB needs smbj's random-access
     * file handle.
     */
    fun openRandomAccess(source: Source, relativePath: String): RandomAccessHandle

    /** Quick reachability check, used to show the "source unavailable" state. */
    suspend fun isReachable(source: Source): Boolean
}

/**
 * Minimal seekable-read contract, implemented differently per source
 * type but consumed identically by core-player's custom DataSource.
 */
interface RandomAccessHandle : AutoCloseable {
    val lengthBytes: Long
    fun readAt(position: Long, buffer: ByteArray, offset: Int, length: Int): Int
}
