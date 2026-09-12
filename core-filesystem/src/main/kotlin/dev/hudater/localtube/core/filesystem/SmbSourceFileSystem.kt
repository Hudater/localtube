package dev.hudater.localtube.core.filesystem

import com.hierynomus.msdtyp.AccessMask
import com.hierynomus.mssmb2.SMB2CreateDisposition
import com.hierynomus.mssmb2.SMB2ShareAccess
import com.hierynomus.smbj.SMBClient
import com.hierynomus.smbj.auth.AuthenticationContext
import com.hierynomus.smbj.share.DiskShare
import dev.hudater.localtube.core.model.Source
import dev.hudater.localtube.core.model.SourceType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.InputStream
import java.util.EnumSet

/**
 * [SourceFileSystem] for a [SourceType.SMB] source, backed by `smbj`.
 *
 * Credential lookup is delegated to [SmbCredentialProvider] rather than
 * reading [Source.smbConnectionInfo] directly, because credentials
 * themselves are stored in EncryptedSharedPreferences by the app layer
 * (see docs/ARCHITECTURE.md, "Credential storage") — this module only
 * knows it needs *a* username/password, not where they're persisted.
 */
class SmbSourceFileSystem(
    private val credentialProvider: SmbCredentialProvider,
) : SourceFileSystem {

    private val client = SMBClient()

    override suspend fun listFiles(source: Source): List<RemoteFileEntry> =
        withContext(Dispatchers.IO) {
            withShare(source) { share ->
                val info = requireSmbInfo(source)
                buildList {
                    walk(share, info.remotePath, relativePrefix = "") { add(it) }
                }
            }
        }

    private fun walk(
        share: DiskShare,
        remoteDir: String,
        relativePrefix: String,
        onFile: (RemoteFileEntry) -> Unit,
    ) {
        for (info in share.list(remoteDir)) {
            val name = info.fileName
            if (name == "." || name == "..") continue

            val remoteChildPath = if (remoteDir.isEmpty()) name else "$remoteDir\\$name"
            val relativeChildPath = if (relativePrefix.isEmpty()) name else "$relativePrefix/$name"

            val isDirectory = info.fileAttributes and 0x10L != 0L // FILE_ATTRIBUTE_DIRECTORY
            if (isDirectory) {
                walk(share, remoteChildPath, relativeChildPath, onFile)
            } else {
                onFile(
                    RemoteFileEntry(
                        relativePath = relativeChildPath,
                        sizeBytes = info.endOfFile,
                        lastModifiedEpochMillis = info.lastWriteTime.toEpochMillis(),
                        kind = FileKindClassifier.classify(name),
                    )
                )
            }
        }
    }

    override suspend fun openInputStream(source: Source, relativePath: String): InputStream =
        withContext(Dispatchers.IO) {
            // A short-lived connection is fine for one-shot sidecar reads
            // (info.json / thumbnails). Video playback uses
            // openRandomAccess instead, which keeps the connection open
            // for the life of the playback session — see core-player.
            val info = requireSmbInfo(source)
            val connection = client.connect(info.host)
            val session = connection.authenticate(credentialProvider.authenticationFor(source))
            val share = session.connectShare(info.shareName) as DiskShare
            val remotePath = toRemotePath(info, relativePath)

            val file = share.openFile(
                remotePath,
                EnumSet.of(AccessMask.GENERIC_READ),
                null,
                SMB2ShareAccess.ALL,
                SMB2CreateDisposition.FILE_OPEN,
                null,
            )
            // Wrap so closing the InputStream also tears down the
            // session/connection instead of leaking them.
            ClosingInputStream(file.inputStream, onClose = {
                file.close()
                share.close()
                session.close()
                connection.close()
            })
        }

    override fun openRandomAccess(source: Source, relativePath: String): RandomAccessHandle {
        val info = requireSmbInfo(source)
        val connection = client.connect(info.host)
        val session = connection.authenticate(credentialProvider.authenticationFor(source))
        val share = session.connectShare(info.shareName) as DiskShare
        val remotePath = toRemotePath(info, relativePath)

        val file = share.openFile(
            remotePath,
            EnumSet.of(AccessMask.GENERIC_READ),
            null,
            SMB2ShareAccess.ALL,
            SMB2CreateDisposition.FILE_OPEN,
            null,
        )

        return SmbRandomAccessHandle(file, share, session, connection)
    }

    override suspend fun isReachable(source: Source): Boolean = withContext(Dispatchers.IO) {
        try {
            withShare(source) { true }
        } catch (_: Exception) {
            false
        }
    }

    override suspend fun deleteFile(source: Source, relativePath: String): Unit =
        withContext(Dispatchers.IO) {
            withShare(source) { share ->
                val info = requireSmbInfo(source)
                val remotePath = toRemotePath(info, relativePath)
                val file = share.openFile(
                    remotePath,
                    EnumSet.of(AccessMask.DELETE),
                    null,
                    SMB2ShareAccess.ALL,
                    SMB2CreateDisposition.FILE_OPEN,
                    null,
                )
                file.deleteOnClose()
                file.close()
            }
        }

    private fun <T> withShare(source: Source, block: (DiskShare) -> T): T {
        val info = requireSmbInfo(source)
        client.connect(info.host).use { connection ->
            val session = connection.authenticate(credentialProvider.authenticationFor(source))
            session.connectShare(info.shareName).use { share ->
                return block(share as DiskShare)
            }
        }
    }

    private fun requireSmbInfo(source: Source) =
        source.smbConnectionInfo
            ?: error("SmbSourceFileSystem given a source with no smbConnectionInfo: ${source.label}")

    private fun toRemotePath(info: dev.hudater.localtube.core.model.SmbConnectionInfo, relativePath: String): String {
        val base = info.remotePath.trim('\\', '/')
        val normalizedRelative = relativePath.replace("/", "\\")
        return if (base.isEmpty()) normalizedRelative else "$base\\$normalizedRelative"
    }

    private class SmbRandomAccessHandle(
        private val file: com.hierynomus.smbj.share.File,
        private val share: DiskShare,
        private val session: com.hierynomus.smbj.session.Session,
        private val connection: com.hierynomus.smbj.connection.Connection,
    ) : RandomAccessHandle {
        override val lengthBytes: Long get() = file.fileInformation.standardInformation.endOfFile

        override fun readAt(position: Long, buffer: ByteArray, offset: Int, length: Int): Int =
            file.read(buffer, position, offset, length)

        override fun close() {
            file.close()
            share.close()
            session.close()
            connection.close()
        }
    }

    private class ClosingInputStream(
        private val delegate: InputStream,
        private val onClose: () -> Unit,
    ) : InputStream() {
        override fun read(): Int = delegate.read()
        override fun read(b: ByteArray, off: Int, len: Int): Int = delegate.read(b, off, len)
        override fun close() {
            delegate.close()
            onClose()
        }
    }
}

/**
 * Supplies smbj auth for a given [Source]. Implemented in the app module
 * (see :app's di package), which is where EncryptedSharedPreferences
 * lookup happens — kept out of this module so core-filesystem never
 * needs to know about Android's security/crypto APIs directly.
 */
fun interface SmbCredentialProvider {
    fun authenticationFor(source: Source): AuthenticationContext
}

private fun com.hierynomus.msdtyp.FileTime.toEpochMillis(): Long = this.toDate().time
