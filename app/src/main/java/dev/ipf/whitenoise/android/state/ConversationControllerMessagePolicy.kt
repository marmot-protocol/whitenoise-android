package dev.ipf.whitenoise.android.state

import androidx.compose.runtime.Immutable

/**
 * The two deletion scopes a user may apply to one message. This is the single
 * authoritative deletion-capability model: both the action UI (which choices
 * the delete surface offers) and the controller mutation path (which requests
 * it accepts) derive from [messageDeleteCapability], so button visibility is
 * never the security boundary.
 */
@Immutable
data class MessageDeleteCapability(
    val canDeleteForMe: Boolean,
    val canDeleteForEveryone: Boolean,
) {
    val canDeleteAtAll: Boolean get() = canDeleteForMe || canDeleteForEveryone
}

/**
 * Whether the runtime can actually deliver a moderator's delete of another
 * member's group message. The Marmot timeline ingest now honours a group
 * admin's delete of another member's message: the moderation verdict is
 * evaluated against signed group state when the delete is recorded and
 * persisted with the event, and direct conversations never grant moderation.
 * Kept as a constant so the moderator branch can be cut in one place if the
 * engine behaviour ever has to be rolled back.
 */
internal const val GROUP_MODERATION_DELETE_SUPPORTED = true

/**
 * Deletion-capability matrix:
 *
 *  - Own message (direct or group): delete for me, delete for everyone.
 *  - Someone else's direct message: delete for me only. A direct conversation
 *    has no moderation, whatever admin flags its underlying two-member group
 *    carries — DM creators are often marked admin at the MLS layer, and an
 *    admin role held in any other group never reaches here at all (each
 *    conversation's controller only passes its own membership role).
 *  - Another member's group message: delete for everyone only when the
 *    current user moderates this group (admin/owner) and the runtime supports
 *    delivering it ([moderationDeleteSupported]); regular members get
 *    delete for me only.
 *  - Already-deleted projection: delete for me only. The underlying protocol
 *    tombstone remains authoritative; local cleanup never offers or retries a
 *    delete for everyone.
 *
 * [localDeleteSupported] and [remoteDeleteSupported] carry the plumbing facts
 * (usable message id, live membership / publish path); this function owns only
 * the ownership/role policy layered on top of them.
 */
internal fun messageDeleteCapability(
    isDirectConversation: Boolean,
    mine: Boolean,
    selfIsAdmin: Boolean,
    localDeleteSupported: Boolean,
    remoteDeleteSupported: Boolean,
    alreadyDeleted: Boolean,
    moderationDeleteSupported: Boolean = GROUP_MODERATION_DELETE_SUPPORTED,
): MessageDeleteCapability {
    if (alreadyDeleted) {
        return MessageDeleteCapability(
            canDeleteForMe = localDeleteSupported,
            canDeleteForEveryone = false,
        )
    }
    val moderatesOthersMessages = !isDirectConversation && selfIsAdmin && moderationDeleteSupported
    return MessageDeleteCapability(
        canDeleteForMe = localDeleteSupported,
        canDeleteForEveryone = remoteDeleteSupported && (mine || moderatesOthersMessages),
    )
}

/**
 * Ownership boundary for an optimistic outgoing row. Only [PRE_ACCEPTANCE]
 * may be cancelled locally; every later phase is already owned by MDK or has
 * an acknowledgement whose ownership cannot be disproved safely.
 */
internal enum class OptimisticSendPhase {
    PRE_ACCEPTANCE,
    ACCEPTED_PENDING,
    ACCEPTANCE_UNKNOWN,
    SETTLED,
    FAILED,
    CANCELLED,
}

internal class OptimisticSendCancelledException : Exception("Optimistic send cancelled before admission")

internal const val MAX_CANCELLED_SEND_TOMBSTONES = 64

/** Shared membership gate for optimistic work that the engine later validates. */
private fun canAcceptSeededMemberMutation(
    accountRef: String?,
    membersVerified: Boolean,
    isSelfMember: Boolean,
    seededSelfMember: Boolean,
    selfLeft: Boolean,
    unrecoverable: Boolean,
    disbanding: Boolean,
    disbanded: Boolean,
): Boolean =
    accountRef != null &&
        !unrecoverable &&
        !disbanding &&
        !disbanded &&
        (
            (membersVerified && isSelfMember) ||
                (!membersVerified && seededSelfMember && !selfLeft)
        )

/**
 * Whether a text [ConversationController.send] can commit an optimistic record.
 * This must stay aligned with the UI's input-clearing decision (issue #264).
 */
internal fun canAcceptTextSend(
    accountRef: String?,
    trimmed: String,
    membersVerified: Boolean,
    isSelfMember: Boolean,
    seededSelfMember: Boolean,
    selfLeft: Boolean,
    unrecoverable: Boolean,
    disbanding: Boolean,
    disbanded: Boolean,
): Boolean =
    trimmed.isNotEmpty() &&
        canAcceptSeededMemberMutation(
            accountRef = accountRef,
            membersVerified = membersVerified,
            isSelfMember = isSelfMember,
            seededSelfMember = seededSelfMember,
            selfLeft = selfLeft,
            unrecoverable = unrecoverable,
            disbanding = disbanding,
            disbanded = disbanded,
        )

/**
 * Reactions use the same seed-safe membership window as text sends. The
 * authoritative engine still validates the mutation and rolls back the
 * optimistic reaction if the cached membership has become stale.
 */
internal fun canAcceptReaction(
    accountRef: String?,
    membersVerified: Boolean,
    isSelfMember: Boolean,
    seededSelfMember: Boolean,
    selfLeft: Boolean,
    unrecoverable: Boolean,
    disbanding: Boolean,
    disbanded: Boolean,
): Boolean =
    canAcceptSeededMemberMutation(
        accountRef = accountRef,
        membersVerified = membersVerified,
        isSelfMember = isSelfMember,
        seededSelfMember = seededSelfMember,
        selfLeft = selfLeft,
        unrecoverable = unrecoverable,
        disbanding = disbanding,
        disbanded = disbanded,
    )
