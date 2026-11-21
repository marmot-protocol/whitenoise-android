package dev.ipf.whitenoise.android.notifications

import android.content.Intent
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import dev.ipf.whitenoise.android.state.AppPhase
import dev.ipf.whitenoise.android.state.WhiteNoiseAppState
import dev.ipf.whitenoise.android.ui.chats.NotificationProfileQuickSheet

/** Holds a tile tap until the normal app lock has allowed private content to appear. */
internal class NotificationProfileTileRoute {
    private var pending by mutableStateOf(false)

    /** Queues the chooser on a tile launch. */
    fun accept(intent: Intent?): Boolean {
        if (intent?.action != ACTION_OPEN) return false
        pending = true
        return true
    }

    @Composable
    @Suppress("FunctionNaming")
    fun Content(state: WhiteNoiseAppState) {
        if (
            pending && state.phase == AppPhase.Ready && state.appInForeground &&
            !state.appLock.screenVisible && !state.appLock.evaluationPending && state.activeAccountRef != null
        ) {
            NotificationProfileQuickSheet(state, onDismiss = { pending = false })
        }
    }

    companion object {
        const val ACTION_OPEN = "dev.ipf.whitenoise.android.action.OPEN_NOTIFICATION_PROFILE_CHOOSER"
    }
}
