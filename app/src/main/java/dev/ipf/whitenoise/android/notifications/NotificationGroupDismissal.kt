package dev.ipf.whitenoise.android.notifications

import android.app.NotificationManager
import android.content.Context
import android.service.notification.StatusBarNotification
import androidx.core.app.NotificationManagerCompat
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay

/** Invalidates displayed/enriching generations, then removes only their matching live OS cards. */
internal suspend fun dismissNotificationGroupGenerations(
    context: Context,
    children: List<NotificationGroupChild>,
    pacer: NotificationPostPacer = NotificationPostPacer.shared,
    read: (NotificationManager) -> Array<StatusBarNotification> = { it.activeNotifications },
    request: () -> Unit = { NotificationGroupReconciler.shared(context).request() },
) {
    synchronized(UserEventNotificationGroup.mutationLock) {
        children.forEach { NotificationCardGenerations.dismiss(it.generation) }
    }
    val manager = context.getSystemService(NotificationManager::class.java) ?: return
    val platform = NotificationGroupDismissalPlatform(context, manager, read, request)
    try {
        children.forEach { target ->
            for (attempt in 0 until GROUP_DISMISSAL_ATTEMPTS) {
                if (platform.attempt(target, pacer)) break
                if (attempt + 1 < GROUP_DISMISSAL_ATTEMPTS) delay(GROUP_DISMISSAL_RETRY_MS)
            }
        }
    } finally {
        request()
    }
}

private class NotificationGroupDismissalPlatform(
    private val context: Context,
    private val manager: NotificationManager,
    private val read: (NotificationManager) -> Array<StatusBarNotification>,
    private val request: () -> Unit,
) {
    private val compat = NotificationManagerCompat.from(context)

    suspend fun attempt(
        target: NotificationGroupChild,
        pacer: NotificationPostPacer,
    ): Boolean =
        try {
            // Normally Android has already removed the group; absent/replaced cards need no slot.
            if (read(manager).none { UserEventNotificationGroup.child(it) == target }) {
                true
            } else {
                pacer.awaitSlot()
                cancelMatching(target)
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: RuntimeException) {
            false
        }

    private fun cancelMatching(target: NotificationGroupChild): Boolean =
        ConversationCardPostSynchronizer.withLock(target.tag, target.id, ConversationCardOp.DISMISS_CANCEL) {
            NotificationGroupReconciler.mutate(context, request) {
                val live = read(manager).firstOrNull { it.tag == target.tag && it.id == target.id }
                if (live == null || UserEventNotificationGroup.child(live) != target) {
                    true
                } else {
                    compat.cancel(target.tag, target.id)
                    ConversationCardPostedRegistry.clearPosted(target.tag, target.id)
                    read(manager).none { UserEventNotificationGroup.child(it) == target }
                }
            }
        }
}

private const val GROUP_DISMISSAL_ATTEMPTS = 3
private const val GROUP_DISMISSAL_RETRY_MS = 150L
