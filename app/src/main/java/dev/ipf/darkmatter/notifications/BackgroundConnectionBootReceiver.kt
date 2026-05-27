package dev.ipf.darkmatter.notifications

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

class BackgroundConnectionBootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val enabled = BackgroundConnectionPreferences.isEnabled(context)
        if (!BackgroundConnectionPolicy.shouldStartFromSystemWake(intent.action, enabled)) return
        val started = NotificationStreamForegroundService.start(context)
        Log.i("DMForegroundSvc", "boot wake action=${intent.action} started=$started")
    }
}
