package com.dotmusic.app

import android.app.PendingIntent
import android.content.Intent
import androidx.annotation.OptIn
import androidx.media3.common.ForwardingPlayer
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService

/**
 * Keeps Dot's own player (Audius, Jamendo) going with the screen off, with the media notification
 * and lock-screen controls. Next and previous go through Dot's queue and recommendations.
 */
@OptIn(UnstableApi::class)
class PlaybackService : MediaSessionService() {
    private var session: MediaSession? = null

    override fun onCreate() {
        super.onCreate()
        DotPlayback.attach(this)
        MusicCenter.attach(this)
        val player = object : ForwardingPlayer(DotPlayback.player(this)) {
            private val extra = listOf(
                Player.COMMAND_SEEK_TO_NEXT, Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM,
                Player.COMMAND_SEEK_TO_PREVIOUS, Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM,
            )
            override fun getAvailableCommands(): Player.Commands =
                super.getAvailableCommands().buildUpon().addAll(*extra.toIntArray()).build()
            override fun isCommandAvailable(command: Int) = command in extra || super.isCommandAvailable(command)
            override fun hasNextMediaItem() = true
            override fun hasPreviousMediaItem() = true
            override fun seekToNext() = MusicCenter.command(MusicCenter.Command.NEXT)
            override fun seekToNextMediaItem() = MusicCenter.command(MusicCenter.Command.NEXT)
            override fun seekToPrevious() = DotPlayback.previous()
            override fun seekToPreviousMediaItem() = DotPlayback.previous()
        }
        val open = PendingIntent.getActivity(this, 0,
            Intent(this, DotPlayerActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        session = MediaSession.Builder(this, player).setSessionActivity(open).build()
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = session

    /** Swiped away from recents while paused: stop; while playing, keep going. */
    override fun onTaskRemoved(rootIntent: Intent?) {
        val p = session?.player
        if (p == null || !p.playWhenReady || p.mediaItemCount == 0) stopSelf()
    }

    override fun onDestroy() {
        session?.run { release() }
        session = null
        DotPlayback.releasePlayer()
        super.onDestroy()
    }
}
