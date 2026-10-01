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
                finishDismissal(context.applicationContext, children, pending::finish)
            } finally {
                scope.cancel()
            }
        }
    }

    /** Separate completion seam exercises success, platform failure and the broadcast deadline. */
    internal suspend fun finishDismissal(
        context: Context,
        children: List<NotificationGroupChild>,
        finish: () -> Unit,
        dismiss: suspend () -> Unit = { dismissNotificationGroupGenerations(context, children) },
        budgetMs: Long = BROADCAST_BUDGET_MS,
    ) {
        try {
            withTimeoutOrNull(budgetMs) { dismiss() }
        } catch (_: RuntimeException) {
            // A platform failure cannot crash an otherwise healthy notification process.
        } finally {
            try {
                NotificationGroupReconciler.shared(context).request()
            } finally {
                finish()
            }
        }
    }

    private companion object {
        const val BROADCAST_BUDGET_MS = 8_000L
    }
}
