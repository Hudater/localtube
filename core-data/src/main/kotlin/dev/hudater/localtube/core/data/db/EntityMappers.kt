package dev.hudater.localtube.core.data.db

import dev.hudater.localtube.core.model.Channel
import dev.hudater.localtube.core.model.ChannelId
import dev.hudater.localtube.core.model.Playlist
import dev.hudater.localtube.core.model.PlaylistId
import dev.hudater.localtube.core.model.SmbConnectionInfo
import dev.hudater.localtube.core.model.Source
import dev.hudater.localtube.core.model.SourceId
import dev.hudater.localtube.core.model.SourceType
import dev.hudater.localtube.core.model.Video
import dev.hudater.localtube.core.model.VideoId

// Room entities stay private to :core-data — everything outside this
// module (including :app) works with core-model types only. This file is
// the single translation point between the two, so a Room schema change
// never leaks into UI code.

fun SourceEntity.toDomain(): Source = Source(
    id = SourceId(id),
    type = SourceType.valueOf(type),
    path = path,
    label = label,
    hidden = hidden,
    lastScanError = lastScanError,
    smbConnectionInfo = if (type == SourceType.SMB.name) {
        SmbConnectionInfo(
            host = smbHost.orEmpty(),
            shareName = smbShareName.orEmpty(),
            remotePath = smbRemotePath.orEmpty(),
            username = smbUsername,
            hasStoredCredentials = smbUsername != null,
        )
    } else null,
)

fun Source.toEntity(): SourceEntity = SourceEntity(
    id = id.value,
    type = type.name,
    path = path,
    label = label,
    hidden = hidden,
    lastScanError = lastScanError,
    smbHost = smbConnectionInfo?.host,
    smbShareName = smbConnectionInfo?.shareName,
    smbRemotePath = smbConnectionInfo?.remotePath,
    smbUsername = smbConnectionInfo?.username,
)

fun ChannelEntity.toDomain(): Channel = Channel(
    id = ChannelId(id),
    name = name,
    thumbnailPath = thumbnailPath,
    iconPath = iconPath,
)

fun PlaylistEntity.toDomain(): Playlist = Playlist(
    id = PlaylistId(id),
    sourceId = SourceId(sourceId),
    channelId = channelId?.let(::ChannelId),
    name = name,
    thumbnailPath = thumbnailPath,
)

fun VideoEntity.toDomain(): Video = Video(
    id = VideoId(id),
    sourceId = SourceId(sourceId),
    channelId = channelId?.let(::ChannelId),
    playlistId = playlistId?.let(::PlaylistId),
    playlistIndex = playlistIndex,
    filePath = filePath,
    title = title,
    durationSeconds = durationSeconds,
    thumbnailPath = thumbnailPath,
    addedAtEpochMillis = addedAtEpochMillis,
    description = description,
)
