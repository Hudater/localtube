package dev.hudater.localtube.core.metadata

import kotlinx.serialization.json.Json

/**
 * [MetadataParser] for yt-dlp's `.info.json` sidecar format, as produced
 * by YTDLnis, Pinchflat, or plain yt-dlp with --write-info-json.
 *
 * If the actual JSON shape differs from [YtDlpInfoJson], fix the field
 * mappings there — this class should rarely need changes itself.
 */
class YtDlpMetadataParser : MetadataParser {

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        coerceInputValues = true
    }

    override fun parse(rawJson: String): ParsedVideoMetadata? {
        val raw = try {
            json.decodeFromString(YtDlpInfoJson.serializer(), rawJson)
        } catch (_: Exception) {
            // Malformed JSON. The scan engine falls back to
            // filename-derived metadata for this file — see
            // core-data's ScanEngine.
            return null
        }

        return ParsedVideoMetadata(
            platformVideoId = raw.id,
            title = raw.title,
            durationSeconds = raw.durationSeconds?.toLong(),
            // Prefer channel/channel_id; fall back to uploader/uploader_id
            // per yt-dlp's own documented fallback behavior.
            channelName = raw.channel ?: raw.uploader,
            channelId = raw.channelId ?: raw.uploaderId,
            playlistName = raw.playlistName,
            playlistId = raw.playlistId,
            playlistIndex = raw.playlistIndex,
        )
    }
}
