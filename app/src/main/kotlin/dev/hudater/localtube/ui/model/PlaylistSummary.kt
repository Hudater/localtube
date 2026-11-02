package dev.hudater.localtube.ui.model

/**
 * One Playlists-tab row: the playlist plus the preview bits derived
 * from its videos (video count + first-video thumbnail). The playlist
 * model itself carries neither, so they're computed here at observe
 * time rather than widening the core model for a UI concern.
 */
data class PlaylistSummary(
    val playlist: dev.hudater.localtube.core.model.Playlist,
    val videoCount: Int,
    val previewThumbnailModel: Any?,
)