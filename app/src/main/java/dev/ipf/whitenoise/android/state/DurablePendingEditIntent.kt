package dev.ipf.whitenoise.android.state

import androidx.compose.runtime.Immutable
import dev.ipf.marmotkit.LocalSendStatusFfi
import dev.ipf.marmotkit.MarmotInterface
import dev.ipf.marmotkit.SendAcceptDispositionFfi
import dev.ipf.marmotkit.SendSummaryFfi

/** Correlation for one in-flight UI revision; the content and durable outbox remain MDK-owned. */
@Immutable
data class DurablePendingEditIntent(
    val originalClientToken: String,
    val editClientToken: String,
)

internal typealias PendingMessageEditPublisher = suspend (String, String, String, String, String) -> SendSummaryFfi

/** A finished native admission can still represent queued publication, rather than delivery. */
internal fun OptimisticEdit.withNativeEditStatus(status: LocalSendStatusFfi?): OptimisticEdit =
    when (status) {
        is LocalSendStatusFfi.Completed -> {
            val publicationStatus =
                if (status.summary.acceptDisposition == SendAcceptDispositionFfi.PUBLISHED) {
                    MessageStatus.Sent
                } else {
                    MessageStatus.Pending
                }
            copy(
                status = publicationStatus,
                nativeEditMessageId = status.summary.messageIds.firstOrNull() ?: nativeEditMessageId,
            )
        }
        LocalSendStatusFfi.Queued -> copy(status = MessageStatus.Pending)
        LocalSendStatusFfi.EngineOwned -> copy(status = MessageStatus.Pending)
        LocalSendStatusFfi.Rejected ->
            copy(
                status = MessageStatus.Failed,
                nativeRevisionRejected = true,
            )
        else -> this
    }

/** Recovers ambiguous admission using the same edit token, never by resending the original message. */
internal suspend fun MarmotInterface.admitPendingMessageEdit(
    account: String,
    group: String,
    intent: DurablePendingEditIntent,
    text: String,
): SendSummaryFfi =
    admitLocalSend(account, group, intent.editClientToken) {
        editLocalMessageWithClientToken(account, group, intent.originalClientToken, text, intent.editClientToken)
    }
