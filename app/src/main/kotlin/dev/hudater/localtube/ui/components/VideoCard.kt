package dev.hudater.localtube.ui.components

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import dev.hudater.localtube.core.model.Channel
import dev.hudater.localtube.core.model.Video
import dev.hudater.localtube.ui.resolveThumbnailModel

/** YouTube's muted gray for the byline/metadata line under a title. */
private val MetadataGray = Color(0xFFAAAAAA)

/**
 * The shared YouTube-style video row (modeled on the YouTube app
 * reference: full-width 16:9 thumbnail with a duration chip, then the
 * channel avatar + title + channel-name metadata below it). The home
 * feed, the channel page Videos tab, and PlaylistScreen all render rows
 * through this card so every browse list looks the same.
 *
 * [onLongClick] stays optional so screens without a destructive
 * long-press action (channel, playlist) simply omit it.
 *
 * [onChannelClick] is optional too: when set, the avatar and the channel
 * byline become their own link to the channel (a no-op long click keeps
 * the row's delete long-press from firing through it). Screens without a
 * channel to navigate to omit it.
 *
 * [channel] is null when the caller has no channel context — the avatar
 * and byline are dropped instead of leaving an empty gap.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun VideoCard(
    video: Video,
    channel: Channel?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    onLongClick: (() -> Unit)? = null,
    onChannelClick: (() -> Unit)? = null,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .combinedClickable(onClick = onClick, onLongClick = onLongClick),
    ) {
        Box {
            AsyncImage(
                model = resolveThumbnailModel(video.sourceId, video.thumbnailPath),
                contentDescription = video.title,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxWidth().aspectRatio(16f / 9f),
            )
            video.durationSeconds?.let { duration ->
                Text(
                    text = formatDuration(duration),
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .padding(4.dp)
                        .background(MaterialTheme.colorScheme.scrim.copy(alpha = 0.7f))
                        .padding(horizontal = 4.dp, vertical = 2.dp),
                    color = Color.White,
                    style = MaterialTheme.typography.labelSmall,
                )
            }
        }

        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (channel != null) {
                ChannelAvatar(
                    name = channel.name,
                    iconPath = channel.iconPath,
                    onClick = onChannelClick,
                )
            }
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = video.title,
                    style = MaterialTheme.typography.bodyLarge,
                    color = Color.White,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                if (channel != null) {
                    Text(
                        text = channel.name,
                        style = MaterialTheme.typography.labelMedium,
                        color = MetadataGray,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier
                            // Separate from the row's click so tapping the
                            // byline opens the channel, not the video.
                            .then(
                                if (onChannelClick != null) {
                                    Modifier.combinedClickable(onClick = onChannelClick, onLongClick = {})
                                } else {
                                    Modifier
                                },
                            )
                            .padding(top = 2.dp),
                    )
                }
            }
        }
    }
}

/**
 * 36dp circular channel avatar, YouTube-style: the channel's captured
 * icon when [iconPath] exists (a plain local file path — channel icons
 * are cached files, not source-relative, so they load through Coil's
 * normal file handling rather than the localtube:// fetcher), otherwise
 * a primary-colored circle with the channel's first letter — the same
 * fallback style as the Home screen's avatar strip. [onClick] makes the
 * avatar a channel link (applied after the clip so the ripple stays
 * inside the circle); null keeps it inert.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ChannelAvatar(name: String, iconPath: String?, onClick: (() -> Unit)? = null) {
    Box(
        modifier = Modifier
            .size(36.dp)
            .clip(CircleShape)
            .background(MaterialTheme.colorScheme.primary)
            .then(
                if (onClick != null) {
                    Modifier.combinedClickable(onClick = onClick, onLongClick = {})
                } else {
                    Modifier
                },
            ),
        contentAlignment = Alignment.Center,
    ) {
        if (iconPath != null) {
            AsyncImage(
                model = iconPath,
                contentDescription = name,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        } else {
            Text(
                text = name.firstOrNull()?.uppercase() ?: "?",
                color = Color.White,
                style = MaterialTheme.typography.labelLarge,
            )
        }
    }
}

private fun formatDuration(totalSeconds: Long): String {
    val hours = totalSeconds / 3600
    val minutes = (totalSeconds % 3600) / 60
    val seconds = totalSeconds % 60
    return if (hours > 0) {
        "%d:%02d:%02d".format(hours, minutes, seconds)
    } else {
        "%d:%02d".format(minutes, seconds)
    }
}
