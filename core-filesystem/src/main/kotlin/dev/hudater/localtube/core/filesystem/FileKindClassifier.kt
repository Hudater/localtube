package dev.hudater.localtube.core.filesystem

/**
 * Classifies a filename by extension into a [FileKind]. Shared by every
 * [SourceFileSystem] implementation so "what counts as a video" is
 * defined in exactly one place.
 */
object FileKindClassifier {

    private val videoExtensions = setOf("mp4", "mkv", "webm", "avi", "mov", "m4v", "ts")
    private val thumbnailExtensions = setOf("jpg", "jpeg", "webp", "png")

    fun classify(filename: String): FileKind {
        val lower = filename.lowercase()
        return when {
            lower.endsWith(".info.json") -> FileKind.INFO_JSON
            thumbnailExtensions.any { lower.endsWith(".$it") } -> FileKind.THUMBNAIL
            videoExtensions.any { lower.endsWith(".$it") } -> FileKind.VIDEO
            else -> FileKind.OTHER
        }
    }

    /**
     * The "base name" two sibling files share, e.g. "My Video" for both
     * "My Video.mp4" and "My Video.info.json". Used by the scan engine to
     * group a video with its sidecars.
     */
    fun baseName(filename: String): String {
        return when {
            filename.endsWith(".info.json", ignoreCase = true) ->
                filename.dropLast(".info.json".length)
            else -> filename.substringBeforeLast('.')
        }
    }
}
