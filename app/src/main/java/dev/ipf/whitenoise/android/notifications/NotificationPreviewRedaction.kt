package dev.ipf.whitenoise.android.notifications

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationManager
import android.content.Context
import android.service.notification.StatusBarNotification
import androidx.core.app.NotificationManagerCompat
import kotlinx.coroutines.delay

/** Scrubs live OS cards only; never reconstructs a dismissed card or changes channel settings. */
internal suspend fun redactActiveNotificationPreviews(context: Context): Boolean {
    return NotificationPreviewRedactor(context).redact()
}

@SuppressLint("MissingPermission")
internal class NotificationPreviewRedactor(
    private val context: Context,
    private val pacer: NotificationPostPacer = NotificationPostPacer.shared,
    private val read: (NotificationManager) -> Array<StatusBarNotification> = { it.activeNotifications },
    private val post: (NotificationManagerCompat, String?, Int, Notification) -> Unit = { manager, tag, id, card ->
        manager.notify(tag, id, card)
    },
    private val cancel: (NotificationManagerCompat, String?, Int) -> Unit = { manager, tag, id ->
        manager.cancel(tag, id)
    },
    private val sleep: suspend (Long) -> Unit = { delay(it) },
) {
    private val manager = context.getSystemService(NotificationManager::class.java)
    private val compat = NotificationManagerCompat.from(context)
    private val attempts = mutableMapOf<String, Int>()
    private var succeeded = true

    /** Waits for bounded asynchronous tray visibility, including writes accepted before the toggle. */
    suspend fun redact(): Boolean {
        synchronized(UserEventNotificationGroup.mutationLock) {
            if (!redactNotificationShortcuts(context)) succeeded = false
            if (!redactNotificationChannelNames(context)) succeeded = false
        }
        val settling =
            synchronized(UserEventNotificationGroup.mutationLock) {
                NotificationGroupWriteVisibility.remainingMillis(context)
            }
        if (settling > 0) sleep(settling)
        var confirmed = false
        repeat(RECHECKS) {
            if (!confirmed) {
                val cards = runCatching { read(checkNotNull(manager)) }.getOrNull()
                if (cards != null) {
                    val privateCards = cards.filter(::isPrivateCard)
                    confirmed = privateCards.isEmpty()
                    privateCards.forEach { scrub(it) }
                }
                if (!confirmed) sleep(RECHECK_DELAY_MS)
            }
        }
        return confirmed && succeeded
    }

    private fun isPrivateCard(card: StatusBarNotification): Boolean =
        UserEventNotificationGroup.isChildCandidate(card) &&
            !card.notification.extras.getBoolean(NotificationPreviewPreferences.EXTRA_HIDDEN)

    /** A fresh OS read inside both mutation gates prevents resurrecting a dismissed/replaced card. */
    private suspend fun scrub(candidate: StatusBarNotification) {
        pacer.awaitSlot()
        ConversationCardPostSynchronizer.withLock(
            candidate.tag.orEmpty(),
            candidate.id,
            ConversationCardOp.REFRESH_CONTACT_NAME,
        ) {
            NotificationGroupReconciler.mutate(context) {
                val live =
                    runCatching {
                        read(checkNotNull(manager)).firstOrNull {
                            it.tag == candidate.tag && it.id == candidate.id
                        }
                    }.getOrElse {
                        succeeded = false
                        null
                    }
                if (live != null && isPrivateCard(live)) writeHidden(live)
            }
        }
    }

    private fun writeHidden(live: StatusBarNotification) {
        val generation = live.notification.extras.getString(UserEventNotificationGroup.EXTRA_GENERATION)
        val pending = ConversationCardPostedRegistry.latestGeneration(live.tag.orEmpty(), live.id)
        if (NotificationCardGenerations.isDismissed(generation)) return
        // Wait briefly for an accepted write, then fail closed if Android never makes it visible.
        // Never overwrite a newer queued card with this older snapshot.
        if (pending != null && pending != generation) {
            val key = "${live.key}:$generation"
            val skipped = attempts.getOrDefault(key, 0)
            attempts[key] = skipped + 1
            if (skipped >= REWRITE_ATTEMPTS) cancelVisible(live)
        } else {
            writeForGeneration(live, generation)
        }
    }

    private fun writeForGeneration(
        live: StatusBarNotification,
        generation: String?,
    ) {
        val key = "${live.key}:$generation"
        val attempt = attempts.getOrDefault(key, 0)
        attempts[key] = attempt + 1
        if (attempt >= REWRITE_ATTEMPTS) {
            cancelVisible(live)
        } else {
            val hidden = notificationWithoutPreview(context, live.notification, silent = true)
            hidden.extras.putLong(
                UserEventNotificationGroup.EXTRA_LEGACY_POST_TIME,
                UserEventNotificationGroup.dismissalTime(live),
            )
            try {
                post(compat, live.tag, live.id, hidden)
                NotificationGroupWriteVisibility.childWritten(context)
            } catch (_: RuntimeException) {
                succeeded = false
                // Cancel on the next paced attempt, with another fresh generation check.
                attempts[key] = REWRITE_ATTEMPTS
            }
        }
    }

    private fun cancelVisible(live: StatusBarNotification) {
        succeeded = false
        runCatching { cancel(compat, live.tag, live.id) }.onFailure { succeeded = false }
    }

    companion object {
        private const val RECHECKS = 5
        private const val REWRITE_ATTEMPTS = 2
        private const val RECHECK_DELAY_MS = 200L
    }
}
