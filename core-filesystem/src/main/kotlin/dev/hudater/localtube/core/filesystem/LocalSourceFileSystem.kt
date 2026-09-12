package dev.hudater.localtube.core.filesystem

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import dev.hudater.localtube.core.model.Source
import dev.hudater.localtube.core.model.SourceType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.FileInputStream
import java.io.FileNotFoundException
import java.io.InputStream
import java.nio.ByteBuffer

/**
 * [SourceFileSystem] for a [SourceType.LOCAL] source: a folder picked via
 * Storage Access Framework, addressed by [Source.path] (a SAF tree URI
 * string).
 */
class LocalSourceFileSystem(
    private val appContext: Context,
) : SourceFileSystem {

    override suspend fun listFiles(source: Source): List<RemoteFileEntry> =
        withContext(Dispatchers.IO) {
            require(source.type == SourceType.LOCAL) {
                "LocalSourceFileSystem given a non-LOCAL source: ${source.type}"
            }
            val treeUri = Uri.parse(source.path)
            val root = DocumentFile.fromTreeUri(appContext, treeUri)
            if (root == null) {
                // Empty (not an error) is a trap: a stale or revoked tree
                // URI must be loud, or a broken source looks like an empty
                // folder and the scan "succeeds" with zero rows.
                android.util.Log.w("LocalTubeScan", "Tree URI not accessible for '${source.label}': ${source.path}")
                return@withContext emptyList()
            }

            buildList {
                walk(root, relativePrefix = "") { entry -> add(entry) }
            }
        }

    private fun walk(
        dir: DocumentFile,
        relativePrefix: String,
        onFile: (RemoteFileEntry) -> Unit,
    ) {
        for (child in dir.listFiles()) {
            val name = child.name ?: continue
            val relativePath = if (relativePrefix.isEmpty()) name else "$relativePrefix/$name"
            if (child.isDirectory) {
                walk(child, relativePath, onFile)
            } else {
                onFile(
                    RemoteFileEntry(
                        relativePath = relativePath,
                        sizeBytes = child.length(),
                        lastModifiedEpochMillis = child.lastModified(),
                        kind = FileKindClassifier.classify(name),
                    )
                )
            }
        }
    }

    override suspend fun openInputStream(source: Source, relativePath: String): InputStream =
        withContext(Dispatchers.IO) {
            val documentUri = resolveDocumentUri(source, relativePath)
            appContext.contentResolver.openInputStream(documentUri)
                ?: throw FileNotFoundException("Could not open $relativePath under ${source.label}")
        }

    override fun openRandomAccess(source: Source, relativePath: String): RandomAccessHandle {
        val documentUri = resolveDocumentUri(source, relativePath)
        val pfd = appContext.contentResolver.openFileDescriptor(documentUri, "r")
            ?: throw FileNotFoundException("Could not open $relativePath under ${source.label}")
        return LocalRandomAccessHandle(pfd)
    }

    override suspend fun isReachable(source: Source): Boolean = withContext(Dispatchers.IO) {
        val treeUri = Uri.parse(source.path)
        DocumentFile.fromTreeUri(appContext, treeUri)?.exists() == true
    }

    override suspend fun deleteFile(source: Source, relativePath: String): Unit =
        withContext(Dispatchers.IO) {
            val documentUri = try {
                resolveDocumentUri(source, relativePath)
            } catch (e: FileNotFoundException) {
                return@withContext // already gone — treat as deleted
            }
            if (!android.provider.DocumentsContract.deleteDocument(appContext.contentResolver, documentUri)) {
                throw java.io.IOException("Provider refused to delete $relativePath under ${source.label}")
            }
        }

    /**
     * Walks [relativePath] segment by segment under the source's tree URI
     * to find the matching DocumentFile, then returns its document URI.
     *
     * NOTE: this is O(depth) DocumentsContract calls per lookup. Fine for
     * "open this one video to play it", too slow to call in a loop — the
     * scan engine should rely on [listFiles] output instead of calling
     * this repeatedly.
     */
    private fun resolveDocumentUri(source: Source, relativePath: String): Uri {
        val treeUri = Uri.parse(source.path)
        var current = DocumentFile.fromTreeUri(appContext, treeUri)
            ?: throw FileNotFoundException("Source root not accessible: ${source.label}")

        for (segment in relativePath.split("/")) {
            current = current.findFile(segment)
                ?: throw FileNotFoundException("$relativePath not found under ${source.label}")
        }
        return current.uri
    }

    private class LocalRandomAccessHandle(
        pfd: android.os.ParcelFileDescriptor,
    ) : RandomAccessHandle {
        // RandomAccessFile can't be constructed from a ParcelFileDescriptor
        // on Android (the (FileDescriptor, String) overload is stubbed out
        // of android.jar), so use a FileInputStream on the underlying fd
        // and seek via its channel.
        private val stream = FileInputStream(pfd.fileDescriptor)
        private val channel = stream.channel
        private val pfd = pfd

        override val lengthBytes: Long get() = channel.size()

        override fun readAt(position: Long, buffer: ByteArray, offset: Int, length: Int): Int {
            channel.position(position)
            return channel.read(ByteBuffer.wrap(buffer, offset, length))
        }

        override fun close() {
            channel.close()
            pfd.close()
        }
    }
}
