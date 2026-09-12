package dev.hudater.localtube.core.model

/**
 * A YouTube (or other platform) channel, deduplicated by [ChannelId]
 * (yt-dlp's `channel_id` / `uploader_id`) across every [Source] it
 * appears in — NOT by display name, since names collide across
 * unrelated channels far more often than IDs do.
 */
data class Channel(
    val id: ChannelId,
    val name: String,
    val thumbnailPath: String?,
    // Absolute path of the avatar cached app-internally by
    // [dev.hudater.localtube.core.data.scan.ChannelIconWorker]; null
    // until that best-effort pass fetches one.
    val iconPath: String? = null,
)

@JvmInline
value class ChannelId(val value: String)

data class Playlist(
    val id: PlaylistId,
    val sourceId: SourceId,
    val channelId: ChannelId?,
    val name: String,
    val thumbnailPath: String?,
)

@JvmInline
value class PlaylistId(val value: String)
