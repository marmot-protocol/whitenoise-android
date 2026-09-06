package dev.ipf.whitenoise.android.ui.conversation

import dev.ipf.whitenoise.android.core.MessageProjector
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

/** Reads the same transient state as ConversationScreen before either control surface is composed. */
internal fun ConversationSurfaceState.hasVisibleComposer(
    controller: ConversationController,
    notificationOpenRequestId: Long,
): Boolean =
    !showDetails.value &&
        !searchOpen.value &&
        selectedMessages.isEmpty() &&
        !initialTimelineBackfillNoProgress.value &&
        !(controller.error != null && controller.timeline.none { !MessageProjector.isEdit(it.record) }) &&
        conversationControllerComposerGate(controller, notificationOpenRequestId) == ComposerGate.COMPOSER
