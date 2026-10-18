package dev.ipf.whitenoise.android.state

import androidx.compose.ui.text.input.TextFieldValue
import androidx.test.core.app.ApplicationProvider
import dev.ipf.marmotkit.AccountSummaryFfi
import dev.ipf.marmotkit.AppBlobEndpointFfi
import dev.ipf.marmotkit.AppGroupEncryptedMediaComponentFfi
import dev.ipf.marmotkit.AppGroupMemberRecordFfi
import dev.ipf.marmotkit.AppGroupRecordFfi
import dev.ipf.marmotkit.AppProtocolProfileFfi
import dev.ipf.marmotkit.EncryptedMediaVersionFfi
import dev.ipf.marmotkit.SelfMembershipFfi
import dev.ipf.marmotkit.SendAcceptDispositionFfi
import dev.ipf.marmotkit.SendMaintenanceDispositionFfi
import dev.ipf.marmotkit.SendSummaryFfi
import dev.ipf.whitenoise.android.ui.group.GroupDetailsConfirmationKind
import dev.ipf.whitenoise.android.ui.group.groupActionMemberHex
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * A notification-routed conversation can open before its account switch lands
 * (#586). The controller must then bind every account-explicit operation to
 * the pinned target account, never the still-active previous one.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], qualifiers = "en")
class ConversationControllerAccountPinningTest {
    @Test
    fun pinnedControllerSendsWithTheTargetAccountWhileAnotherAccountIsActive() =
        runTest {
            var sendAccount: String? = null
            val controller =
                ConversationController(
                    appState = appState(activeAccountRef = PREVIOUS_REF),
                    initialGroup = group(),
                    initialMemberSnapshot = targetMemberSnapshot(),
                    accountRefOverride = TARGET_REF,
                    textPublisher = { _, account, _, _ ->
                        sendAccount = account
                        SendSummaryFfi(
                            published = 1u,
                            messageIds = listOf(MESSAGE_ID),
                            acceptDisposition = SendAcceptDispositionFfi.PUBLISHED,
                            maintenanceDisposition = SendMaintenanceDispositionFfi.READY,
                        )
                    },
                )

            controller.send("hello from the pinned account")

            assertEquals(TARGET_REF, sendAccount)
            assertEquals(TARGET_REF, controller.boundAccountRef)
        }

    @Test
    fun pinnedConversationDraftAndDurableClearStayWithTheTargetAccount() =
        runTest {
            val appState = appState(activeAccountRef = PREVIOUS_REF)
            appState.setDraft(PREVIOUS_REF, GROUP_ID, TextFieldValue("previous-account draft"))
            appState.setDraft(TARGET_REF, GROUP_ID, TextFieldValue("target-account draft"))
            val controller =
                ConversationController(
                    appState = appState,
                    initialGroup = group(),
                    initialMemberSnapshot = targetMemberSnapshot(),
                    accountRefOverride = TARGET_REF,
                    textPublisher = { _, account, _, _ ->
                        assertEquals(TARGET_REF, account)
                        SendSummaryFfi(
                            published = 1u,
                            messageIds = listOf(MESSAGE_ID),
                            acceptDisposition = SendAcceptDispositionFfi.PUBLISHED,
                            maintenanceDisposition = SendMaintenanceDispositionFfi.READY,
                        )
                    },
                )

            assertEquals("target-account draft", appState.draftFor(controller.boundAccountRef, GROUP_ID))
            appState.sendConversationText(controller, "target-account draft")

            assertEquals(null, appState.draftFor(TARGET_REF, GROUP_ID))
            assertEquals("previous-account draft", appState.draftFor(PREVIOUS_REF, GROUP_ID))
        }

    @Test
    fun pinnedControllerResolvesSelfIdentityFromTheTargetAccount() {
        val controller =
            ConversationController(
                appState = appState(activeAccountRef = PREVIOUS_REF),
                initialGroup = group(),
                initialMemberSnapshot = targetMemberSnapshot(),
                accountRefOverride = TARGET_REF,
            )

        assertEquals(TARGET_ID, controller.boundAccountIdHex)
        listOf(
            GroupDetailsConfirmationKind.StepDown,
            GroupDetailsConfirmationKind.StepDownSoleAdmin,
            GroupDetailsConfirmationKind.Leave,
            GroupDetailsConfirmationKind.LeaveSoleMember,
            GroupDetailsConfirmationKind.BlockedLeave,
        ).forEach { kind ->
            assertEquals(TARGET_ID, groupActionMemberHex(kind, controller.boundAccountIdHex, PREVIOUS_ID))
        }
    }

