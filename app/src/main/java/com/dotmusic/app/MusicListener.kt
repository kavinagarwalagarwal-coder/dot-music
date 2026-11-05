package com.dotmusic.app

import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.service.notification.NotificationListenerService

/**
 * Notification access is how Android lets an app see other apps' media sessions. This follows the
 * session that's playing (or the most recent one) and publishes it to [MusicCenter].
 * Nothing is stored or sent anywhere.
 */
class MusicListener : NotificationListenerService() {
    private val main = Handler(Looper.getMainLooper())
    private var sessions: MediaSessionManager? = null
    private var controller: MediaController? = null
    private var artFor: String? = null
    private var art: Bitmap? = null
    private var accent = DEFAULT_ACCENT

    private val sessionsChanged = MediaSessionManager.OnActiveSessionsChangedListener { list -> choose(list.orEmpty()) }

    private val callback = object : MediaController.Callback() {
        override fun onPlaybackStateChanged(state: PlaybackState?) = publishSoon()
        override fun onMetadataChanged(metadata: MediaMetadata?) = publishSoon()
        override fun onSessionDestroyed() { main.post { refreshSessions() } }
    }

    private val screenOn = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) = MusicCenter.scheduleTick()
    }

    override fun onListenerConnected() {
        super.onListenerConnected()
        MusicCenter.attach(this)
        MusicCenter.controls = ::control
        MusicCenter.seeker = { ms -> controller?.transportControls?.seekTo(ms.coerceAtLeast(0)) }
        val manager = getSystemService(MediaSessionManager::class.java)
        sessions = manager
        runCatching { manager?.addOnActiveSessionsChangedListener(sessionsChanged, ComponentName(this, MusicListener::class.java), main) }
        runCatching { registerReceiver(screenOn, IntentFilter(Intent.ACTION_SCREEN_ON)) }
        refreshSessions()
    }

    override fun onListenerDisconnected() {
        runCatching { sessions?.removeOnActiveSessionsChangedListener(sessionsChanged) }
        runCatching { unregisterReceiver(screenOn) }
        setController(null)
        MusicCenter.controls = null
        MusicCenter.seeker = null
        MusicCenter.publish(null)
        super.onListenerDisconnected()
    }

    private fun refreshSessions() {
        val list = runCatching { sessions?.getActiveSessions(ComponentName(this, MusicListener::class.java)) }.getOrNull()
        choose(list.orEmpty())
    }

    /** Prefer whatever is playing; otherwise keep the most recent session (Android lists newest first). */
    private fun choose(list: List<MediaController>) {
        val pick = list.firstOrNull { it.playbackState?.state == PlaybackState.STATE_PLAYING }
            ?: list.firstOrNull { it.sessionToken == controller?.sessionToken }
            ?: list.firstOrNull()
        if (pick?.sessionToken != controller?.sessionToken) setController(pick)
        publishSoon()
    }

    private fun setController(next: MediaController?) {
        controller?.unregisterCallback(callback)
        controller = next
        next?.registerCallback(callback, main)
    }

    private fun control(c: MusicCenter.Command) {
        val ctl = controller ?: return
        val t = ctl.transportControls
        when (c) {
            MusicCenter.Command.TOGGLE ->
                if (ctl.playbackState?.state == PlaybackState.STATE_PLAYING) t.pause() else t.play()
            MusicCenter.Command.NEXT -> t.skipToNext()
            MusicCenter.Command.PREVIOUS -> t.skipToPrevious()
        }
    }

    // Apps often send several changes at once; collect them into one update.
    private val publish = Runnable { publishNow() }
    private fun publishSoon() { main.removeCallbacks(publish); main.postDelayed(publish, 60) }

    private fun publishNow() {
        val ctl = controller ?: return MusicCenter.publish(null)
        val meta = ctl.metadata ?: return MusicCenter.publish(null)
        val title = meta?.getString(MediaMetadata.METADATA_KEY_TITLE)?.takeIf { it.isNotBlank() }
            ?: meta?.getString(MediaMetadata.METADATA_KEY_DISPLAY_TITLE)?.takeIf { it.isNotBlank() }
            ?: return MusicCenter.publish(null)
        val artist = meta.getString(MediaMetadata.METADATA_KEY_ARTIST)?.takeIf { it.isNotBlank() }
            ?: meta.getString(MediaMetadata.METADATA_KEY_ALBUM_ARTIST).orEmpty()
        val key = "${ctl.packageName}|$title|$artist"
        if (key != artFor) {
            artFor = key
            art = artwork(meta)
            accent = art?.let(::accentOf) ?: DEFAULT_ACCENT
        }
        val state = ctl.playbackState
        MusicCenter.publish(Track(
            title = title,
            artist = artist,
            packageName = ctl.packageName,
            art = art,
            accent = accent,
            isPlaying = state?.state == PlaybackState.STATE_PLAYING || state?.state == PlaybackState.STATE_BUFFERING,
            positionMs = state?.position?.coerceAtLeast(0) ?: 0,
            positionUpdatedAt = state?.lastPositionUpdateTime?.takeIf { it > 0 } ?: SystemClock.elapsedRealtime(),
            speed = state?.playbackSpeed?.takeIf { it > 0f } ?: 1f,
            durationMs = meta.getLong(MediaMetadata.METADATA_KEY_DURATION).coerceAtLeast(0),
            openApp = ctl.sessionActivity,
        ))
    }

    private fun artwork(meta: MediaMetadata): Bitmap? {
        val direct = meta.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART)
            ?: meta.getBitmap(MediaMetadata.METADATA_KEY_ART)
            ?: meta.getBitmap(MediaMetadata.METADATA_KEY_DISPLAY_ICON)
        if (direct != null) return scaled(direct)
        val uri = sequenceOf(MediaMetadata.METADATA_KEY_ALBUM_ART_URI, MediaMetadata.METADATA_KEY_ART_URI, MediaMetadata.METADATA_KEY_DISPLAY_ICON_URI)
            .mapNotNull { meta.getString(it)?.takeIf { s -> s.isNotBlank() } }.firstOrNull() ?: return null
        return runCatching {
            contentResolver.openInputStream(android.net.Uri.parse(uri))?.use { BitmapFactory.decodeStream(it) }?.let(::scaled)
        }.getOrNull()
    }

    /** Keep a small copy: the dots never need more than this. */
    private fun scaled(b: Bitmap): Bitmap =
        if (b.width <= ART_SIZE && b.height <= ART_SIZE) b else Bitmap.createScaledBitmap(b, ART_SIZE, ART_SIZE, true)

    /** The most colourful of the cover's brighter colours. */
    private fun accentOf(b: Bitmap): Int {
        val colors = DotArt.sample(b, 8)
        return colors.maxByOrNull { c ->
            val hsv = FloatArray(3).also { android.graphics.Color.colorToHSV(c, it) }
            hsv[1] * 0.7f + hsv[2] * 0.3f
        }?.let(DotArt::vivid) ?: DEFAULT_ACCENT
    }

    companion object {
        private const val ART_SIZE = 256
        const val DEFAULT_ACCENT = 0xFFFF3B30.toInt()

        fun enabled(context: Context): Boolean =
            android.provider.Settings.Secure.getString(context.contentResolver, "enabled_notification_listeners")
                ?.split(':')?.any { ComponentName.unflattenFromString(it)?.packageName == context.packageName } == true
    }
}
