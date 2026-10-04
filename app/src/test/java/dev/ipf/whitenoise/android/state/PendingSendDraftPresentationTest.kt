package dev.ipf.whitenoise.android.state

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.text.input.TextFieldValue
import androidx.test.core.app.ApplicationProvider
import com.github.takahirom.roborazzi.captureRoboImage
import dev.ipf.marmotkit.AccountSummaryFfi
import dev.ipf.marmotkit.AppBlobEndpointFfi
import dev.ipf.marmotkit.AppGroupEncryptedMediaComponentFfi
import dev.ipf.marmotkit.AppGroupMemberRecordFfi
import dev.ipf.marmotkit.AppGroupRecordFfi
import dev.ipf.marmotkit.AppProtocolProfileFfi
import dev.ipf.marmotkit.ChatListAttachmentKindFfi
import dev.ipf.marmotkit.ChatListDraftPreviewFfi
import dev.ipf.marmotkit.ChatListMessageDeliveryStateFfi
import dev.ipf.marmotkit.EncryptedMediaVersionFfi
import dev.ipf.marmotkit.MarmotKitException
import dev.ipf.marmotkit.SelectedChatPreviewFfi
import dev.ipf.marmotkit.SelfMembershipFfi
import dev.ipf.marmotkit.SendAcceptDispositionFfi
import dev.ipf.marmotkit.SendMaintenanceDispositionFfi
import dev.ipf.marmotkit.SendSummaryFfi
import dev.ipf.whitenoise.android.R
import dev.ipf.whitenoise.android.core.EMPTY_MARKDOWN_DOCUMENT
import dev.ipf.whitenoise.android.core.MessageTextCopy
import dev.ipf.whitenoise.android.media.editor.MessageDraftGeneration
import dev.ipf.whitenoise.android.ui.chats.ChatRow
import dev.ipf.whitenoise.android.ui.chats.ChatRowPortFixtures
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
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowLooper
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], qualifiers = "en-w360dp-h780dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
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
                    initialGroup = pendingSendGroup(GROUP_ID, ACCOUNT_ID),
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

    /** Verifies a failed send restores the newest accepted MDK timestamp with its captured text. */
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
                    initialGroup = pendingSendGroup(GROUP_ID, ACCOUNT_ID),
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
                    initialGroup = pendingSendGroup(GROUP_ID, ACCOUNT_ID),
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

    /** The native Draft selection is deliberately held across parser and send settlement. */
    @Test
    fun nativeDraftCannotFlashDuringAcceptedSendLight() = acceptedSendNativeDraft(dark = false)

    @Test
    fun nativeDraftCannotFlashDuringAcceptedSendDark() = acceptedSendNativeDraft(dark = true)

    private fun acceptedSendNativeDraft(dark: Boolean) =
        runTest {
            val fixture = nativeDraftFixture()
            val state = fixture.state
            val chats = fixture.chats
            val staleNative = nativeDraft("sending now")
            try {
                val send = async { state.sendConversationText(fixture.conversation, "sending now") }
                fixture.parserStarted.await()
                chats.setChatListVisible(true)
                renderNativeDraftRow(fixture, staleNative, dark)
                assertSendPreview("preparing", dark, "Sending")
                fixture.finishParse.complete(Unit)
                fixture.publishStarted.await()
                settleChatRowRecompute()
                assertSendPreview("pending", dark, "sending now")
                composeRule.runOnIdle { state.setDraft(TextFieldValue("sending now")) }
                composeRule.onNodeWithText("Draft: sending now", useUnmergedTree = true).assertExists()
                fixture.finishPublish.complete(Unit)
                send.await()
                settleChatRowRecompute()
                composeRule.onNodeWithText("Draft: sending now", useUnmergedTree = true).assertExists()
                assertEquals("sending now", state.draftFor(ACCOUNT_REF, GROUP_ID))
                assertEquals(
                    ChatListMessageDeliveryStateFfi.DELIVERED,
                    chats.items
                        .single()
                        .projection
                        ?.lastMessage
                        ?.deliveryState,
                )
            } finally {
                fixture.close()
            }
        }

    private fun renderNativeDraftRow(
        fixture: NativeDraftFixture,
        staleNative: SelectedChatPreviewFfi,
        dark: Boolean,
    ) {
        composeRule.setContent {
            WhiteNoiseTheme(darkTheme = dark) {
                fixture.chats.items.singleOrNull()?.let { item ->
                    ChatRow(
                        item = item.copy(selectedPreview = staleNative),
                        appState = fixture.state,
                        onClick = {},
                        onOpenProfile = {},
                    )
                }
            }
        }
    }

    private fun settleChatRowRecompute() {
        ShadowLooper.idleMainLooper(32, TimeUnit.MILLISECONDS)
        composeRule.waitForIdle()
    }

    private fun assertSendPreview(
        stage: String,
        dark: Boolean,
        text: String,
    ) {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        composeRule.onNodeWithText(text, useUnmergedTree = true).assertExists()
        composeRule.onNodeWithContentDescription(context.getString(R.string.sending)).assertExists()
        composeRule.onNodeWithText("Draft: sending now", useUnmergedTree = true).assertDoesNotExist()
        val theme = if (dark) "dark" else "light"
        composeRule.onRoot().captureRoboImage("src/test/snapshots/chat_row_send_${stage}_$theme.png")
    }

    private fun nativeDraftFixture(failPublication: Boolean = false): NativeDraftFixture {
        val state = appState()
        state.setDraft(TextFieldValue("sending now"))
        val row = requireNotNull(ChatRowPortFixtures.item().projection).copy(groupIdHex = GROUP_ID)
        val chats =
            ChatsController(
                state,
                initialAccountRef = ACCOUNT_REF,
                memberSnapshotLoader = { _, _ -> emptyList() },
            )
        state.attachChatsController(chats)
        chats.setChatListVisible(false)
        chats.applyChatListRow(row)
        val parserStarted = CompletableDeferred<Unit>()
        val finishParse = CompletableDeferred<Unit>()
        val publishStarted = CompletableDeferred<Unit>()
        val finishPublish = CompletableDeferred<Unit>()
        val conversation =
            ConversationController(
                appState = state,
                initialGroup = pendingSendGroup(GROUP_ID, ACCOUNT_ID),
                initialMemberSnapshot = memberSnapshot(),
                markdownParser = {
                    parserStarted.complete(Unit)
                    finishParse.await()
                    EMPTY_MARKDOWN_DOCUMENT
                },
                textPublisher = { _, _, _, _ ->
                    publishStarted.complete(Unit)
                    finishPublish.await()
                    if (failPublication) throw MarmotKitException.Publish("relay rejected event")
                    SendSummaryFfi(
                        1u,
                        listOf(CONFIRMED_MESSAGE_ID),
                        SendAcceptDispositionFfi.PUBLISHED,
                        SendMaintenanceDispositionFfi.READY,
                    )
                },
            )
        return NativeDraftFixture(state, chats, conversation, parserStarted, finishParse, publishStarted, finishPublish)
    }

    @Test
    fun cancellingBeforeParsingCompletesRemovesThePreparingRowIndicator() = stoppedSendPreview(cancel = true)

    @Test
    fun terminalPublicationFailureReplacesThePreparingRowIndicatorWithFailure() = stoppedSendPreview(cancel = false)

    private fun stoppedSendPreview(cancel: Boolean) =
        runTest {
            val fixture = nativeDraftFixture(failPublication = !cancel)
            try {
                val send = async { fixture.state.sendConversationText(fixture.conversation, "sending now") }
                fixture.parserStarted.await()
                fixture.chats.setChatListVisible(true)
                assertEquals(
                    true,
                    fixture.chats.items
                        .single()
                        .awaitingSendPreview,
                )
                if (cancel) {
                    val pending =
                        fixture.conversation.timeline
                            .single()
                            .record
                    assertEquals(true, fixture.conversation.deleteMessage(pending, presentFailure = false))
                }
                fixture.finishParse.complete(Unit)
                if (!cancel) fixture.publishStarted.await()
                fixture.finishPublish.complete(Unit)
                send.await()
                settleChatRowRecompute()
                val item = fixture.chats.items.single()
                assertEquals(false, item.awaitingSendPreview)
                if (cancel) {
                    assertEquals(false, item.hasOptimisticSendPreview)
                    assertEquals(false, fixture.publishStarted.isCompleted)
                } else {
                    assertEquals(OutgoingMessageIndicator.Failed, item.projectedDeliveryIndicator())
                }
                val native = nativeDraft("sending now")
                assertEquals(native, fixture.state.chatRowSelectedPreviewFor(ACCOUNT_REF, GROUP_ID, native))
            } finally {
                fixture.close()
            }
        }

    private data class NativeDraftFixture(
        val state: WhiteNoiseAppState,
        val chats: ChatsController,
        val conversation: ConversationController,
        val parserStarted: CompletableDeferred<Unit>,
        val finishParse: CompletableDeferred<Unit>,
        val publishStarted: CompletableDeferred<Unit>,
        val finishPublish: CompletableDeferred<Unit>,
    ) {
        fun close() {
            finishParse.complete(Unit)
            finishPublish.complete(Unit)
            conversation.onCleared()
            chats.onCleared()
        }
    }

    @Test
    fun clearingANewerDraftCannotReviveTheSentNativeDraftAndSignOutClearsTheFence() {
        val presentation = SentComposerDraftPresentation()
        val generation = MessageDraftGeneration(1L)
        val next = MessageDraftGeneration(2L)
        val token = DraftSendClearToken(ACCOUNT_REF, GROUP_ID, generation, null, null)
        val native = nativeDraft("sent draft")
        presentation.hide(token)
        assertEquals(
            SelectedChatPreviewFfi.Message,
            presentation.selectedPreview(ACCOUNT_REF, GROUP_ID, generation, null, native),
        )
        assertEquals(
            SelectedChatPreviewFfi.Empty,
            presentation.selectedPreview(ACCOUNT_REF, GROUP_ID, generation, null, SelectedChatPreviewFfi.Empty),
        )
        val selectedNewDraft = presentation.selectedPreview(ACCOUNT_REF, GROUP_ID, next, "newer draft", native)
        assertEquals("newer draft", (selectedNewDraft as SelectedChatPreviewFfi.Draft).draft.text)
        presentation.onDraftChanged(ACCOUNT_REF, GROUP_ID, next, "")
        assertEquals(
            SelectedChatPreviewFfi.Message,
            presentation.selectedPreview(ACCOUNT_REF, GROUP_ID, next, null, native),
        )
        assertEquals(native, presentation.selectedPreview("other-account", GROUP_ID, next, null, native))
        presentation.removeAccount(ACCOUNT_REF)
        assertEquals(native, presentation.selectedPreview(ACCOUNT_REF, GROUP_ID, next, null, native))
    }

    @Test
    fun deletingALaterCaptionPreservesTheAttachmentOnlyNativeDraft() =
        runTest {
            val fixture = nativeDraftFixture()
            val state = fixture.state
            try {
                val send = async { state.sendConversationText(fixture.conversation, "sending now") }
                fixture.parserStarted.await()
                state.setDraft(TextFieldValue("later caption"))
                state.setDraft(TextFieldValue(""))
                val attachment = nativeAttachmentDraft()
                assertEquals(attachment, state.chatRowSelectedPreviewFor(ACCOUNT_REF, GROUP_ID, attachment))
                val empty = state.chatRowSelectedPreviewFor(ACCOUNT_REF, GROUP_ID, SelectedChatPreviewFfi.Empty)
                assertEquals(SelectedChatPreviewFfi.Empty, empty)
                val outgoing =
                    state.chatRowSelectedPreviewFor(
                        accountRef = ACCOUNT_REF,
                        groupIdHex = GROUP_ID,
                        nativePreview = SelectedChatPreviewFfi.Empty,
                        hasOptimisticSendPreview = true,
                    )
                assertEquals(SelectedChatPreviewFfi.Message, outgoing)
                fixture.finishParse.complete(Unit)
                fixture.finishPublish.complete(Unit)
                send.await()
            } finally {
                fixture.close()
            }
        }

    @Test
    fun aNewerNativeCaptionKeepsItsAttachmentMetadata() {
        val presentation = SentComposerDraftPresentation()
        val first = MessageDraftGeneration(1L)
        val snapshot = ComposerDraftSnapshot(TextFieldValue("sent"), false)
        val token = DraftSendClearToken(ACCOUNT_REF, GROUP_ID, first, snapshot, null)
        presentation.hide(token)
        val attachment = nativeAttachmentDraft("new caption", 2uL)
        val next = MessageDraftGeneration(2L)
        val selected = presentation.selectedPreview(ACCOUNT_REF, GROUP_ID, next, "new caption", attachment)
        assertEquals(attachment, selected)
        assertEquals(attachment, presentation.selectedPreview(ACCOUNT_REF, GROUP_ID, first, null, attachment))
        val truncated = nativeAttachmentDraft("new", 2uL)
        truncated.draft.textTruncated = true
        assertEquals(truncated, presentation.selectedPreview(ACCOUNT_REF, GROUP_ID, next, "new caption", truncated))
    }

    @Test
    fun aNewerAttachmentMutationPreservesItsNativeDraftWithoutLocalText() {
        val presentation = SentComposerDraftPresentation()
        presentation.hide(DraftSendClearToken(ACCOUNT_REF, GROUP_ID, MessageDraftGeneration(1L), null, null))
        val attachmentDraft =
            SelectedChatPreviewFfi.Draft(
                ChatListDraftPreviewFfi("", true, 1uL, ChatListAttachmentKindFfi.PHOTO),
            )
        assertEquals(
            attachmentDraft,
            presentation.selectedPreview(ACCOUNT_REF, GROUP_ID, MessageDraftGeneration(2L), null, attachmentDraft),
        )
    }

    @Test
    fun nativeDraftFenceAndPendingPreviewSurviveChatControllerReplacement() =
        runTest {
            val fixture = nativeDraftFixture()
            val replacement =
                ChatsController(
                    fixture.state,
                    initialAccountRef = ACCOUNT_REF,
                    memberSnapshotLoader = { _, _ -> emptyList() },
                )
            try {
                val send = async { fixture.state.sendConversationText(fixture.conversation, "sending now") }
                fixture.parserStarted.await()
                fixture.state.replaceChatsController(fixture.chats, replacement)
                replacement.setChatListVisible(true)
                assertEquals(true, replacement.items.single().awaitingSendPreview)
                assertEquals(
                    SelectedChatPreviewFfi.Message,
                    fixture.state.chatRowSelectedPreviewFor(
                        ACCOUNT_REF,
                        GROUP_ID,
                        nativeDraft("sending now"),
                    ),
                )
                fixture.finishParse.complete(Unit)
                fixture.publishStarted.await()
                // Controller debounce belongs to the Android main looper, separately from Compose frames.
                settleChatRowRecompute()
                assertEquals(
                    "sending now",
                    replacement.items
                        .single()
                        .projection
                        ?.lastMessage
                        ?.plaintext,
                )
                fixture.finishPublish.complete(Unit)
                send.await()
            } finally {
                fixture.close()
                replacement.onCleared()
            }
        }

    @Test
    fun definiteFailureRestoresNativeDraftButDurableCleanupKeepsItHidden() =
        runTest {
            val state = appState()
            state.setDraft(TextFieldValue("try again"))
            val native = nativeDraft("try again")
            val failed =
                ConversationController(
                    appState = state,
                    initialGroup = pendingSendGroup(GROUP_ID, ACCOUNT_ID),
                    initialMemberSnapshot = memberSnapshot(),
                    textPublisher = { _, _, _, _ -> throw MarmotKitException.Publish("relay rejected event") },
                )
            state.sendConversationText(failed, "try again")
            assertEquals(native, state.chatRowSelectedPreviewFor(ACCOUNT_REF, GROUP_ID, native))
            val started = CompletableDeferred<Unit>()
            val finish = CompletableDeferred<Unit>().also { it.complete(Unit) }
            val sent = controller(state, started, finish)
            state.sendConversationText(sent, "try again")
            assertEquals(SelectedChatPreviewFfi.Message, state.chatRowSelectedPreviewFor(ACCOUNT_REF, GROUP_ID, native))
            assertEquals(native, state.chatRowSelectedPreviewFor("another-account", GROUP_ID, native))
            failed.onCleared()
            sent.onCleared()
        }

    private fun nativeAttachmentDraft(
        text: String = "",
        count: ULong = 1uL,
    ) = SelectedChatPreviewFfi.Draft(ChatListDraftPreviewFfi(text, false, count, ChatListAttachmentKindFfi.PHOTO))

    private fun nativeDraft(text: String) =
        SelectedChatPreviewFfi.Draft(
            ChatListDraftPreviewFfi(text, false, 0uL, null),
        )

    private fun controller(
        appState: WhiteNoiseAppState,
        publishStarted: CompletableDeferred<Unit>,
        finishPublish: CompletableDeferred<Unit>,
    ) = ConversationController(
        appState = appState,
        initialGroup = pendingSendGroup(GROUP_ID, ACCOUNT_ID),
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

    /** Creates account-bound state while allowing timestamp-aware tests to inject their draft store. */
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

private fun pendingSendGroup(
    groupIdHex: String,
    accountIdHex: String,
) = AppGroupRecordFfi(
    groupIdHex = groupIdHex,
    protocolProfile = AppProtocolProfileFfi.LEGACY,
    endpoint = "wss://relay.example",
    profilePresent = true,
    name = "Pending draft group",
    description = "",
    admins = listOf(accountIdHex),
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
