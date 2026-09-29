package dev.ipf.whitenoise.android.ui.conversation

import dev.ipf.whitenoise.android.core.MessageProjector
import dev.ipf.whitenoise.android.state.BlockListMirror
import dev.ipf.whitenoise.android.state.ConversationController
import dev.ipf.whitenoise.android.ui.conversation.composer.ComposerGate
import dev.ipf.whitenoise.android.ui.conversation.composer.conversationComposerGate

/** Shared by shell ownership and the rendered bottom bar; never authorizes message delivery. */
internal fun conversationControllerComposerGate(
    controller: ConversationController,
    notificationOpenRequestId: Long,
): ComposerGate =
    conversationComposerGate(
        pendingInvite = controller.group.pendingConfirmation,
        inviteAcceptanceResolutionPending = controller.inviteAcceptanceResolutionPending,
        membersVerified = controller.membersVerified,
        isSelfMember = controller.isSelfMember,
        seededSelfMember = controller.seededSelfMember,
        seededMembershipKnown = controller.seededMembershipKnown,
        assumeMemberUntilVerified = notificationOpenRequestId != 0L,
        unrecoverable = controller.group.unrecoverable,
        disbanding = controller.group.disbanding,
        disbanded = controller.group.disbanded,
    )

/** Blocking replaces only an otherwise usable direct-message composer. */
internal fun blockedDmComposerGate(
    membershipGate: ComposerGate,
    directPeerAccount: String?,
    blocked: Boolean?,
): ComposerGate =
    if (membershipGate == ComposerGate.COMPOSER && directPeerAccount != null && blocked == true) {
        ComposerGate.BLOCKED
    } else {
        membershipGate
    }

/** Read the live account-scoped mirror without treating a missing snapshot as unblocked. */
internal fun blockedDmFromMirror(
    controller: ConversationController,
    mirror: BlockListMirror,
): Boolean? {
    val account = controller.boundAccountRef
    val peer = controller.dmPeerAccount
    val mirrorReady = mirror.accountRef == account && mirror.revision != null
    return if (account != null && peer != null && mirrorReady) {
        mirror.isBlocked(peer)
    } else {
        null
    }
}

/** Reads the same transient state as ConversationScreen before either control surface is composed. */
internal fun ConversationSurfaceState.hasVisibleComposer(
    controller: ConversationController,
    notificationOpenRequestId: Long,
    blockMirror: BlockListMirror,
): Boolean =
    !showDetails.value &&
        !searchOpen.value &&
        selectedMessages.isEmpty() &&
        !initialTimelineBackfillNoProgress.value &&
        !(controller.error != null && controller.timeline.none { !MessageProjector.isEdit(it.record) }) &&
        blockedDmComposerGate(
            membershipGate = conversationControllerComposerGate(controller, notificationOpenRequestId),
            directPeerAccount = controller.dmPeerAccount,
            blocked = blockedDmFromMirror(controller, blockMirror),
        ) == ComposerGate.COMPOSER
