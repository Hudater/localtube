package dev.hudater.localtube.playback

import android.content.Intent
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import dev.hudater.localtube.LocalTubeApplication

/**
 * Hosts the single ExoPlayer inside a [MediaSession] while it plays.
 * Two of the three missing system behaviors fall out of this service:
 *
 *  - The media notification (play/pause/seek from the shade, lock
 *    screen): MediaSessionService promotes itself to a foreground
 *    mediaPlayback service and posts the default notification whenever
 *    the session's player starts — no notification code needed here.
 *  - Audio focus: [ExoPlayer.setAudioAttributes]'s handleAudioFocus flag
 *    makes the player request/abandon focus around play/pause, which is
 *    what pauses OTHER apps' media when LocalTube starts playing.
 *
 * The player is built through the app container's [LocalTubePlayerFactory]
 * so `localtube://` URIs still resolve through the SourceFileSystem layer
 * (LOCAL SAF / SMB) — same wiring the old in-process player had, just
 * living in the service process context now. [PlaybackManager] drives it
 * remotely through a MediaController.
 */
class PlaybackService : MediaSessionService() {

    private var mediaSession: MediaSession? = null

    override fun onCreate() {
        super.onCreate()
        val container = (application as LocalTubeApplication).container
        val player: ExoPlayer = container.playerFactory.create()
        // USAGE_MEDIA + movie content type tell the system this is video
        // audio; handleAudioFocus = true is the single flag that pauses
        // other apps' media when playback starts.
        player.setAudioAttributes(
            AudioAttributes.Builder()
                .setUsage(C.USAGE_MEDIA)
                .setContentType(C.AUDIO_CONTENT_TYPE_MOVIE)
                .build(),
            /* handleAudioFocus = */ true,
        )
        mediaSession = MediaSession.Builder(this, player).build()
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? =
        mediaSession

    override fun onTaskRemoved(rootIntent: Intent?) {
        // Swiping the app out of recents pauses playback like media apps
        // do. Deliberately NOT calling super: its default stops the
        // service the moment playback is paused, which would tear down
        // the notification mid-use.
        mediaSession?.player?.pause()
    }

    override fun onDestroy() {
        mediaSession?.run {
            player.release()
            release()
        }
        mediaSession = null
        super.onDestroy()
    }
}
