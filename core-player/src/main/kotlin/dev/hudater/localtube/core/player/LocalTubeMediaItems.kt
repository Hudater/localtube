package dev.hudater.localtube.core.player

import android.net.Uri
import androidx.media3.common.MediaItem
import dev.hudater.localtube.core.model.Source
import dev.hudater.localtube.core.model.SourceId
import dev.hudater.localtube.core.model.Video

/**
 * ExoPlayer's [MediaItem] carries a [Uri]. We don't have a real URL for
 * SMB/local-SAF content, so we encode just enough into a custom
 * `localtube://` URI for [SourceFileSystemDataSource] to reconstruct a
 * [Source] + relative path — NOT the full [Source] object (credentials
 * must never end up in a URI that could be logged).
 *
 * The SourceFileSystemDataSourceFactory is constructed per-source-type
 * already knowing which [SourceFileSystem] (and therefore which
 * credentials) to use — see [LocalTubePlayerFactory] — so this URI only
 * needs to carry the source id and relative path, both non-secret.
 */
object LocalTubeMediaItems {

    private const val SCHEME = "localtube"

    fun buildMediaItem(source: Source, video: Video): MediaItem {
        val uri = toUri(source, video.filePath)
        return MediaItem.Builder()
            .setUri(uri)
            .setMediaId(video.id.value)
            .build()
    }

    fun toUri(sourceId: SourceId, relativePath: String): Uri =
        Uri.Builder()
            .scheme(SCHEME)
            .authority(sourceId.value.toString())
            .path("/$relativePath")
            .build()

    fun toUri(source: Source, relativePath: String): Uri = toUri(source.id, relativePath)

    fun sourceIdFrom(uri: Uri): SourceId = SourceId(uri.authority?.toLongOrNull() ?: -1)
    fun relativePathFrom(uri: Uri): String = uri.path.orEmpty().removePrefix("/")
}
