package dev.ipf.whitenoise.android.state

import dev.ipf.marmotkit.AccountSetupReadinessFfi
import dev.ipf.marmotkit.AccountSummaryFfi
import dev.ipf.marmotkit.CreateGroupOptionsFfi
import dev.ipf.marmotkit.MarmotKitException
import dev.ipf.marmotkit.TimelineMessageQueryFfi
import dev.ipf.marmotkit.UserProfileMetadataFfi

/** Android's narrow adapter to the same MDK operations used by normal sign-up and conversations. */
internal class AppReviewDemoNative(
    private val appState: WhiteNoiseAppState,
) : ReviewDemoBackend {
    override val activeAccountRef: String?
        get() = appState.activeAccountRef

    override val runtimeGeneration: Int
        get() = appState.runtimeGeneration

    override val foregroundReady: Boolean
        get() =
            appState.appInForeground &&
                appState.phase == AppPhase.Ready &&
                appState.activeAccount?.let { it.localSigning && !it.signedOut } == true &&
                !appState.signOutInProgress &&
                !appState.wipeInProgress

    override suspend fun accounts(): List<ReviewDemoAccount> = appState.marmotIo { listAccounts() }.map { it.forReviewDemo() }

    override suspend fun createAccount(): ReviewDemoAccount = appState.marmotIo { createIdentityWithBootstrapRelays() }.forReviewDemo()

    override suspend fun qualifyAccount(ref: String) {
        appState.marmotIo { enforceAppOwnedAttachmentAcquisitionPolicy(listOf(ref)) }
        appState.refreshAccounts()
    }

    override suspend fun accountNetworkReady(ref: String): Boolean =
        when (appState.marmotIo { accountSetupReadiness(ref) }) {
            AccountSetupReadinessFfi.NETWORK_READY -> true
            AccountSetupReadinessFfi.RECOVERY_REQUIRED -> throw ReviewDemoFailure(ReviewDemoProblem.OperationFailed)
            else -> false
        }

    override suspend fun profilePublished(id: String): Boolean =
        appState.marmotIo { userProfile(id) }?.let { profile ->
            profile.name == DEMO_NAME && profile.displayName == DEMO_NAME && profile.about == DEMO_ABOUT
        } == true

    override suspend fun publishProfile(ref: String) {
        appState.marmotIo {
            publishUserProfileUsingAccountRelays(
                ref,
                UserProfileMetadataFfi(
                    name = DEMO_NAME,
                    displayName = DEMO_NAME,
                    about = DEMO_ABOUT,
                    picture = null,
                    banner = null,
                    nip05 = null,
                    lud16 = null,
                ),
            )
        }
        appState.refreshAccounts()
    }

    override suspend fun existingDirectConversation(
        ref: String,
        peerId: String,
    ): String? {
        val existing = appState.marmotIo { existingDirectConversation(ref, peerId) } ?: return null
        if (!existing.reusable) throw ReviewDemoFailure(ReviewDemoProblem.OperationFailed)
        return existing.groupIdHex
    }

    override suspend fun createDirectConversation(
        ref: String,
        peerId: String,
    ): String {
        val member = appState.marmotIo { normalizeMemberRef(peerId) }
        appState.marmotIo(MarmotTraceSection.PREWARM_KEY_PACKAGES) {
            prewarmGroupMemberKeyPackages(ref, listOf(member.memberRef))
        }
        return try {
            appState.marmotIo(MarmotTraceSection.CREATE_GROUP) {
                createGroupWithOptions(
                    ref,
                    "",
                    listOf(member.memberRef),
                    CreateGroupOptionsFfi(description = null, initialImage = null, disappearingMessageSecs = 0u),
                )
            }
        } catch (projection: MarmotKitException.CreatedGroupProjectionUnavailable) {
            projection.groupIdHex
        }
    }

    override suspend fun invitation(
        ref: String,
        groupId: String,
    ): ReviewDemoInvitation? =
        appState.marmotIo { chatListRow(ref, groupId) }?.let {
            if (it.pendingConfirmation) ReviewDemoInvitation.Pending else ReviewDemoInvitation.Accepted
        }

    override suspend fun acceptInvitation(
        ref: String,
        groupId: String,
    ) {
        try {
            appState.marmotIo { acceptGroupInvite(ref, groupId) }
        } catch (_: MarmotKitException.GroupInviteNotPending) {
            // An interrupted earlier acceptance already won.
        }
    }

    override suspend fun timeline(
        ref: String,
        groupId: String,
    ): List<ReviewDemoMessage> =
        appState
            .marmotIo {
                timelineMessages(
                    ref,
                    TimelineMessageQueryFfi(
                        groupIdHex = groupId,
                        search = null,
                        before = null,
                        beforeMessageId = null,
                        after = null,
                        afterMessageId = null,
                        limit = 100u,
                    ),
                ).messages
            }.asSequence()
            .filter { it.kind == 9uL && !it.deleted }
            .map { record ->
                ReviewDemoMessage(
                    id = record.messageIdHex,
                    token = record.clientToken,
                    sender = record.sender,
                    text = record.plaintext,
                    replyTo = record.replyToMessageIdHex,
                    reactions = record.reactions.userReactions.map { ReviewDemoReaction(it.sender, it.emoji) },
                )
            }.toList()

    override suspend fun submitMessage(
        ref: String,
        groupId: String,
        text: String,
        replyTo: String?,
        token: String,
    ) {
        appState.marmotIo(MarmotTraceSection.TEXT_SEND) {
            sendComposerTextWithToken(ref, groupId, replyTo, text, token)
        }
    }

    override suspend fun submitReaction(
        ref: String,
        groupId: String,
        targetId: String,
        emoji: String,
    ) {
        appState.marmotIo(MarmotTraceSection.MESSAGE_REACT) { reactToMessage(ref, groupId, targetId, emoji) }
    }

    override suspend fun catchUp() {
        try {
            appState.marmotIo(MarmotTraceSection.CATCH_UP) { catchUpAccounts() }
        } catch (_: MarmotKitException.AccountWorkerBusy) {
            // A foreground account switch may already own this catch-up.
        } catch (_: MarmotKitException.AccountWorkerResponseTimedOut) {
            // Inspect the authoritative projection again before retrying.
        }
    }

    override suspend fun activate(
        ref: String,
        stillOwned: () -> Boolean,
    ): Boolean = appState.setActiveAccount(ref, shouldActivate = stillOwned)

    private fun AccountSummaryFfi.forReviewDemo() = ReviewDemoAccount(label, accountIdHex, localSigning, signedOut)

    private companion object {
        const val DEMO_NAME = "Johnny Appleseed"
        const val DEMO_ABOUT = "App Review demo profile"
    }
}
