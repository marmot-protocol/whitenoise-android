package dev.ipf.whitenoise.android.notifications

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/** Immutable delete intents carry only the OS generations represented by the dismissed card/summary. */
class NotificationGroupDismissReceiver : BroadcastReceiver() {
    override fun onReceive(
        context: Context,
        intent: Intent,
    ) {
        val children = UserEventNotificationGroup.dismissalChildren(intent) ?: return
        val pending = goAsync()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        scope.launch {
            try {
                withTimeoutOrNull(BROADCAST_BUDGET_MS) {
                    dismissNotificationGroupGenerations(context.applicationContext, children)
                }
            } catch (_: RuntimeException) {
                // A failed platform operation must not crash an otherwise healthy notification process.
            } finally {
                NotificationGroupReconciler.shared(context).request()
                pending.finish()
                scope.cancel()
            }
        }
    }

    private companion object {
        const val BROADCAST_BUDGET_MS = 8_000L
    }
}