    @Test
    fun pinnedControllerWithMissingAccountLabelFailsClosedInsteadOfBorrowingTheActiveIdentity() {
        val controller =
            ConversationController(
                appState = appState(activeAccountRef = PREVIOUS_REF),
                initialGroup = group(),
                accountRefOverride = "vanished-account",
            )

        assertEquals("vanished-account", controller.boundAccountRef)
        assertEquals(null, controller.boundAccountIdHex)
        listOf(
            GroupDetailsConfirmationKind.StepDown,
            GroupDetailsConfirmationKind.StepDownSoleAdmin,
            GroupDetailsConfirmationKind.Leave,
            GroupDetailsConfirmationKind.LeaveSoleMember,
            GroupDetailsConfirmationKind.BlockedLeave,
        ).forEach { kind ->
            assertEquals(null, groupActionMemberHex(kind, controller.boundAccountIdHex, PREVIOUS_ID))
        }
    }

    @Test
    fun pinnedControllerLoadingStateUsesItsAccountOverride() {
        assertTrue(
            conversationStartsLoading(
                startOnConstruction = true,
                accountRefOverride = TARGET_REF,
                activeAccountRef = null,
            ),
        )
        assertFalse(
            conversationStartsLoading(
                startOnConstruction = true,
                accountRefOverride = null,
                activeAccountRef = null,
            ),
        )
    }

    @Test
    fun unpinnedControllerKeepsBindingToTheActiveAccount() {
        val controller =
            ConversationController(
                appState = appState(activeAccountRef = PREVIOUS_REF),
                initialGroup = group(),
            )

        assertEquals(PREVIOUS_REF, controller.boundAccountRef)
        assertEquals(PREVIOUS_ID, controller.boundAccountIdHex)
    }

    /** Native acknowledgement belongs to the send owner even if its presenting controller is disposed. */
    @Test
    fun disposalBeforeSendAcknowledgementKeepsThePinnedSendAndRetainedDraftOwner() =
        runTest {
            val state = appState(activeAccountRef = PREVIOUS_REF)
            state.setDraft(TARGET_REF, GROUP_ID, TextFieldValue("pending acknowledgement"))
            state.setDraft(PREVIOUS_REF, GROUP_ID, TextFieldValue("other account draft"))
            val entered = CompletableDeferred<Unit>()
            val acknowledge = CompletableDeferred<Unit>()
            var nativeCalls = 0
            val controller =
                ConversationController(
                    appState = state,
                    initialGroup = group(),
                    initialMemberSnapshot = targetMemberSnapshot(),
                    accountRefOverride = TARGET_REF,
                    markdownParser = { dev.ipf.whitenoise.android.core.EMPTY_MARKDOWN_DOCUMENT },
                    textPublisher = { _, account, groupId, _ ->
                        assertEquals(TARGET_REF, account)
                        assertEquals(GROUP_ID, groupId)
                        nativeCalls += 1
                        entered.complete(Unit)
                        acknowledge.await()
                        SendSummaryFfi(
                            published = 0u,
                            messageIds = listOf(MESSAGE_ID),
                            acceptDisposition = SendAcceptDispositionFfi.ACCEPTED_PENDING,
                            maintenanceDisposition = SendMaintenanceDispositionFfi.READY,
                        )
                    },
                )
            val send =
                async(start = CoroutineStart.UNDISPATCHED) {
                    state.sendConversationText(controller, "pending acknowledgement")
                }
            try {
                entered.await()
                controller.onCleared()
                assertFalse("disposal must leave the native admission waiter alive", send.isCancelled)
                acknowledge.complete(Unit)
                assertTrue(send.await())
                assertEquals(1, nativeCalls)
                assertEquals(null, state.draftFor(TARGET_REF, GROUP_ID))
                assertEquals("other account draft", state.draftFor(PREVIOUS_REF, GROUP_ID))
                assertFalse(controller.acceptsConversationActionOwner(TARGET_REF, GROUP_ID))
                val replacement =
                    ConversationController(
                        appState = state,
                        initialGroup = group(),
                        initialMemberSnapshot = targetMemberSnapshot(),
                        accountRefOverride = TARGET_REF,
                    )
                try {
                    assertEquals(1, replacement.timeline.count { it.record.plaintext == "pending acknowledgement" })
                    assertEquals(1, nativeCalls)
                } finally {
                    replacement.onCleared()
                }
            } finally {
                acknowledge.complete(Unit)
                controller.onCleared()
            }
        }

    /** Complementary app-owner coverage: a pending Retry acknowledgement is independent of controller disposal. */
    @Test
    fun appOwnedRetryAdmissionSurvivesControllerDisposal() =
        runTest {
            val state = appState(activeAccountRef = TARGET_REF)
            val controller = ConversationController(state, group(), targetMemberSnapshot())
            val entered = CompletableDeferred<Unit>()
            val acknowledge = CompletableDeferred<Unit>()
            val accepted = CompletableDeferred<Unit>()
            val key = mediaCacheKey(TARGET_REF, GROUP_ID, MESSAGE_ID, 0)
            var nativeCalls = 0
            controller.attachmentTransferState(MESSAGE_ID, 0, initiallyAvailable = false)
            assertTrue(
                state.attachmentUserActions.retry(
                    key,
                    admit = {
                        nativeCalls += 1
                        entered.complete(Unit)
                        acknowledge.await()
                    },
                    onAccepted = { accepted.complete(Unit) },
                    onFailure = { accepted.completeExceptionally(it) },
                ),
            )
            try {
                awaitConversationCondition { entered.isCompleted }
                controller.releaseAttachmentTransferState(MESSAGE_ID, 0)
                controller.onCleared()
                assertTrue(key in state.attachmentUserActions.pendingRetries.value)
                assertFalse(accepted.isCompleted)
                acknowledge.complete(Unit)
                awaitConversationCondition { accepted.isCompleted }
                accepted.await()
                assertEquals(1, nativeCalls)
                assertFalse(key in state.attachmentUserActions.pendingRetries.value)
            } finally {
                acknowledge.complete(Unit)
                controller.onCleared()
            }
        }

