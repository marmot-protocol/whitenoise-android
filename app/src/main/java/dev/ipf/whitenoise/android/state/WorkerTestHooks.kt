package dev.ipf.whitenoise.android.state

import dev.ipf.whitenoise.android.notifications.NotificationReactionSendOutcome
import dev.ipf.whitenoise.android.notifications.NotificationReplyCompletionStore
import dev.ipf.whitenoise.android.notifications.NotificationReplySendOutcome

/**
 * Optional JVM-test overrides for WorkManager worker paths. Null in production;
 * workers keep their real AppState behavior unless a test sets this on
 * [WhiteNoiseAppState.workerTestHooks].
 */
internal class WorkerTestHooks {
    var ensureNotificationRuntimeStarted: (suspend () -> Unit)? = null

    var sendNotificationReply:
        (
            suspend (
                accountRef: String,
                groupIdHex: String,
                afterMessageIdHex: String,
                text: String,
                completionStore: NotificationReplyCompletionStore,
                completionKey: String,
                recoveryScope: String,
            ) -> NotificationReplySendOutcome
        )? = null

    var sendNotificationReaction:
        (
            suspend (
                accountRef: String,
                groupIdHex: String,
                messageIdHex: String,
                reaction: String,
            ) -> NotificationReactionSendOutcome
        )? = null

    var markNotificationMessageRead:
        (
            suspend (
                accountRef: String,
                groupIdHex: String,
                messageIdHex: String,
            ) -> Boolean
        )? = null

    var downloadAttachmentForDurableWork:
        (
            suspend (
                request: AttachmentTransferRequest,
                priority: AttachmentDownloadPriority,
            ) -> Boolean
        )? = null

    var sweepExpiredDisappearingMessages: (suspend () -> Unit)? = null
}
