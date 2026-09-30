package dev.ipf.whitenoise.android.notifications

import android.app.NotificationManager
import android.content.Context
import android.service.notification.StatusBarNotification
import androidx.core.app.NotificationManagerCompat
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay

/** Fences preparing/enriching writes, then removes only matching live OS generations. */
internal suspend fun dismissNotificationGroupGenerations(
    context: Context,
    children: List<NotificationGroupChild>,
    fence: NotificationGroupDismissalFence? = null,
    pacer: NotificationPostPacer = NotificationPostPacer.shared,
    read: (NotificationManager) -> Array<StatusBarNotification> = { it.activeNotifications },
) {
    synchronized(UserEventNotificationGroup.mutationLock) {
        fence?.let(NotificationCardGenerations::dismissThrough)
        children.forEach { NotificationCardGenerations.dismiss(it.generation) }
    }
    val manager = context.getSystemService(NotificationManager::class.java) ?: return
    val compat = NotificationManagerCompat.from(context)
    try {
        children.forEach { target ->
            repeatDismissal@ for (attempt in 0 until GROUP_DISMISSAL_ATTEMPTS) {
                // The OS normally removes the group before delivering its delete intent. Do not
                // spend the receiver's finite budget pacing cards already gone or replaced.
                val needsCancel =
                    try {
                        read(manager).any { UserEventNotificationGroup.child(it) == target }
                    } catch (_: RuntimeException) {
                        if (attempt + 1 < GROUP_DISMISSAL_ATTEMPTS) delay(GROUP_DISMISSAL_RETRY_MS)
                        continue@repeatDismissal
                    }
                if (!needsCancel) break@repeatDismissal
                pacer.awaitSlot()
                val complete =
                    try {
                        ConversationCardPostSynchronizer.withLock(target.tag, target.id, ConversationCardOp.DISMISS_CANCEL) {
                            NotificationGroupReconciler.mutate(context) {
                                val live = read(manager).firstOrNull { it.tag == target.tag && it.id == target.id }
                                if (live == null || UserEventNotificationGroup.child(live) != target) return@mutate true
                                compat.cancel(target.tag, target.id)
                                ConversationCardPostedRegistry.clearPosted(target.tag, target.id)
                                read(manager).none { UserEventNotificationGroup.child(it) == target }
                            }
                        }
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (_: RuntimeException) {
                        false
                    }
                if (complete) break@repeatDismissal
                delay(GROUP_DISMISSAL_RETRY_MS)
            }
        }
    } finally {
        NotificationGroupReconciler.shared(context).request()
    }
}

private const val GROUP_DISMISSAL_ATTEMPTS = 3
private const val GROUP_DISMISSAL_RETRY_MS = 150L
