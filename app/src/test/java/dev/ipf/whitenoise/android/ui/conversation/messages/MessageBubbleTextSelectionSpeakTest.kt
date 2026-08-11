package dev.ipf.whitenoise.android.ui.conversation.messages

import android.app.Application
import android.speech.tts.TextToSpeech
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.isRoot
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.test.core.app.ApplicationProvider
import androidx.test.espresso.Espresso.pressBack
import com.github.takahirom.roborazzi.captureRoboImage
import dev.ipf.marmotkit.AccountSummaryFfi
import dev.ipf.marmotkit.AppBlobEndpointFfi
import dev.ipf.marmotkit.AppGroupEncryptedMediaComponentFfi
import dev.ipf.marmotkit.AppGroupRecordFfi
import dev.ipf.marmotkit.AppMessageRecordFfi
import dev.ipf.marmotkit.AppProtocolProfileFfi
import dev.ipf.marmotkit.EncryptedMediaVersionFfi
import dev.ipf.marmotkit.MarkdownDocumentFfi
import dev.ipf.marmotkit.SelfMembershipFfi
import dev.ipf.whitenoise.android.R
import dev.ipf.whitenoise.android.audio.tts.EngineTrust
import dev.ipf.whitenoise.android.audio.tts.FakeSessionEngine
import dev.ipf.whitenoise.android.audio.tts.TtsEngineInfo
import dev.ipf.whitenoise.android.audio.tts.TtsResolutionResult
import dev.ipf.whitenoise.android.state.ConversationController
import dev.ipf.whitenoise.android.state.DraftPersistence
import dev.ipf.whitenoise.android.state.DraftStore
import dev.ipf.whitenoise.android.state.MessageStatus
import dev.ipf.whitenoise.android.state.TimelineMessage
import dev.ipf.whitenoise.android.state.WhiteNoiseAppState
import dev.ipf.whitenoise.android.ui.conversation.composer.ComposerGate
import dev.ipf.whitenoise.android.ui.conversation.composer.ComposerTextState
import dev.ipf.whitenoise.android.ui.theme.WhiteNoiseTheme
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "w360dp-h780dp-mdpi")
class MessageBubbleTextSelectionSpeakTest {
    @get:Rule
    val composeRule = createComposeRule()

    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()
    private val app = ApplicationProvider.getApplicationContext<Application>()

    @Test
    @Suppress("LongMethod")
    fun textSelectionToolbarBackClearsSelectionModeInMessageBubbleHost() {
        var textSelectionMode by mutableStateOf(true)
        val appState = appStateWithTts()
        val item = timelineMessage("Alpha sentence. Beta sentence.")
        val controller = conversationController(appState)

        composeRule.setContent {
            WhiteNoiseTheme {
                Box(Modifier.fillMaxSize().testTag(MESSAGE_HOST_TAG)) {
                    messageBubbleHost(
                        item = item,
                        controller = controller,
                        appState = appState,
                        textSelectionMode = textSelectionMode,
                        onTextSelectionModeChange = { textSelectionMode = it },
                    )
                }
            }
        }
        composeRule.waitForIdle()
        assertNativeSpeakDisplayed()

        pressBack()

        composeRule.runOnIdle { assertFalse(textSelectionMode) }
    }

    @Test
    fun longPressActionMenuSpeakQueuesThroughAppStateAtPressedSentence() {
        val engine = FakeSessionEngine()
        val appState = appStateWithTts(engine)
        val item = timelineMessage("First sentence. Second sentence.")
        val controller = conversationController(appState)
        var actionMenuOpen by mutableStateOf(false)

        composeRule.setContent {
            WhiteNoiseTheme {
                Box(Modifier.fillMaxWidth().testTag(MESSAGE_HOST_TAG)) {
                    messageBubbleHost(
                        item = item,
                        controller = controller,
                        appState = appState,
                        textSelectionMode = false,
                        onTextSelectionModeChange = {},
                        isActionMenuOpen = actionMenuOpen,
                        onActionMenuOpenChange = { actionMenuOpen = it },
                    )
                }
            }
        }
        composeRule.waitForIdle()

        longPressOnMessageText("Second sentence")
        composeRule.waitForIdle()
        assertTrue(actionMenuOpen)

        composeRule.onNodeWithText(app.getString(R.string.speak_aloud)).performClick()
        waitForTts(engine)

        assertTrue(
            engine.spoken
                .first()
                .text
                .endsWith("Second sentence."),
        )
    }

