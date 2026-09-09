package dev.hudater.localtube.core.metadata

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Mirrors the fields yt-dlp writes to a video's `.info.json` sidecar.
 *
 * THIS IS THE ONE FILE TO EDIT if the actual .info.json files produced by
 * YTDLnis / Pinchflat / a future tool don't match what's assumed here.
 *
 * Field names below come from yt-dlp's documented "Output template"
 * entry variables (channel, channel_id, playlist, playlist_index,
 * playlist_id, duration, thumbnail, id, title, uploader, uploader_id).
 * They have NOT yet been verified against a real info.json file produced
 * by your setup — see docs/ARCHITECTURE.md, "Open item: verify info.json
 * shape", for the one-time check you should do before trusting this in
 * production.
 *
 * Every field is nullable/has a default on purpose: real-world info.json
 * files are inconsistent (private videos, playlist-less standalone
 * videos, older yt-dlp versions, etc). A missing field should never
 * crash the parser — see [YtDlpMetadataParser].
 *
 * `ignoreUnknownKeys = true` is set on the Json instance in
 * [YtDlpMetadataParser] so new/unexpected fields in real files never
 * break parsing — we just don't read them.
 */
@Serializable
data class YtDlpInfoJson(
    /** Platform video ID, e.g. the YouTube "v=" value. */
    @SerialName("id") val id: String? = null,
    @SerialName("title") val title: String? = null,
    @SerialName("duration") val durationSeconds: Double? = null,

    // --- Channel identity ---
    // yt-dlp docs: "channel" falls back to uploader, "channel_id" falls
    // back to uploader_id, when the extractor doesn't set the channel
    // fields directly. We read both and prefer channel/channel_id.
    @SerialName("channel") val channel: String? = null,
    @SerialName("channel_id") val channelId: String? = null,
    @SerialName("uploader") val uploader: String? = null,
    @SerialName("uploader_id") val uploaderId: String? = null,

    // --- Playlist context (present only when downloaded as part of a playlist) ---
    @SerialName("playlist") val playlistName: String? = null,
    @SerialName("playlist_id") val playlistId: String? = null,
    @SerialName("playlist_index") val playlistIndex: Int? = null,

    // --- Thumbnail ---
    // yt-dlp usually writes a separate thumbnail file (video.jpg/.webp)
    // rather than embedding it here, but the URL is kept for completeness
    // / a future remote-thumbnail fallback.
    @SerialName("thumbnail") val thumbnailUrl: String? = null,

    // --- Timestamps ---
    @SerialName("epoch") val scrapedAtEpochSeconds: Long? = null,
    @SerialName("upload_date") val uploadDate: String? = null,
)
