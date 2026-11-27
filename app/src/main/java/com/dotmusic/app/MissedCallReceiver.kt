package com.dotmusic.app

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.Uri

class MissedCallReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            MissedCalls.ACTION_CLEAR -> MissedCalls.clear(context)
            MissedCalls.ACTION_CALL_BACK -> {
                val number = intent.getStringExtra(MissedCalls.EXTRA_NUMBER) ?: return
                try {
                    val callIntent = Intent(Intent.ACTION_DIAL, Uri.parse("tel:$number"))
                    callIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    context.startActivity(callIntent)
                } catch (_: Exception) {}
                MissedCalls.clear(context)
            }
        }
    }
}