    @Test
    @Suppress("LongMethod")
    fun selectTextFromActionMenuSpeakClearsModeAndStartsAtPressedSentence() {
        val engine = FakeSessionEngine()
        val appState = appStateWithTts(engine)
        val item = timelineMessage("First sentence. Second sentence.")
        val controller = conversationController(appState)
        var textSelectionMode by mutableStateOf(false)
        var actionMenuOpen by mutableStateOf(false)

        composeRule.setContent {
            WhiteNoiseTheme {
                Box(Modifier.fillMaxWidth().testTag(MESSAGE_HOST_TAG)) {
                    messageBubbleHost(
                        item = item,
                        controller = controller,
                        appState = appState,
                        textSelectionMode = textSelectionMode,
                        onTextSelectionModeChange = { textSelectionMode = it },
                        isActionMenuOpen = actionMenuOpen,
                        onActionMenuOpenChange = { actionMenuOpen = it },
                    )
                }
            }
        }
        composeRule.waitForIdle()

        longPressOnMessageText("Second sentence")
        composeRule.waitForIdle()
        assertTrue(actionMenuOpen)

        composeRule.onNodeWithText(app.getString(R.string.select_text)).performClick()
        composeRule.waitForIdle()
        assertTrue(textSelectionMode)
        assertNativeSpeakDisplayed()

        clickNativeSpeak()
        waitForTts(engine)

        composeRule.runOnIdle {
            assertFalse(textSelectionMode)
            assertFalse(
                engine.spoken.first().text,
                engine.spoken
                    .first()
                    .text
                    .contains("First sentence."),
            )
            assertTrue(
                engine.spoken.first().text,
                engine.spoken
                    .first()
                    .text
                    .endsWith("Second sentence."),
            )
        }
    }

    @Test
    @Suppress("LongMethod")
    fun selectionToolbarSemanticSpeakUsesSameSpeakPath() {
        val engine = FakeSessionEngine()
        val appState = appStateWithTts(engine)
        val item = timelineMessage("Accessibility sentence. Later sentence.")
        val controller = conversationController(appState)
        var textSelectionMode by mutableStateOf(false)
        var actionMenuOpen by mutableStateOf(false)

        composeRule.setContent {
            WhiteNoiseTheme {
                Box(Modifier.fillMaxWidth().testTag(MESSAGE_HOST_TAG)) {
                    messageBubbleHost(
                        item = item,
                        controller = controller,
                        appState = appState,
                        textSelectionMode = textSelectionMode,
                        onTextSelectionModeChange = { textSelectionMode = it },
                        isActionMenuOpen = actionMenuOpen,
                        onActionMenuOpenChange = { actionMenuOpen = it },
                    )
                }
            }
        }
        composeRule.waitForIdle()

        longPressOnMessageText("Accessibility sentence")
        composeRule.waitForIdle()
        composeRule.onNodeWithText(app.getString(R.string.select_text)).performClick()
        composeRule.waitForIdle()
        assertNativeSpeakDisplayed()

        composeRule
            .onNodeWithContentDescription(app.getString(R.string.speak_aloud))
            .performClick()
        waitForTts(engine)

        assertTrue(
            engine.spoken
                .first()
                .text
                .contains("Accessibility sentence"),
        )
    }

    @Test
    @Suppress("LongMethod")
    fun messageTextSelectionToolbarHostLightScreenshot() {
        captureHostScreenshot(darkTheme = false, rtl = false, largeText = false)
    }

    @Test
    @Suppress("LongMethod")
    fun messageTextSelectionToolbarHostDarkScreenshot() {
        captureHostScreenshot(darkTheme = true, rtl = false, largeText = false)
    }

    @Test
    @Suppress("LongMethod")
    fun messageTextSelectionToolbarHostRtlLargeTextScreenshot() {
        captureHostScreenshot(darkTheme = true, rtl = true, largeText = true)
    }

