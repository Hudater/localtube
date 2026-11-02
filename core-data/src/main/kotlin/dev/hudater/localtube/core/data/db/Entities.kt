package dev.hudater.localtube.core.data.db

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(tableName = "sources")
data class SourceEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val type: String, // SourceType.name — see Converters if this grows past a simple enum
    val path: String,
    val label: String,
    val hidden: Boolean = false,
    // Last per-source scan failure, recorded by ScanWorker (null = last
    // scan of this source completed). Surfaced in the UI, because a
    // failed source was previously indistinguishable from an empty one —
    // see docs/ARCHITECTURE.md, "Scan failure vs empty folder".
    val lastScanError: String? = null,
    // SMB-specific columns. Null for LOCAL/NFS sources.
    // Credentials are NOT stored here — see docs/ARCHITECTURE.md,
    // "Credential storage". Only non-secret connection shape lives in Room.
    val smbHost: String? = null,
    val smbShareName: String? = null,
    val smbRemotePath: String? = null,
    val smbUsername: String? = null,
)

@Entity(tableName = "channels")
data class ChannelEntity(
    @PrimaryKey val id: String, // yt-dlp channel_id / uploader_id
    val name: String,
    val thumbnailPath: String?,
    // Absolute path of the avatar cached app-internally by
    // ChannelIconWorker; null until that best-effort pass fetches one.
    val iconPath: String? = null,
)

@Entity(
    tableName = "playlists",
    foreignKeys = [
        ForeignKey(
            entity = SourceEntity::class,
            parentColumns = ["id"],
            childColumns = ["sourceId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("sourceId"), Index("channelId")],
)
data class PlaylistEntity(
    @PrimaryKey val id: String, // yt-dlp playlist_id
    val sourceId: Long,
    val channelId: String?,
    val name: String,
    val thumbnailPath: String?,
)

@Entity(
    tableName = "videos",
    foreignKeys = [
        ForeignKey(
            entity = SourceEntity::class,
            parentColumns = ["id"],
            childColumns = ["sourceId"],
            onDelete = ForeignKey.CASCADE,
        ),
        ForeignKey(
            entity = PlaylistEntity::class,
            parentColumns = ["id"],
            childColumns = ["playlistId"],
            onDelete = ForeignKey.SET_NULL,
        ),
    ],
    indices = [Index("sourceId"), Index("channelId"), Index("playlistId")],
)
data class VideoEntity(
    @PrimaryKey val id: String, // platform video id if known, else a derived stable id — see ScanEngine
    val sourceId: Long,
    val channelId: String?,
    val playlistId: String?,
    val playlistIndex: Int?,
    @ColumnInfo(name = "file_path") val filePath: String,
    val title: String,
    val durationSeconds: Long?,
    val thumbnailPath: String?,
    val addedAtEpochMillis: Long,
    // Used by the scan engine's diff pass to detect changed files without
    // hashing — see docs/ARCHITECTURE.md, "Scan diffing strategy".
    val fileSizeBytes: Long,
    val fileLastModifiedEpochMillis: Long,
    // yt-dlp --write-description sidecar text, truncated by ScanEngine
    // (display-only); null when the download has no .description sidecar.
    val description: String? = null,
    // Size+mtime fingerprints of the .info.json and .description sidecars
    // as of the last scan that read them (null for rows from before this
    // schema, acquired on the next scan's single full pass). These gate
    // ScanEngine's sidecar re-reads: an unchanged sidecar is never read
    // at all — see docs/ARCHITECTURE.md, "Sidecar fingerprinting".
    val infoJsonSizeBytes: Long? = null,
    val infoJsonLastModifiedEpochMillis: Long? = null,
    val descriptionSizeBytes: Long? = null,
    val descriptionLastModifiedEpochMillis: Long? = null,
    // Fingerprint of the thumbnail sidecar, same contract as above: an
    // unchanged thumbnail is never re-derived, and an app-internal
    // extraction path written by ThumbnailFallbackWorker is carried
    // across scans instead of being stomped by the SAF-relative path.
    val thumbnailSizeBytes: Long? = null,
    val thumbnailLastModifiedEpochMillis: Long? = null,
)
