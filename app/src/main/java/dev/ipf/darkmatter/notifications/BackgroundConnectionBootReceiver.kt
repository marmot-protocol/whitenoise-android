package dev.ipf.darkmatter.notifications

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import dev.ipf.darkmatter.BuildConfig

class BackgroundConnectionBootReceiver : BroadcastReceiver() {
    override fun onReceive(
        context: Context,
        intent: Intent,
    ) {
        val enabled = BackgroundConnectionPreferences.isEnabled(context)
        if (!BackgroundConnectionPolicy.shouldStartFromSystemWake(intent.action, enabled)) return
        val started = NotificationStreamForegroundService.start(context)
        // Debug-only so operational INFO logs don't ship in release logcat. See #39.
        if (BuildConfig.DEBUG) Log.i("DMForegroundSvc", "system wake action=${intent.action} started=$started")
    }
}
