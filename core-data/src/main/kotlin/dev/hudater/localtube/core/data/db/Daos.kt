package dev.hudater.localtube.core.data.db

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface SourceDao {
    @Query("SELECT * FROM sources ORDER BY label")
    fun observeAll(): Flow<List<SourceEntity>>

    @Query("SELECT * FROM sources WHERE hidden = 0 ORDER BY label")
    fun observeVisible(): Flow<List<SourceEntity>>

    @Query("SELECT * FROM sources WHERE id = :id")
    suspend fun getById(id: Long): SourceEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(source: SourceEntity): Long

    @Update
    suspend fun update(source: SourceEntity)

    @Delete
    suspend fun delete(source: SourceEntity)
}

@Dao
interface ChannelDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(channel: ChannelEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(channels: List<ChannelEntity>)

    /**
     * Channels visible across a given set of source IDs, deduplicated by
     * channel id — this backs the avatar strip's cross-source dedup
     * behavior described in docs/ARCHITECTURE.md, "Channel dedup".
     */
    @Query(
        """
        SELECT DISTINCT c.* FROM channels c
        INNER JOIN videos v ON v.channelId = c.id
        WHERE v.sourceId IN (:sourceIds)
        ORDER BY c.name
        """
    )
    fun observeChannelsForSources(sourceIds: List<Long>): Flow<List<ChannelEntity>>

    @Query("SELECT * FROM channels WHERE id = :id")
    suspend fun getById(id: String): ChannelEntity?
}

@Dao
interface PlaylistDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(playlist: PlaylistEntity)

    @Query(
        """
        SELECT * FROM playlists
        WHERE sourceId IN (:sourceIds)
        AND (:channelId IS NULL OR channelId = :channelId)
        ORDER BY name
        """
    )
    fun observePlaylists(sourceIds: List<Long>, channelId: String?): Flow<List<PlaylistEntity>>

    @Query("SELECT * FROM playlists WHERE id = :id")
    suspend fun getById(id: String): PlaylistEntity?
}

@Dao
interface VideoDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(video: VideoEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(videos: List<VideoEntity>)

    @Query("DELETE FROM videos WHERE id IN (:ids)")
    suspend fun deleteByIds(ids: List<String>)

    @Query("SELECT id, fileSizeBytes, fileLastModifiedEpochMillis, addedAtEpochMillis, title, channelId FROM videos WHERE sourceId = :sourceId")
    suspend fun getKnownFileFingerprints(sourceId: Long): List<VideoFingerprint>

    @Query("SELECT * FROM videos WHERE thumbnailPath IS NULL")
    suspend fun videosWithoutThumbnail(): List<VideoEntity>

    @Query("UPDATE videos SET thumbnailPath = :path WHERE id = :id")
    suspend fun updateThumbnailPath(id: String, path: String)

    /**
     * Home feed: random order across whichever sources are selected,
     * optionally narrowed to one channel. See docs/ARCHITECTURE.md,
     * "Home feed ordering", for why this is RANDOM() rather than
     * chronological.
     */
    @Query(
        """
        SELECT * FROM videos
        WHERE sourceId IN (:sourceIds)
        AND (:channelId IS NULL OR channelId = :channelId)
        ORDER BY RANDOM()
        """
    )
    fun observeFeed(sourceIds: List<Long>, channelId: String?): Flow<List<VideoEntity>>

    /** Videos tab on a channel page: standalone videos only. */
    @Query(
        """
        SELECT * FROM videos
        WHERE sourceId IN (:sourceIds)
        AND playlistId IS NULL
        AND (:channelId IS NULL OR channelId = :channelId)
        ORDER BY addedAtEpochMillis DESC
        """
    )
    fun observeStandaloneVideos(sourceIds: List<Long>, channelId: String?): Flow<List<VideoEntity>>

    @Query("SELECT * FROM videos WHERE playlistId = :playlistId ORDER BY playlistIndex ASC")
    fun observePlaylistVideos(playlistId: String): Flow<List<VideoEntity>>

    @Query("SELECT * FROM videos WHERE id = :id")
    suspend fun getById(id: String): VideoEntity?
}

data class VideoFingerprint(
    val id: String,
    val fileSizeBytes: Long,
    val fileLastModifiedEpochMillis: Long,
    val addedAtEpochMillis: Long,
    val title: String,
    val channelId: String?,
)
