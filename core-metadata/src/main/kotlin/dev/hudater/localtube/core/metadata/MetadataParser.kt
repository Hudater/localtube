package dev.hudater.localtube.core.metadata

/**
 * Everything the scan engine (core-data) needs about one video file,
 * derived from its sidecar metadata (or defaults when metadata is
 * missing/unreadable).
 *
 * Deliberately NOT [dev.hudater.localtube.core.model.Video] — this type
 * has no [dev.hudater.localtube.core.model.SourceId] or file path,
 * because a parser only knows about the JSON content, not where the file
 * lives. core-data's scan engine combines this with source/path
 * information to build the real [dev.hudater.localtube.core.model.Video].
 */
data class ParsedVideoMetadata(
    val platformVideoId: String?,
    val title: String?,
    val durationSeconds: Long?,
    val channelName: String?,
    val channelId: String?,
    val playlistName: String?,
    val playlistId: String?,
    val playlistIndex: Int?,
)

/**
 * Parses a metadata sidecar's raw text content into [ParsedVideoMetadata].
 *
 * This is an interface (not a concrete class used directly) so that:
 *  - a second downloader format can be supported later by adding another
 *    implementation without touching call sites
 *  - core-data's scan engine can be tested against a fake parser without
 *    needing real JSON fixtures
 */
interface MetadataParser {
    /**
     * @param rawJson the full text content of a `.info.json` file
     * @return parsed metadata, or null if [rawJson] could not be parsed
     *   at all (malformed JSON). Individual missing fields inside valid
     *   JSON do NOT cause null — they come back as null fields in
     *   [ParsedVideoMetadata] instead, so the scan engine can still index
     *   the video file itself using filename-derived fallbacks.
     */
    fun parse(rawJson: String): ParsedVideoMetadata?
}
