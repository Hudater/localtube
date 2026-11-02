package dev.hudater.localtube.core.filesystem

import android.content.Context
import android.database.Cursor
import android.net.Uri
import android.provider.DocumentsContract
import androidx.documentfile.provider.DocumentFile
import dev.hudater.localtube.core.model.Source
import dev.hudater.localtube.core.model.SourceType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.io.FileInputStream
import java.io.FileNotFoundException
import java.io.InputStream
import java.nio.ByteBuffer

/**
 * [SourceFileSystem] for a [SourceType.LOCAL] source: a folder picked via
 * Storage Access Framework, addressed by [Source.path] (a SAF tree URI
 * string).
 *
 * The walk queries DocumentsContract directly — one content-resolver
 * query per DIRECTORY carrying every child's name/size/mtime in its
 * cursor — rather than through [DocumentFile], which issues separate
 * provider round-trips per child per property (several times the file
 * count per scan). For a few-thousand-file library that is the
 * difference between a walk of seconds and one of minutes. Subtree walk
 * is parallel (bounded).
 *
 * Unreadable/revoked sources throw [SourceUnavailableException]: an
 * *empty* listing means the folder itself is genuinely empty, never
 * that the permission was lost.
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
            val treeDocumentId = DocumentsContract.getTreeDocumentId(treeUri)
            // fromTreeUri is nearly free and catches a malformed tree URI;
            // exists() forces one provider round-trip that fails exactly
            // when the persisted grant was revoked — the "worked before,
            // now gone" failure mode. Loud, not silent.
            val root = DocumentFile.fromTreeUri(appContext, treeUri)
            if (root == null || !root.exists()) {
                throw SourceUnavailableException(
                    "Folder not accessible for '${source.label}' — its SAF grant was " +
                        "revoked or the folder moved. Re-select it in Sources.",
                )
            }
            collectFiles(treeUri, treeDocumentId)
        }

    /**
     * Recursively queries each directory's children in parallel. Each
     * directory costs exactly ONE provider query whose cursor carries
     * display name, size, last-modified, and the dir flag for every
     * child at once.
     */
    private suspend fun collectFiles(treeUri: Uri, rootDocumentId: String): List<RemoteFileEntry> =
        coroutineScope {
            val io = Dispatchers.IO
            val recursionPermits = Semaphore(6)
            val collectMutex = Mutex()
            val files = mutableListOf<RemoteFileEntry>()

            suspend fun walkDir(documentId: String, prefix: String) {
                for (child in queryChildren(treeUri, documentId)) {
                    val relativePath = if (prefix.isEmpty()) child.name else "$prefix/${child.name}"
                    if (child.isDirectory) {
                        launch(io) {
                            recursionPermits.withPermit {
                                walkDir(child.documentId, relativePath)
                            }
                        }
                    } else {
                        collectMutex.withLock {
                            files += RemoteFileEntry(
                                relativePath = relativePath,
                                sizeBytes = child.sizeBytes,
                                lastModifiedEpochMillis = child.lastModifiedEpochMillis,
                                kind = FileKindClassifier.classify(child.name),
                            )
                        }
                    }
                }
            }

            // The picked folder is the root: list only its children.
            recursionPermits.withPermit { walkDir(rootDocumentId, prefix = "") }
            files
        }

    private data class SafChild(
        val documentId: String,
        val name: String,
        val isDirectory: Boolean,
        val sizeBytes: Long,
        val lastModifiedEpochMillis: Long,
    )

    /** One cursor per directory: every child's metadata in a single round-trip. */
    private fun queryChildren(treeUri: Uri, parentDocumentId: String): List<SafChild> {
        val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(
            treeUri,
            parentDocumentId,
        )
        val result = mutableListOf<SafChild>()
        appContext.contentResolver.query(
            childrenUri,
            arrayOf(
                DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                DocumentsContract.Document.COLUMN_MIME_TYPE,
                DocumentsContract.Document.COLUMN_SIZE,
                DocumentsContract.Document.COLUMN_LAST_MODIFIED,
            ),
            null,
            null,
            null,
        )?.use { cursor ->
            while (cursor.moveToNext()) {
                val name = cursor.stringOrNull(1) ?: continue // same fallback as DocumentFile.name
                result += SafChild(
                    documentId = cursor.getString(0),
                    name = name,
                    isDirectory = DocumentsContract.Document.MIME_TYPE_DIR == cursor.stringOrNull(2),
                    sizeBytes = cursor.getLong(3),
                    lastModifiedEpochMillis = cursor.getLong(4),
                )
            }
        }
        return result
    }

    private fun Cursor.stringOrNull(column: Int): String? =
        if (isNull(column)) null else getString(column)

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
            if (!DocumentsContract.deleteDocument(appContext.contentResolver, documentUri)) {
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
