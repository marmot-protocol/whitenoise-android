package dev.ipf.whitenoise.android.state

/** One atomic view of the mounted reply composer for an immutable dictation origin. */
internal sealed interface ConversationDictationReplyTargetResolution {
    val available: Boolean?

    data object Detached : ConversationDictationReplyTargetResolution {
        override val available: Boolean? = null
    }

    data object Mismatch : ConversationDictationReplyTargetResolution {
        override val available: Boolean = false
    }

    data class Mounted(
        val controller: ConversationController,
    ) : ConversationDictationReplyTargetResolution {
        override val available: Boolean = true
    }
}

/** The newest controller owns a transient double-mount; a mismatch fails closed instead of using an older composer. */
internal fun resolveConversationDictationReplyTarget(
    controllers: Iterable<ConversationController>,
    accountRef: String,
    groupIdHex: String,
    replyToMessageIdHex: String?,
): ConversationDictationReplyTargetResolution {
    val controller =
        newestMatchingController(controllers) { controller ->
            controller.matchesConversation(accountRef, groupIdHex)
        } ?: return ConversationDictationReplyTargetResolution.Detached
    return if (
        controller.replyingTo
            ?.messageIdHex
            .equals(replyToMessageIdHex, ignoreCase = true)
    ) {
        ConversationDictationReplyTargetResolution.Mounted(controller)
    } else {
        ConversationDictationReplyTargetResolution.Mismatch
    }
}
