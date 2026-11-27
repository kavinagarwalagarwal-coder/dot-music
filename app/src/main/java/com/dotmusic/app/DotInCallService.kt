package com.dotmusic.app

import android.telecom.InCallService
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.os.Build
import android.telecom.Call

/** Telecom's bridge for cellular calls when Dot is chosen as the device's default dialer. */
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
        } else currentCall?.let(::showCallNotification)
        broadcastCallChanged()
        super.onCallRemoved(call)
    }

    private fun ensureChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(NotificationChannel(CHANNEL_ID, "Phone calls", NotificationManager.IMPORTANCE_HIGH).apply {
                description = "Incoming and ongoing calls"
                lockscreenVisibility = Notification.VISIBILITY_PUBLIC
            })
        }
    }

    private fun showCallNotification(call: Call) {
        val incoming = call.state == Call.STATE_RINGING
        val handle = call.details.handle?.schemeSpecificPart.orEmpty().ifBlank { "Unknown caller" }
        val launch = PendingIntent.getActivity(
            this, 0, DotCallActivity.intent(this), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val notification = Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(com.dotmusic.app.R.drawable.ic_launcher_foreground)
            .setContentTitle(if (incoming) "Incoming call" else handle)
            .setContentText(if (incoming) handle else "Tap to return to your call")
            .setCategory(Notification.CATEGORY_CALL)
            .setPriority(Notification.PRIORITY_MAX)
            .setOngoing(true)
            .setContentIntent(launch)
            .setFullScreenIntent(launch, true)
            .build()
        (getSystemService(NOTIFICATION_SERVICE) as NotificationManager).notify(NOTIFICATION_ID, notification)
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
    }
}
