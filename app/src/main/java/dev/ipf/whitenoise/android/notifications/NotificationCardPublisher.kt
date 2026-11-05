package dev.ipf.whitenoise.android.notifications

import android.app.Notification
import android.app.NotificationManager
import android.content.Context
import android.service.notification.StatusBarNotification
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.Person
import androidx.core.content.pm.ShortcutInfoCompat
import dev.ipf.whitenoise.android.notifications.UserEventNotificationGroup.EXTRA_GENERATION
import kotlin.coroutines.cancellation.CancellationException

private const val NOTIFICATION_LOG_TAG_LENGTH = 16

/** Opaque identity of the exact accepted platform payload, separate from dismissal ownership. */
internal const val EXTRA_PAYLOAD_REVISION = "dev.ipf.whitenoise.android.notification.PAYLOAD_REVISION"

/**
 * Applies platform writes and reads without owning a second card lifetime or policy store.
 * The related operations stay together to preserve their existing synchronization contract.
 */
@Suppress("TooManyFunctions")
internal class NotificationCardPublisher
    @Suppress("LongParameterList")
    constructor(
        private val context: Context,
        private val cardContent: NotificationCardContent,
        private val conversationShortcuts: NotificationConversationShortcuts,
        private val notificationPoster: (NotificationManagerCompat, String, Int, Notification) -> Unit,
        private val notificationCanceller: (NotificationManagerCompat, String, Int) -> Unit,
        private val activeNotificationsProvider: (NotificationManager) -> Array<StatusBarNotification>,
        private val groupReconciliation: () -> Unit,
    ) {
        /**
         * The card's retained entries as logical messages, newest last. A conversation card keeps its history in
         * MessagingStyle; a legacy expandable-text card, a plain card or a redacted card is one entry identified by
         * the card's stamped newest message id.
         */
        fun retainedEntryGroups(notification: Notification): List<List<NotificationCompat.MessagingStyle.Message>> {
            val redacted = notification.extras?.getBoolean(EXTRA_CONTENT_REDACTED) == true
            val messages =
                NotificationCompat.MessagingStyle
                    .extractMessagingStyleFromNotification(notification)
                    ?.messages
                    ?.takeUnless { redacted || it.isEmpty() }
            if (messages != null) return notificationLogicalMessageGroups(messages)
            val single =
                cardContent.expandedSingleMessage(notification.extras)?.firstOrNull()
                    ?: NotificationCompat.MessagingStyle.Message(
                        notification.extras?.getCharSequence(Notification.EXTRA_TEXT) ?: "",
                        notification.`when`,
                        null as Person?,
                    )
            conversationCardMessageIdHex(notification)?.let {
                single.extras.putString(LocalNotificationFormatter.EXTRA_ENTRY_MESSAGE_ID_HEX, it)
            }
            return listOf(listOf(single))
        }

        /** The message id stamped on one logical entry, or null for an entry posted before entries carried ids. */
        fun entryMessageId(entry: List<NotificationCompat.MessagingStyle.Message>): String? =
            entry.firstNotNullOfOrNull { message ->
                message.extras.getString(LocalNotificationFormatter.EXTRA_ENTRY_MESSAGE_ID_HEX)?.takeIf(String::isNotBlank)
            }

        /** Reads the system tray while preserving coroutine cancellation and converting platform faults to retry state. */
        fun readActiveNotifications(manager: NotificationManager): Array<StatusBarNotification>? =
            try {
                activeNotificationsProvider(manager)
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (_: Throwable) {
                null
            }

        /**
         * Writes one card and reports success without allowing observer failures to alter delivery.
         * The existing write fences stay explicit named arguments at every call.
         */
        @Suppress("LongParameterList")
        fun postNotificationSafely(
            manager: NotificationManagerCompat,
            tag: String,
            id: Int,
            notification: Notification,
            onNotificationWritten: (() -> Unit)? = null,
            recordedAtMs: Long? = null,
            mustBeLive: Boolean = false,
            finalPostAllowed: (() -> Boolean)? = null,
            hiddenShortcut: (() -> ShortcutInfoCompat)? = null,
        ): NotificationCardWriteResult =
            try {
                if (finalPostAllowed != null) {
                    ConversationCardPostSynchronizer.awaitTestBarrier(
                        ConversationCardOp.SHOW_NOTIFY,
                        ConversationCardBarrier.BEFORE_PLATFORM_WRITE,
                        tag,
                        id,
                    )
                }
                val written =
                    NotificationGroupReconciler.postChild(
                        context,
                        notification,
                        request = groupReconciliation,
                        isLive = { notificationWriteIsLive(notification, tag, id, mustBeLive, finalPostAllowed) },
                    ) {
                        val payload =
                            previewPayload(cardContent.withCurrentContactPictures(notification), tag, id, hiddenShortcut)
                        // Rewrites of a card posted by the earlier expanded-choice build return to the standard template.
                        val standard = withoutGroupSenderPictures(context, withoutLegacyReactionRail(context, payload))
                        val revision = ConversationCardPostedRegistry.newPayloadRevision()
                        // A recovery builder can discard custom views and group alert behavior. Stamp a copy of
                        // the final platform payload so the revision describes exactly the card Android receives.
                        val outgoing = standard.clone().apply { extras.putString(EXTRA_PAYLOAD_REVISION, revision) }
                        notificationPoster(manager, tag, id, outgoing)
                        recordAcceptedPayload(tag, id, outgoing, recordedAtMs)
                    }
                if (written) {
                    runCatching { onNotificationWritten?.invoke() }
                }
                if (written) NotificationCardWriteResult.WRITTEN else NotificationCardWriteResult.REFUSED
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (expectedPlatformFailure: RuntimeException) {
                notificationDebug {
                    "post failed tag=${tag.take(NOTIFICATION_LOG_TAG_LENGTH)} type=${expectedPlatformFailure.javaClass.simpleName}"
                }
                NotificationCardWriteResult.FAILED
            }

        /** Keeps only bounded Android payload metadata, never message text or an independent removal/read policy. */
        private fun recordAcceptedPayload(
            tag: String,
            id: Int,
            payload: Notification,
            recordedAtMs: Long?,
        ) {
            ConversationCardPostedRegistry.markPosted(
                tag,
                id,
                recordedAtMs ?: System.currentTimeMillis(),
                payload.extras.getString(EXTRA_GENERATION),
                payload =
                    ConversationCardPostedRegistry.PayloadSnapshot(
                        requireNotNull(payload.extras.getString(EXTRA_PAYLOAD_REVISION)),
                        retainedEntryGroups(payload).mapNotNull(::entryMessageId).toSet(),
                    ),
            )
        }

        /** The caller's final fence still holds and, for a live-only write, the tray still shows this generation. */
        private fun notificationWriteIsLive(
            notification: Notification,
            tag: String,
            id: Int,
            mustBeLive: Boolean,
            finalPostAllowed: (() -> Boolean)?,
        ): Boolean =
            if (!ConversationCardPostedRegistry.canAcceptPost(tag, id) || finalPostAllowed?.invoke() == false) {
                false
            } else if (!mustBeLive) {
                true
            } else {
                val current =
                    context.getSystemService(NotificationManager::class.java)?.let {
                        activeNotification(it, tag, id)
                    }
                val candidateRevision = notification.extras.getString(EXTRA_PAYLOAD_REVISION)
                current != null &&
                    ConversationCardPostedRegistry.isCurrentPayload(
                        tag,
                        id,
                        current.notification.extras.getString(EXTRA_PAYLOAD_REVISION),
                    ) &&
                    (
                        candidateRevision == null ||
                            ConversationCardPostedRegistry.isCurrentPayload(tag, id, candidateRevision)
                    ) &&
                    current.notification.extras.getString(EXTRA_GENERATION) ==
                    notification.extras.getString(EXTRA_GENERATION)
            }

        fun canPublishPreviewShortcut(
            content: LocalNotificationContent,
            expected: Notification,
        ): Boolean {
            val latest = activeConversationCard(content.notificationTag, content.notificationId) ?: return false
            val sameGeneration = latest.extras.getString(EXTRA_GENERATION) == expected.extras.getString(EXTRA_GENERATION)
            return sameGeneration &&
                ConversationCardPostedRegistry.isCurrentPayload(
                    content.notificationTag,
                    content.notificationId,
                    latest.extras.getString(EXTRA_PAYLOAD_REVISION),
                ) &&
                NotificationPreviewPreferences.canExpose(context, latest, latest)
        }

        /** What Android receives: the rich card when its preview may be shown, otherwise the generic hidden card. */
        private fun previewPayload(
            notification: Notification,
            tag: String,
            id: Int,
            hiddenShortcut: (() -> ShortcutInfoCompat)?,
        ): Notification {
            val needsLiveProof =
                notification.extras.getBoolean(NotificationPreviewPreferences.EXTRA_CORRECTION) ||
                    !NotificationPreviewPreferences.hasCurrentProvenance(context, notification)
            val current = if (needsLiveProof) activeConversationCard(tag, id) else null
            return if (NotificationPreviewPreferences.canExpose(context, notification, current)) {
                notification
            } else {
                // A hidden card leaves its conversation shortcut generic, so the next rich post must publish it again.
                notification.shortcutId?.let(conversationShortcuts::forgetShortcutSnapshot)
                notificationWithoutPreview(context, notification, shortcut = hiddenShortcut?.invoke())
            }
        }

        fun cancelNotification(
            manager: NotificationManagerCompat,
            tag: String,
            id: Int,
        ) {
            NotificationGroupReconciler.mutate(context, groupReconciliation) { notificationCanceller(manager, tag, id) }
        }

        /** Cancels a completed write when its conversation was dismissed during the platform call. */
        fun retainPostedCardUnlessDismissed(
            showToken: ConversationCardShowToken,
            manager: NotificationManagerCompat,
            tag: String,
            id: Int,
        ): Boolean {
            if (ConversationCardPostSynchronizer.isShowNotDismissed(showToken)) return true
            ConversationCardPostedRegistry.clearPosted(tag, id)
            cancelNotification(manager, tag, id)
            return false
        }

        fun activeNotification(
            manager: NotificationManager,
            tag: String?,
            id: Int,
        ): StatusBarNotification? =
            try {
                activeNotificationsProvider(manager).firstOrNull { it.tag == tag && it.id == id }
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (_: Throwable) {
                null
            }

        /** Reads only the acted card's platform identity before dismissal is allowed to inspect siblings. */
        internal fun conversationCardMessageIdHex(
            notificationTag: String,
            notificationId: Int,
        ): String? =
            conversationCardMessageIdHex(
                runCatching {
                    context
                        .getSystemService(NotificationManager::class.java)
                        ?.activeNotifications
                        ?.firstOrNull { it.tag == notificationTag && it.id == notificationId }
                        ?.notification
                }.getOrNull(),
            )

        fun activeConversationCard(
            tag: String,
            id: Int,
        ): Notification? =
            runCatching {
                context
                    .getSystemService(NotificationManager::class.java)
                    ?.let(activeNotificationsProvider)
                    ?.firstOrNull { it.tag == tag && it.id == id }
                    ?.notification
            }.getOrNull()

        fun existingConversationMessages(
            tag: String,
            id: Int,
            replacingMessageIdHex: String? = null,
        ): List<NotificationCompat.MessagingStyle.Message>? {
            val existing =
                activeConversationCard(tag, id)
                    ?.takeUnless { it.extras?.getBoolean(EXTRA_CONTENT_REDACTED) == true }
                    ?.takeIf { NotificationPreviewPreferences.canRetainPreview(context, it) }
                    ?: return null
            val messages =
                NotificationCompat.MessagingStyle
                    .extractMessagingStyleFromNotification(existing)
                    ?.messages
                    ?: cardContent.expandedSingleMessage(existing.extras)
            val retained =
                messages
                    ?.let(::notificationLogicalMessageGroups)
                    ?.filter { entry ->
                        ConversationCardPostedRegistry.canCarryEntry(
                            tag,
                            id,
                            existing.extras.getString(EXTRA_PAYLOAD_REVISION),
                            entryMessageId(entry),
                        )
                    }?.flatten()
            return if (
                replacingMessageIdHex != null &&
                conversationCardMessageIdHex(existing) == replacingMessageIdHex
            ) {
                retained?.let(::dropLastNotificationLogicalMessage)
            } else {
                retained
            }
        }
    }
