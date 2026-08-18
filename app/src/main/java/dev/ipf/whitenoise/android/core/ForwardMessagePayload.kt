package dev.ipf.whitenoise.android.core

import androidx.compose.runtime.Immutable
import dev.ipf.marmotkit.MediaAttachmentReferenceFfi

/** Source attachment identity used only to materialize fresh destination media. */
@Immutable
internal data class ForwardAttachmentSource(
    val attachmentIndex: Int,
    val reference: MediaAttachmentReferenceFfi,
)

/** Complete user-authored content for one committed message, in source timeline order. */
@Immutable
internal sealed interface ForwardMessagePayload {
    val sourceGroupIdHex: String
    val sourceMessageIdHex: String

    @Immutable
    data class Text(
        override val sourceGroupIdHex: String,
        override val sourceMessageIdHex: String,
        val text: String,
    ) : ForwardMessagePayload

    @Immutable
    data class Media(
        override val sourceGroupIdHex: String,
        override val sourceMessageIdHex: String,
        val caption: String?,
        val attachments: List<ForwardAttachmentSource>,
        val expiresAtSeconds: ULong? = null,
    ) : ForwardMessagePayload
}

internal enum class ForwardBlockedReason {
    Unsupported,
    PendingAttachment,
    FailedAttachment,
    MalformedAttachment,
    ExpiredAttachment,
    UnavailableAttachment,
    RestrictedAttachment,
}

internal sealed interface ForwardEligibility {
    data class Eligible(
        val payload: ForwardMessagePayload,
    ) : ForwardEligibility

    data class Blocked(
        val reason: ForwardBlockedReason,
    ) : ForwardEligibility
}
