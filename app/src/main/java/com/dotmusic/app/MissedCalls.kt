package com.dotmusic.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.CallLog

/** The most recent missed call, surfaced as a single notification that clears as soon as the user
 * opens Dot Phone, taps the call-back action, or taps the clear action. Tapping the notification or
 * the call-back action opens the dialler with the number pre-filled.
 *
 * The count is kept in memory so a small badge in the keypad header can remind the user before they
 * open the app; opening the app cancels the notification but keeps the count until the user returns
 * the call. Notifications are only posted while the dialler is not on screen, so they never flash
 * while the app is in use. */
object MissedCalls {
    private const val CHANNEL = "dot_missed"
    private const val NOTIF_ID = 412
    const val ACTION_CLEAR = "com.dotmusic.app.CLEAR_MISSED"
    const val ACTION_CALL_BACK = "com.dotmusic.app.CALL_BACK"
    const val EXTRA_NUMBER = "number"

    private val io = java.util.concurrent.Executors.newSingleThreadExecutor { r ->
        Thread(r, "dot-missed").apply { priority = Thread.MIN_PRIORITY }
    }

    @Volatile private var count = 0
    @Volatile private var lastNumber: String? = null

    val countNow: Int get() = count
    val numberNow: String? get() = lastNumber

    fun onCallEnded(context: Context) {
        io.execute { refresh(context) }
        io.execute {
            try { Thread.sleep(2500) } catch (_: Exception) {}
            refresh(context)
        }
    }

    fun start(context: Context) {
        ensureChannel(context)
        refresh(context)
    }

    fun stop(context: Context) {
        context.getSystemService(NotificationManager::class.java).cancel(NOTIF_ID)
    }

    /** Called when the user opens the app: the notification goes away, but the count stays so the
    header badge can still remind them until they return the call. */
    fun opened(context: Context) {
        context.getSystemService(NotificationManager::class.java).cancel(NOTIF_ID)
    }

    fun clear(context: Context) {
        count = 0
        lastNumber = null
        context.getSystemService(NotificationManager::class.java).cancel(NOTIF_ID)
    }

    private fun ensureChannel(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val manager = context.getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(
                NotificationChannel(CHANNEL, "Missed calls", NotificationManager.IMPORTANCE_HIGH).apply {
                    description = "Missed calls you haven\'t returned yet"
                    lockscreenVisibility = Notification.VISIBILITY_PUBLIC
                }
            )
        }
    }

    private fun refresh(context: Context) {
        runCatching {
            val projections = arrayOf(
                CallLog.Calls.NUMBER,
                CallLog.Calls.DATE,
                CallLog.Calls.TYPE
            )
            context.contentResolver.query(
                CallLog.Calls.CONTENT_URI,
                projections,
                "${CallLog.Calls.TYPE} = ?",
                arrayOf(CallLog.Calls.MISSED_TYPE.toString()),
                "${CallLog.Calls.DATE} DESC"
            )?.use { cursor ->
                val numberIndex = cursor.getColumnIndexOrThrow(CallLog.Calls.NUMBER)
                val dateIndex = cursor.getColumnIndexOrThrow(CallLog.Calls.DATE)
                var latestNumber: String? = null
                var latestDate = 0L
                var seen = 0
                while (cursor.moveToNext()) {
                    val number = cursor.getString(numberIndex).orEmpty().trim()
                    if (number.isBlank()) continue
                    if (Store.key(number).isEmpty()) continue
                    seen++
                    val date = cursor.getLong(dateIndex)
                    if (date > latestDate) {
                        latestDate = date
                        latestNumber = number
                    }
                }
                count = seen
                lastNumber = latestNumber
                if (seen == 0) {
                    context.getSystemService(NotificationManager::class.java).cancel(NOTIF_ID)
                } else if (!appVisible) {
                    postNotification(context, latestNumber!!)
                }
            }
        }.getOrNull()
    }

    private fun postNotification(context: Context, number: String) {
        val callIntent = Intent(Intent.ACTION_DIAL, Uri.fromParts("tel", number, null))
        callIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        val launch = PendingIntent.getActivity(
            context, 1, callIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val clearIntent = Intent(context, MissedCallReceiver::class.java).setAction(ACTION_CLEAR)
        val clearP = PendingIntent.getBroadcast(
            context, 2, clearIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val callBackIntent = Intent(context, MissedCallReceiver::class.java).setAction(ACTION_CALL_BACK).putExtra(EXTRA_NUMBER, number)
        val callBackP = PendingIntent.getBroadcast(
            context, 3, callBackIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val builder = Notification.Builder(context, CHANNEL).apply {
            setSmallIcon(R.drawable.ic_launcher_foreground)
            setContentTitle(context.getString(R.string.missed_title))
            setContentText(context.getString(R.string.missed_count, count))
            setSubText(number)
            setCategory(Notification.CATEGORY_CALL)
            setPriority(Notification.PRIORITY_MAX)
            setOngoing(false)
            setAutoCancel(true)
            setContentIntent(launch)
            setDeleteIntent(clearP)
            addAction(
                Notification.Action.Builder(null, context.getString(R.string.call_back), callBackP).build()
            )
            addAction(
                Notification.Action.Builder(null, context.getString(R.string.clear_missed), clearP).build()
            )
        }
        context.getSystemService(NotificationManager::class.java).notify(NOTIF_ID, builder.build())
    }

    @Volatile var appVisible = false
}
