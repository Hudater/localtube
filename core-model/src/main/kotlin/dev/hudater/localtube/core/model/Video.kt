package dev.hudater.localtube.core.model

/**
 * A single playable video, resolved from a file on disk (or over SMB/NFS)
 * plus whatever sidecar metadata was found for it.
 *
 * [playlistId] is null for a standalone video — see docs/ARCHITECTURE.md,
 * "Videos vs Playlists tab", for why this distinction drives the UI.
 */
data class Video(
    val id: VideoId,
    val sourceId: SourceId,
    val channelId: ChannelId?,
    val playlistId: PlaylistId?,
    val playlistIndex: Int?,
    val filePath: String,
    val title: String,
    val durationSeconds: Long?,
    val thumbnailPath: String?,
    val addedAtEpochMillis: Long,
)

/**
 * Stable identity for a video. Prefer the yt-dlp `id` field (the
 * platform's own video ID) when metadata is available; falls back to a
 * path-derived value when it isn't. See core-metadata for how this is
 * derived.
 */
@JvmInline
value class VideoId(val value: String)
