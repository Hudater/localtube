package dev.hudater.localtube.core.data.repository

import dev.hudater.localtube.core.data.db.ChannelDao
import dev.hudater.localtube.core.data.db.PlaylistDao
import dev.hudater.localtube.core.data.db.VideoDao
import dev.hudater.localtube.core.data.db.toDomain
import dev.hudater.localtube.core.model.Channel
import dev.hudater.localtube.core.model.ChannelId
import dev.hudater.localtube.core.model.Playlist
import dev.hudater.localtube.core.model.PlaylistId
import dev.hudater.localtube.core.model.SourceId
import dev.hudater.localtube.core.model.Video
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * Read-side repository for browsing already-scanned content: the home
 * feed, the channel avatar strip, and a channel's Videos/Playlists tabs.
 *
 * Writing scanned content is ScanEngine's job, not this repository's —
 * keeping "browse" and "index" concerns in separate classes so the
 * ViewModel layer only ever depends on the (much simpler) read contract.
 */
interface LibraryRepository {
    /** Home feed: random order, scoped to selected sources and optional channel filter. */
    fun observeFeed(sourceIds: List<SourceId>, channelId: ChannelId?): Flow<List<Video>>

    /** Channel avatar strip, deduplicated by channel id across the given sources. */
    fun observeChannels(sourceIds: List<SourceId>): Flow<List<Channel>>

    /** Channel page "Videos" tab: standalone videos only. */
    fun observeStandaloneVideos(sourceIds: List<SourceId>, channelId: ChannelId?): Flow<List<Video>>

    /** Channel page "Playlists" tab. */
    fun observePlaylists(sourceIds: List<SourceId>, channelId: ChannelId?): Flow<List<Playlist>>

    /** Videos inside one playlist, in playlist order. */
    fun observePlaylistVideos(playlistId: PlaylistId): Flow<List<Video>>

    suspend fun getVideo(videoId: dev.hudater.localtube.core.model.VideoId): Video?
}

class RoomLibraryRepository(
    private val videoDao: VideoDao,
    private val channelDao: ChannelDao,
    private val playlistDao: PlaylistDao,
) : LibraryRepository {

    override fun observeFeed(sourceIds: List<SourceId>, channelId: ChannelId?): Flow<List<Video>> =
        videoDao.observeFeed(sourceIds.map { it.value }, channelId?.value)
            .map { list -> list.map { it.toDomain() } }

    override fun observeChannels(sourceIds: List<SourceId>): Flow<List<Channel>> =
        channelDao.observeChannelsForSources(sourceIds.map { it.value })
            .map { list -> list.map { it.toDomain() } }

    override fun observeStandaloneVideos(
        sourceIds: List<SourceId>,
        channelId: ChannelId?,
    ): Flow<List<Video>> =
        videoDao.observeStandaloneVideos(sourceIds.map { it.value }, channelId?.value)
            .map { list -> list.map { it.toDomain() } }

    override fun observePlaylists(
        sourceIds: List<SourceId>,
        channelId: ChannelId?,
    ): Flow<List<Playlist>> =
        playlistDao.observePlaylists(sourceIds.map { it.value }, channelId?.value)
            .map { list -> list.map { it.toDomain() } }

    override fun observePlaylistVideos(playlistId: PlaylistId): Flow<List<Video>> =
        videoDao.observePlaylistVideos(playlistId.value)
            .map { list -> list.map { it.toDomain() } }

    override suspend fun getVideo(videoId: dev.hudater.localtube.core.model.VideoId): Video? =
        videoDao.getById(videoId.value)?.toDomain()
}
