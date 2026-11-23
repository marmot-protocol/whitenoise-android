package dev.ipf.whitenoise.android.state

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

internal data class NoticeOwner(
    val accountRef: String?,
    val runtime: Int,
)

/** Holds the latest confirmation or status separately from the partial outcome the user can dismiss. */
internal class TransientNotices(
    private val currentOwner: () -> NoticeOwner,
) {
    var transient by mutableStateOf<TransientNotice?>(null)
        private set

    private var retained by mutableStateOf<RetainedPartialNotice?>(null)

    // staleness-exempt: monotonically unique notice identity, not an async publication fence.
    private var transientNoticeSequence = 0L

    fun partial(): TransientNotice? = retained?.takeIf { it.owner == currentOwner() }?.notice

    fun publish(
        title: AppText,
        detail: AppText?,
        kind: TransientNoticeKind,
        conversation: ConversationNoticeDestination? = null,
    ): TransientNotice {
        val notice = TransientNotice(++transientNoticeSequence, title, detail, conversation, kind)
        if (kind == TransientNoticeKind.Partial) {
            retained = RetainedPartialNotice(currentOwner(), notice)
        } else {
            transient = notice
        }
        return notice
    }

    fun dismiss(notice: TransientNotice?) {
        if (transient === notice) transient = null
        if (retained?.notice === notice) retained = null
    }
}

private data class RetainedPartialNotice(
    val owner: NoticeOwner,
    val notice: TransientNotice,
)
