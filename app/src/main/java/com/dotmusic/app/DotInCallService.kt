package com.dotmusic.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.telecom.Call
import android.telecom.CallAudioState
import android.telecom.InCallService
import android.telecom.VideoProfile

/** Telecom's bridge for cellular calls when Dot is chosen as the device's phone app. */
class DotInCallService : InCallService() {
    private val calls = LinkedHashSet<Call>()
    private val callback = object : Call.Callback() {
        override fun onStateChanged(call: Call, state: Int) {
            currentCall = call
            broadcastCallChanged()
            showCallNotification(call)
        }
    }

    override fun onCallAdded(call: Call) {
        super.onCallAdded(call)
        calls += call
        call.registerCallback(callback)
        currentCall = call
        service = this
        ensureChannel()
        showCallNotification(call)
        broadcastCallChanged()
        runCatching { startActivity(DotCallActivity.intent(this)) }
    }

    override fun onCallRemoved(call: Call) {
        call.unregisterCallback(callback)
        calls -= call
        if (currentCall === call) currentCall = calls.lastOrNull()
        if (calls.isEmpty()) {
            (getSystemService(NOTIFICATION_SERVICE) as NotificationManager).cancel(NOTIFICATION_ID)
            service = null
            muted = false
            speaker = false
            MissedCalls.onCallEnded(this)
        } else currentCall?.let(::showCallNotification)
        broadcastCallChanged()
        super.onCallRemoved(call)
    }

    /** Telecom's own audio state, so the call screen shows mute and speaker truthfully. */
    override fun onCallAudioStateChanged(audioState: CallAudioState) {
        super.onCallAudioStateChanged(audioState)
        muted = audioState.isMuted
        speaker = audioState.route == CallAudioState.ROUTE_SPEAKER
        broadcastCallChanged()
    }

    private fun ensureChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "Phone calls", NotificationManager.IMPORTANCE_HIGH).apply {
                    description = "Incoming and ongoing calls"
                    lockscreenVisibility = Notification.VISIBILITY_PUBLIC
                }
            )
        }
    }

    private fun showCallNotification(call: Call) {
        val incoming = call.state == Call.STATE_RINGING
        val handle = call.details.handle?.schemeSpecificPart.orEmpty().ifBlank { getString(R.string.unknown_caller) }
        val launch = PendingIntent.getActivity(
            this, 0, DotCallActivity.intent(this),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val builder = Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle(if (incoming) getString(R.string.incoming) else handle)
            .setContentText(if (incoming) handle else getString(R.string.tap_to_return))
            .setCategory(Notification.CATEGORY_CALL)
            .setPriority(Notification.PRIORITY_MAX)
            .setOngoing(true)
            .setContentIntent(launch)
            .setFullScreenIntent(launch, true)
        if (incoming) {
            builder.addAction(action(R.string.decline, CallActions.DECLINE))
            builder.addAction(action(R.string.answer, CallActions.ANSWER))
        }
        (getSystemService(NOTIFICATION_SERVICE) as NotificationManager).notify(NOTIFICATION_ID, builder.build())
    }

    private fun action(label: Int, action: String): Notification.Action {
        val intent = Intent(this, CallActionReceiver::class.java).setAction(action)
        val pending = PendingIntent.getBroadcast(
            this, action.hashCode(), intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return Notification.Action.Builder(null, getString(label), pending).build()
    }

    private fun broadcastCallChanged() {
        sendBroadcast(Intent(ACTION_CALL_CHANGED).setPackage(packageName))
    }

    companion object {
        const val ACTION_CALL_CHANGED = "com.dotmusic.app.CALL_CHANGED"
        private const val CHANNEL_ID = "dot_calls"
        private const val NOTIFICATION_ID = 410

        @Volatile var currentCall: Call? = null
            internal set
        @Volatile var service: DotInCallService? = null
            internal set
        @Volatile var muted = false
            internal set
        @Volatile var speaker = false
            internal set
    }
}

/** Answer and decline from the call notification, without opening the app. */
class CallActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val call = DotInCallService.currentCall ?: return
        when (intent.action) {
            CallActions.ANSWER -> runCatching { call.answer(VideoProfile.STATE_AUDIO_ONLY) }
            CallActions.DECLINE -> runCatching { call.disconnect() }
        }
    }
}

private object CallActions {
    const val ANSWER = "com.dotmusic.app.ACTION_ANSWER"
    const val DECLINE = "com.dotmusic.app.ACTION_DECLINE"
}