    private fun captureHostScreenshot(
        darkTheme: Boolean,
        rtl: Boolean,
        largeText: Boolean,
    ) {
        val appState = appStateWithTts()
        val item = timelineMessage("Selected sentence. Next sentence.")
        val controller = conversationController(appState)
        var textSelectionMode by mutableStateOf(true)
        val snapshot =
            when {
                rtl && largeText -> "src/test/snapshots/message_text_selection_toolbar_rtl_large_text.png"
                darkTheme -> "src/test/snapshots/message_text_selection_toolbar_dark.png"
                else -> "src/test/snapshots/message_text_selection_toolbar_light.png"
            }

        composeRule.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(
                LocalDensity provides Density(density.density, fontScale = if (largeText) 1.5f else 1f),
                LocalLayoutDirection provides if (rtl) LayoutDirection.Rtl else LayoutDirection.Ltr,
            ) {
                WhiteNoiseTheme(darkTheme = darkTheme) {
                    Box(Modifier.fillMaxSize().testTag(MESSAGE_HOST_TAG)) {
                        messageBubbleHost(
                            item = item,
                            controller = controller,
                            appState = appState,
                            textSelectionMode = textSelectionMode,
                            onTextSelectionModeChange = { textSelectionMode = it },
                        )
                    }
                }
            }
        }
        composeRule.waitForIdle()
        assertTrue(textSelectionMode)
        assertNativeSpeakDisplayed()
        composeRule.onAllNodes(isRoot())[0].captureRoboImage(snapshot)
    }

    private fun assertNativeSpeakDisplayed() {
        composeRule.onNodeWithTag("message_text_selection_speak").assertIsDisplayed()
    }

    private fun clickNativeSpeak() {
        composeRule.onNodeWithTag("message_text_selection_speak").performClick()
    }

    private fun longPressOnMessageText(substring: String) {
        val layoutResults = mutableListOf<TextLayoutResult>()
        composeRule
            .onNodeWithText(substring, substring = true, useUnmergedTree = true)
            .performSemanticsAction(SemanticsActions.GetTextLayoutResult) { action -> action(layoutResults) }
        val layout = layoutResults.single()
        val textBounds =
            composeRule
                .onNodeWithText(substring, substring = true, useUnmergedTree = true)
                .getUnclippedBoundsInRoot()
        val fullText = layout.layoutInput.text.text
        val targetOffset = fullText.indexOf(substring) + (substring.length / 2)
        val glyphBounds = layout.getBoundingBox(targetOffset.coerceIn(0, fullText.length - 1))
        val pressInRoot =
            Offset(
                x = textBounds.left.value + glyphBounds.left + (glyphBounds.width / 2f),
                y = textBounds.top.value + glyphBounds.top + (glyphBounds.height / 2f),
            )
        val hostBounds = composeRule.onNodeWithTag(MESSAGE_HOST_TAG).getUnclippedBoundsInRoot()
        val pressOnHost =
            Offset(
                x = pressInRoot.x - hostBounds.left.value,
                y = pressInRoot.y - hostBounds.top.value,
            )

        composeRule.onNodeWithTag(MESSAGE_HOST_TAG).performTouchInput {
            down(pressOnHost)
            advanceEventTime(viewConfiguration.longPressTimeoutMillis + 100)
            up()
        }
    }

    private fun waitForTts(engine: FakeSessionEngine) {
        composeRule.waitForIdle()
        composeRule.runOnIdle {
            var attempts = 0
            while (engine.spoken.isEmpty() && attempts < 50) {
                Thread.sleep(20)
                attempts++
            }
        }
        assertTrue(engine.spoken.isNotEmpty())
    }

    @Composable
    @Suppress("LongParameterList")
    private fun messageBubbleHost(
        item: TimelineMessage,
        controller: ConversationController,
        appState: WhiteNoiseAppState,
        textSelectionMode: Boolean,
        onTextSelectionModeChange: (Boolean) -> Unit,
        isActionMenuOpen: Boolean = false,
        onActionMenuOpenChange: (Boolean) -> Unit = {},
    ) {
        val composerTextState = ComposerTextState(TextFieldValue(""))
        MessageBubble(
            item = item,
            controller = controller,
            appState = appState,
            composerTextState = composerTextState,
            highlighted = false,
            selectionMode = false,
            textSelectionMode = textSelectionMode,
            onTextSelectionModeChange = onTextSelectionModeChange,
            onTextSelectionBoundsChange = {},
            batchSelectable = true,
            selected = false,
            onToggleSelection = {},
            rangeDragActive = false,
            onDragSelectionStart = {},
            onDragSelection = { false },
            onDragSelectionEnd = {},
            onDragSelectionCancel = {},
            quickReactionEmojis = emptyList(),
            recentEmojis = emptyList(),
            onEmojiUsed = {},
            isActionMenuOpen = isActionMenuOpen,
            onActionMenuOpenChange = onActionMenuOpenChange,
            onQuickReactionsSave = {},
            onQuickReactionsReset = {},
            onReplyPreviewClick = {},
            composerGate = ComposerGate.COMPOSER,
            inviteMutationInFlight = false,
            onJoinInvite = {},
            onDeclineInvite = {},
            mentionCandidates = emptyList(),
            mentionPickerEnabled = false,
        )
    }

    private fun appStateWithTts(engine: FakeSessionEngine = FakeSessionEngine()): WhiteNoiseAppState {
        val appState =
            WhiteNoiseAppState(
                context = context,
                draftStore = DraftStore(EmptyDraftPersistence()),
                accountIdHexResolver = { null },
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
        appState.forceUsableTtsResolutionForTests()
        appState.ttsController.attachEngine(engine)
        return appState
    }

    private fun WhiteNoiseAppState.forceUsableTtsResolutionForTests() {
        val delegateField = WhiteNoiseAppState::class.java.getDeclaredField("ttsResolution\$delegate")
        delegateField.isAccessible = true
        @Suppress("UNCHECKED_CAST")
        val delegate = delegateField.get(this) as androidx.compose.runtime.MutableState<TtsResolutionResult?>
        delegate.value =
            TtsResolutionResult(
                status = TextToSpeech.SUCCESS,
                engines = listOf(TtsEngineInfo("com.test.tts", "Test TTS", EngineTrust.Local)),
                defaultEnginePackage = "com.test.tts",
                handle = null,
            )
    }

    private fun conversationController(appState: WhiteNoiseAppState): ConversationController =
        ConversationController(appState = appState, initialGroup = group())

    private fun timelineMessage(body: String): TimelineMessage {
        val messageId = "05" + "00".repeat(31)
        return TimelineMessage(
            id = "msg:$messageId",
            record =
                AppMessageRecordFfi(
                    messageIdHex = messageId,
                    direction = "received",
                    groupIdHex = GROUP_ID,
                    sender = SENDER_ID,
                    plaintext = body,
                    contentTokens =
                        MarkdownDocumentFfi(
                            truncated = false,
                            blocks = emptyList(),
                            blankLinesBefore = byteArrayOf(),
                        ),
                    kind = 9uL,
                    tags = emptyList(),
                    sourceEpoch = null,
                    retentionSeconds = null,
                    retentionExpiresAt = null,
                    recordedAt = 1uL,
                    receivedAt = 1uL,
                ),
            status = MessageStatus.Received,
        )
    }

    private fun group() =
        AppGroupRecordFfi(
            groupIdHex = GROUP_ID,
            protocolProfile = AppProtocolProfileFfi.LEGACY,
            endpoint = "wss://relay.example",
            profilePresent = true,
            name = "Speak group",
            description = "",
            admins = listOf(ACCOUNT_ID),
            relays = emptyList(),
            nostrGroupIdHex = "03".repeat(32),
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

    private class EmptyDraftPersistence : DraftPersistence {
        override fun read(): Map<String, String> = emptyMap()

        override fun write(
            key: String,
            value: String?,
        ) = Unit
    }

    private companion object {
        const val ACCOUNT_REF = "personal"
        val ACCOUNT_ID = "01" + "00".repeat(31)
        val SENDER_ID = "02" + "00".repeat(31)
        val GROUP_ID = "04" + "00".repeat(31)
        const val MESSAGE_HOST_TAG = "message-text-selection-host"
    }
}
