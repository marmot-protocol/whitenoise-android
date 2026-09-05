package dev.ipf.whitenoise.android.state

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.text.input.TextFieldValue
import androidx.test.core.app.ApplicationProvider
import dev.ipf.marmotkit.AccountSummaryFfi
import dev.ipf.marmotkit.AppBlobEndpointFfi
import dev.ipf.marmotkit.AppGroupEncryptedMediaComponentFfi
import dev.ipf.marmotkit.AppGroupMemberRecordFfi
import dev.ipf.marmotkit.AppGroupRecordFfi
import dev.ipf.marmotkit.AppProtocolProfileFfi
import dev.ipf.marmotkit.EncryptedMediaVersionFfi
import dev.ipf.marmotkit.MarmotKitException
import dev.ipf.marmotkit.SelfMembershipFfi
import dev.ipf.marmotkit.SendAcceptDispositionFfi
import dev.ipf.marmotkit.SendMaintenanceDispositionFfi
import dev.ipf.marmotkit.SendSummaryFfi
import dev.ipf.whitenoise.android.core.MessageTextCopy
import dev.ipf.whitenoise.android.ui.conversation.composer.ComposerBar
import dev.ipf.whitenoise.android.ui.theme.WhiteNoiseTheme
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], qualifiers = "en")
class PendingSendDraftPresentationTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun pendingSendDoesNotReturnToComposerAfterLeavingAndReopeningConversation() =
        runTest {
            val appState = appState()
            val publishStarted = CompletableDeferred<Unit>()
            val finishPublish = CompletableDeferred<Unit>()
            val controller = controller(appState, publishStarted, finishPublish)
            var conversationOpen by mutableStateOf(true)

            composeRule.setContent {
                WhiteNoiseTheme {
                    if (conversationOpen) {
                        ComposerBar(
                            replyingTo = null,
                            messageTextCopy = MessageTextCopy.Default,
                            onCancelReply = {},
                            onSend = { _, _ -> },
                            initialDraft =
                                appState
                                    .draftSnapshotFor(ACCOUNT_REF, GROUP_ID)
                                    ?.textFieldValue
                                    ?: TextFieldValue(""),
                            onDraftChange = { appState.setDraft(it) },
                            draftKey = GROUP_ID,
                        )
                    }
                }
            }

            composeRule.onNode(hasSetTextAction()).performTextInput("sending now")
            composeRule.waitForIdle()
            val send =
                async {
                    appState.sendConversationText(controller, "sending now")
                }
            publishStarted.await()

            composeRule.runOnIdle { conversationOpen = false }
            composeRule.runOnIdle { conversationOpen = true }

            composeRule.onNode(hasSetTextAction()).assertTextEquals("")

            finishPublish.complete(Unit)
            send.await()
            assertEquals(MessageStatus.Sent, controller.timeline.single().status)
        }

    @Test
    fun pendingSendHidesItsCapturedRecoveryDraftFromComposerAndChatRow() =
        runTest {
            val appState = appState()
            appState.setDraft(TextFieldValue("sending now"))
            val publishStarted = CompletableDeferred<Unit>()
            val finishPublish = CompletableDeferred<Unit>()
            val controller = controller(appState, publishStarted, finishPublish)

            val send = async { appState.sendConversationText(controller, "sending now") }
            publishStarted.await()

            assertEquals(null, appState.draftFor(ACCOUNT_REF, GROUP_ID))
            assertEquals(null, appState.chatRowDraftFor(ACCOUNT_REF, GROUP_ID))
            assertEquals(MessageStatus.Pending, controller.timeline.single().status)

            finishPublish.complete(Unit)
            send.await()

            assertEquals(null, appState.draftFor(ACCOUNT_REF, GROUP_ID))
        }

    @Test
    fun failedSendRestoresItsRecoveryDraftToTheChatRow() =
        runTest {
            val appState = appState()
            appState.setDraft(TextFieldValue("try again"))
            val controller =
                ConversationController(
                    appState = appState,
                    initialGroup = group(),
                    initialMemberSnapshot = memberSnapshot(),
                    textPublisher = { _, _, _, _ ->
                        throw MarmotKitException.Publish("relay rejected event")
                    },
                )

            appState.sendConversationText(controller, "try again")

            assertEquals(MessageStatus.Failed, controller.timeline.single().status)
            assertEquals(
                "try again",
                appState.chatRowDraftFor(ACCOUNT_REF, GROUP_ID),
            )
        }

    @Test
    fun failedSendRestoresTheCapturedAuthoritativeDraftTimestamp() =
        runTest {
            var clock = 100L
            val draftStore = DraftStore(TestDraftPersistence()) { clock }
            val appState = appState(draftStore)
            appState.setDraft(TextFieldValue("try again"))
            clock = 999L
            val controller =
                ConversationController(
                    appState = appState,
                    initialGroup = group(),
                    initialMemberSnapshot = memberSnapshot(),
                    textPublisher = { _, _, _, _ ->
                        // The coalesced MDK save may acknowledge after the UI
                        // has hidden the accepted send but before publishing
                        // fails. Recovery must retain that newer ordering time.
                        draftStore.applyAuthoritativeTimestamp(ACCOUNT_REF, GROUP_ID, draftedAtMs = 250_000)
                        throw MarmotKitException.Publish("relay rejected event")
                    },
                )

            appState.sendConversationText(controller, "try again")

            assertEquals(MessageStatus.Failed, controller.timeline.single().status)
            assertEquals(250uL, draftStore.draftedAtSecondsFor(ACCOUNT_REF, GROUP_ID))
        }

    @Test
    fun ambiguousSendKeepsItsRecoveryDraftHiddenWhileTheBubbleRemainsPending() =
        runTest {
            val appState = appState()
            appState.setDraft(TextFieldValue("may already be delivered"))
            val controller =
                ConversationController(
                    appState = appState,
                    initialGroup = group(),
                    initialMemberSnapshot = memberSnapshot(),
                    textPublisher = { _, _, _, _ ->
                        throw MarmotKitException.Publish("send event timed out")
                    },
                )

            appState.sendConversationText(controller, "may already be delivered")

            assertEquals(MessageStatus.Pending, controller.timeline.single().status)
            assertEquals(null, appState.draftFor(ACCOUNT_REF, GROUP_ID))
            assertEquals(null, appState.chatRowDraftFor(ACCOUNT_REF, GROUP_ID))
        }

    @Test
    fun newerDraftRemainsVisibleWhileTheOlderSendIsPending() =
        runTest {
            val appState = appState()
            appState.setDraft(TextFieldValue("first message"))
            val publishStarted = CompletableDeferred<Unit>()
            val finishPublish = CompletableDeferred<Unit>()
            val controller = controller(appState, publishStarted, finishPublish)

            val send = async { appState.sendConversationText(controller, "first message") }
            publishStarted.await()
            appState.setDraft(TextFieldValue("next message"))

            assertEquals(
                "next message",
                appState.chatRowDraftFor(ACCOUNT_REF, GROUP_ID),
            )

            finishPublish.complete(Unit)
            send.await()

            assertEquals("next message", appState.draftFor(ACCOUNT_REF, GROUP_ID))
        }

    @Test
    fun identicalNewerDraftIsNotMistakenForThePendingSendRecoveryDraft() =
        runTest {
            val appState = appState()
            appState.setDraft(TextFieldValue("same words"))
            val publishStarted = CompletableDeferred<Unit>()
            val finishPublish = CompletableDeferred<Unit>()
            val controller = controller(appState, publishStarted, finishPublish)

            val send = async { appState.sendConversationText(controller, "same words") }
            publishStarted.await()
            appState.setDraft(TextFieldValue("same words"))

            assertEquals(
                "same words",
                appState.chatRowDraftFor(ACCOUNT_REF, GROUP_ID),
            )

            finishPublish.complete(Unit)
            send.await()

            assertEquals("same words", appState.draftFor(ACCOUNT_REF, GROUP_ID))
        }

    private fun controller(
        appState: WhiteNoiseAppState,
        publishStarted: CompletableDeferred<Unit>,
        finishPublish: CompletableDeferred<Unit>,
    ) = ConversationController(
        appState = appState,
        initialGroup = group(),
        initialMemberSnapshot = memberSnapshot(),
        textPublisher = { _, _, _, _ ->
            publishStarted.complete(Unit)
            finishPublish.await()
            SendSummaryFfi(
                published = 1u,
                messageIds = listOf(CONFIRMED_MESSAGE_ID),
                acceptDisposition = SendAcceptDispositionFfi.PUBLISHED,
                maintenanceDisposition = SendMaintenanceDispositionFfi.READY,
            )
        },
    )

    private fun appState(draftStore: DraftStore = DraftStore(TestDraftPersistence())) =
        WhiteNoiseAppState(
            context = ApplicationProvider.getApplicationContext(),
            draftStore = draftStore,
            accountIdHexResolver = { ACCOUNT_ID },
            accounts =
                listOf(
                    AccountSummaryFfi(
                        label = ACCOUNT_REF,
                        accountIdHex = ACCOUNT_ID,
                        localSigning = true,
                        externalSigning = false,
                        signedOut = false,
                        running = true,
                    ),
                ),
            activeAccountRef = ACCOUNT_REF,
        )

    private fun WhiteNoiseAppState.setDraft(value: TextFieldValue) = setDraft(ACCOUNT_REF, GROUP_ID, value)

    private fun memberSnapshot() =
        GroupMemberSnapshot(
            listOf(
                AppGroupMemberRecordFfi(
                    memberIdHex = ACCOUNT_ID,
                    account = ACCOUNT_REF,
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
            name = "Pending draft group",
            description = "",
            admins = listOf(ACCOUNT_ID),
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

    private class TestDraftPersistence : DraftPersistence {
        override fun read(): Map<String, String> = emptyMap()

        override fun write(
            key: String,
            value: String?,
        ) = Unit
    }

    private companion object {
        const val ACCOUNT_REF = "alice"
        val ACCOUNT_ID = "a1".repeat(32)
        val GROUP_ID = "b2".repeat(32)
        val CONFIRMED_MESSAGE_ID = "c3".repeat(32)
    }
}
