package dev.hudater.localtube.ui

import dev.hudater.localtube.core.model.SourceId
import dev.hudater.localtube.core.player.LocalTubeMediaItems
import java.io.File

/**
 * A stored `thumbnailPath` is one of two shapes:
 *  - a relative path within a source (sidecar .jpg/.webp found during
 *    scanning) — needs [ThumbnailFetcher] via a `localtube://` URI
 *  - an absolute path into the app's cache dir (frame-extraction
 *    fallback from [dev.hudater.localtube.core.data.scan.ThumbnailFallbackGenerator])
 *    — loads as a plain local file
 *
 * This function picks the right Coil model for either case so screens
 * don't need to know the distinction.
 */
fun resolveThumbnailModel(sourceId: SourceId, thumbnailPath: String?): Any? {
    if (thumbnailPath == null) return null
    return if (thumbnailPath.startsWith("/")) {
        File(thumbnailPath)
    } else {
        LocalTubeMediaItems.toUri(sourceId, thumbnailPath)
    }
}