    /** Matching display ids on another account or group cannot satisfy the extracted owner's cache probe. */
    @Test
    fun attachmentPresentationKeepsItsPinnedAccountAndGroupCacheIdentity() {
        val state = appState(activeAccountRef = PREVIOUS_REF)
        val controller =
            ConversationController(state, group(), targetMemberSnapshot(), accountRefOverride = TARGET_REF)
        try {
            state.cacheMediaPlaintext(mediaCacheKey(PREVIOUS_REF, GROUP_ID, MESSAGE_ID, 0), byteArrayOf(1))
            state.cacheMediaPlaintext(mediaCacheKey(TARGET_REF, "other-group", MESSAGE_ID, 0), byteArrayOf(2))
            assertFalse(controller.hasCachedAttachment(MESSAGE_ID, 0))
            state.cacheMediaPlaintext(mediaCacheKey(TARGET_REF, GROUP_ID, MESSAGE_ID, 0), byteArrayOf(3))
            assertTrue(controller.hasCachedAttachment(MESSAGE_ID, 0))
            assertFalse(controller.hasCachedAttachment(MESSAGE_ID, 1))
        } finally {
            controller.onCleared()
        }
    }

    private fun appState(activeAccountRef: String) =
        WhiteNoiseAppState(
            context = ApplicationProvider.getApplicationContext(),
            draftStore = DraftStore(NoopDraftPersistence()),
            accountIdHexResolver = { PREVIOUS_ID },
            accounts =
                listOf(
                    AccountSummaryFfi(
                        label = PREVIOUS_REF,
                        accountIdHex = PREVIOUS_ID,
                        localSigning = true,
                        externalSigning = false,
                        signedOut = false,
                        running = true,
                    ),
                    AccountSummaryFfi(
                        label = TARGET_REF,
                        accountIdHex = TARGET_ID,
                        localSigning = true,
                        externalSigning = false,
                        signedOut = false,
                        running = true,
                    ),
                ),
            activeAccountRef = activeAccountRef,
        )

    private fun targetMemberSnapshot() =
        GroupMemberSnapshot(
            listOf(
                AppGroupMemberRecordFfi(
                    memberIdHex = TARGET_ID,
                    account = TARGET_REF,
                    local = true,
                ),
            ),
        )

    private fun group() =
        AppGroupRecordFfi(
            groupIdHex = GROUP_ID,
            protocolProfile = AppProtocolProfileFfi.LEGACY,
            endpoint = "wss://relay.example",
            profilePresent = true,
            name = "Pinned group",
            description = "",
            admins = listOf(TARGET_ID),
            relays = listOf("wss://relay.example"),
            nostrGroupIdHex = "04".repeat(32),
            avatarUrl = null,
            avatarDim = null,
            avatarThumbhash = null,
            imageHashHex = null,
            encryptedMedia =
                AppGroupEncryptedMediaComponentFfi(
                    componentId = 0x8008u,
                    component = "marmot.group.encrypted-media.v1",
                    required = true,
                    version = EncryptedMediaVersionFfi.V1,
                    mediaFormat = "encrypted-media-v1",
                    allowedLocatorKinds = listOf("blossom-v1"),
                    defaultBlobEndpoints =
                        listOf(
                            AppBlobEndpointFfi(
                                locatorKind = "blossom-v1",
                                baseUrl = "https://blossom.example",
                            ),
                        ),
                ),
            disappearingMessageSecs = 0uL,
            archived = false,
            pendingConfirmation = false,
            unrecoverable = false,
            selfMembership = SelfMembershipFfi.MEMBER,
            leaveRequestPending = false,
            leaveRequestedAtMs = null,
            disbanding = false,
            disbandRequest = null,
            disbanded = false,
            welcomerAccountIdHex = null,
            viaWelcomeMessageIdHex = null,
        )

    private class NoopDraftPersistence : DraftPersistence {
        override fun read(): Map<String, String> = emptyMap()

        override fun write(
            key: String,
            value: String?,
        ) = Unit
    }

    private companion object {
        const val PREVIOUS_REF = "alice"
        const val TARGET_REF = "bob"
        val PREVIOUS_ID = "a1".repeat(32)
        val TARGET_ID = "e5".repeat(32)
        val GROUP_ID = "b2".repeat(32)
        val MESSAGE_ID = "c3".repeat(32)
    }
}
