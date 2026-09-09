package dev.hudater.localtube.core.data.scan

import android.media.MediaDataSource
import dev.hudater.localtube.core.filesystem.RandomAccessHandle

/**
 * Bridges our [RandomAccessHandle] (implemented per source type in
 * core-filesystem) to Android's [MediaDataSource], which is what
 * [android.media.MediaMetadataRetriever] needs for non-file sources like
 * SMB streams.
 *
 * Kept in core-data rather than core-filesystem because it's a
 * `android.media`-specific adapter, not a filesystem concern — the
 * filesystem module shouldn't need to know MediaMetadataRetriever exists.
 */
class RandomAccessMediaDataSource(
    private val handle: RandomAccessHandle,
) : MediaDataSource() {

    override fun getSize(): Long = handle.lengthBytes

    override fun readAt(position: Long, buffer: ByteArray, offset: Int, size: Int): Int =
        handle.readAt(position, buffer, offset, size)

    override fun close() {
        handle.close()
    }
}
