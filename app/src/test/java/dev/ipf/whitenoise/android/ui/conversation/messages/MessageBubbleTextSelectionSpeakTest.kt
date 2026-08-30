package dev.ipf.whitenoise.android.ui.conversation.messages

import android.app.Application
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.pm.ResolveInfo
import android.speech.tts.TextToSpeech
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.contextmenu.data.TextContextMenuItem
import androidx.compose.foundation.text.contextmenu.provider.LocalTextContextMenuToolbarProvider
import androidx.compose.foundation.text.contextmenu.provider.TextContextMenuDataProvider
import androidx.compose.foundation.text.contextmenu.provider.TextContextMenuProvider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.input.TextFieldValue
import androidx.test.core.app.ApplicationProvider
import dev.ipf.marmotkit.AccountSummaryFfi
import dev.ipf.marmotkit.AppBlobEndpointFfi
import dev.ipf.marmotkit.AppGroupEncryptedMediaComponentFfi
import dev.ipf.marmotkit.AppGroupRecordFfi
import dev.ipf.marmotkit.AppMessageRecordFfi
import dev.ipf.marmotkit.AppProtocolProfileFfi
import dev.ipf.marmotkit.EncryptedMediaVersionFfi
import dev.ipf.marmotkit.MarkdownAutolinkKindFfi
import dev.ipf.marmotkit.MarkdownBlockFfi
import dev.ipf.marmotkit.MarkdownDocumentFfi
import dev.ipf.marmotkit.MarkdownInlineFfi
import dev.ipf.marmotkit.MarkdownLinkDestinationKindFfi
import dev.ipf.marmotkit.SelfMembershipFfi
import dev.ipf.whitenoise.android.R
import dev.ipf.whitenoise.android.audio.tts.EngineTrust
import dev.ipf.whitenoise.android.audio.tts.FakeSessionEngine
import dev.ipf.whitenoise.android.audio.tts.TtsEngineInfo
import dev.ipf.whitenoise.android.audio.tts.TtsResolutionResult
import dev.ipf.whitenoise.android.audio.tts.TtsSpeakableEntry
import dev.ipf.whitenoise.android.audio.tts.projectTtsSpeakableEntry
import dev.ipf.whitenoise.android.state.ConversationController
import dev.ipf.whitenoise.android.state.DraftPersistence
import dev.ipf.whitenoise.android.state.DraftStore
import dev.ipf.whitenoise.android.state.MessageStatus
import dev.ipf.whitenoise.android.state.TimelineMessage
import dev.ipf.whitenoise.android.state.WhiteNoiseAppState
import dev.ipf.whitenoise.android.ui.conversation.composer.ComposerGate
import dev.ipf.whitenoise.android.ui.conversation.composer.ComposerTextState
import dev.ipf.whitenoise.android.ui.conversation.timelineRowTtsHighlightPassage
import dev.ipf.whitenoise.android.ui.conversation.timelineRowTtsReadAloudProgress
import dev.ipf.whitenoise.android.ui.theme.WhiteNoiseTheme
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.util.Locale

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "w360dp-h240dp-mdpi")
@Suppress("LargeClass") // Message-body gestures share one production bubble and native Compose fixture.
class MessageBubbleTextSelectionSpeakTest {
    @get:Rule
    val composeRule = createComposeRule()

    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()
    private val app = ApplicationProvider.getApplicationContext<Application>()

    @Test
    fun longPressActionMenuSpeakQueuesThroughAppStateAtMessageTop() {
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
                .endsWith("First sentence."),
        )
    }

    @Test
    fun doubleTapMessageTextSeeksToThePressedSentence() {
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

        doubleTapOnMessageText("First sentence")
        waitForTts(engine)
        val originalSessionId = appState.ttsController.state.value.sessionId
        val submissionsBeforeSeek = engine.spoken.size
        assertTrue(appState.ownsTtsAutoReadSession(GROUP_ID))

        doubleTapOnMessageText("Second sentence")
        composeRule.waitForIdle()

        assertFalse(actionMenuOpen)
        assertEquals(originalSessionId, appState.ttsController.state.value.sessionId)
        assertEquals(1, appState.ttsController.state.value.sentenceIndexWithinMessage)
        assertEquals(submissionsBeforeSeek + 1, engine.spoken.size)
        assertEquals("Second sentence.", engine.spoken.last().text)
    }

    @Test
    fun doubleTapSeeksInsideActiveTruncatedMessageProjection() {
        val engine = FakeSessionEngine()
        val appState = appStateWithTts(engine)
        val item = timelineMessage("First sentence. Second sentence.", contentTokensTruncated = true)
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
        longPressOnMessageText("First sentence")
        composeRule.onNodeWithText(app.getString(R.string.speak_aloud)).performClick()
        waitForTts(engine)
        val originalSessionId = appState.ttsController.state.value.sessionId

        doubleTapOnMessageText("Second sentence")
        composeRule.waitForIdle()

        assertEquals(originalSessionId, appState.ttsController.state.value.sessionId)
        assertEquals(1, appState.ttsController.state.value.sentenceIndexWithinMessage)
        assertEquals("Second sentence.", engine.spoken.last().text)
    }

    @Test
    fun singleLinkTapDuringReadAloudOpensOnlyAfterDoubleTapWindow() {
        val engine = FakeSessionEngine()
        val appState = appStateWithTts(engine)
        val item = timelineMessageWithAutolink()
        val controller = conversationController(appState)
        composeRule.setContent {
            WhiteNoiseTheme {
                Box(Modifier.fillMaxWidth().testTag(MESSAGE_HOST_TAG)) {
                    messageBubbleHost(item, controller, appState, false, {})
                }
            }
        }
        assertTrue(
            appState.speakAloudAutoRead(
                GROUP_ID,
                listOf(projectedTtsEntry(item)),
                Locale.getDefault(),
            ),
        )
        waitForTts(engine)
        assertTrue(appState.ownsTtsAutoReadSession(GROUP_ID))
        composeRule.mainClock.autoAdvance = false

        singleTapOnMessageText("https://example.com")
        assertEquals(null, shadowOf(app).nextStartedActivity)
        composeRule.mainClock.advanceTimeBy(
            android.view.ViewConfiguration
                .getDoubleTapTimeout()
                .toLong() + 1L,
        )
        composeRule.waitForIdle()

        val opened = shadowOf(app).nextStartedActivity
        assertEquals(Intent.ACTION_VIEW, opened?.action)
        assertEquals("https://example.com", opened?.dataString)
    }

    @Test
    fun doubleTapOnLinkDuringReadAloudSeeksAndDoesNotOpenLink() {
        val engine = FakeSessionEngine()
        val appState = appStateWithTts(engine)
        val item = timelineMessageWithAutolink()
        val controller = conversationController(appState)
        composeRule.setContent {
            WhiteNoiseTheme {
                Box(Modifier.fillMaxWidth().testTag(MESSAGE_HOST_TAG)) {
                    messageBubbleHost(item, controller, appState, false, {})
                }
            }
        }
        assertTrue(
            appState.speakAloudAutoRead(
                GROUP_ID,
                listOf(projectedTtsEntry(item)),
                Locale.getDefault(),
            ),
        )
        waitForTts(engine)
        composeRule.mainClock.autoAdvance = false
        val sessionId = appState.ttsController.state.value.sessionId
        val submissionsBeforeSeek = engine.spoken.size

        doubleTapOnMessageText("https://example.com")
        composeRule.mainClock.advanceTimeBy(
            android.view.ViewConfiguration
                .getDoubleTapTimeout()
                .toLong() + 1L,
        )
        composeRule.waitForIdle()

        assertEquals(null, shadowOf(app).nextStartedActivity)
        assertEquals(sessionId, appState.ttsController.state.value.sessionId)
        assertEquals(1, appState.ttsController.state.value.sentenceIndexWithinMessage)
        assertEquals("Read now.", engine.spoken[submissionsBeforeSeek].text)
    }

    @Test
    fun doubleTapLinkStillOpensWhenItsMessageIsOutsideTheActiveQueue() {
        val engine = FakeSessionEngine()
        val appState = appStateWithTts(engine)
        val item = timelineMessageWithAutolink()
        val controller = conversationController(appState)
        composeRule.setContent {
            WhiteNoiseTheme {
                Box(Modifier.fillMaxWidth().testTag(MESSAGE_HOST_TAG)) {
                    messageBubbleHost(item, controller, appState, false, {})
                }
            }
        }
        assertTrue(
            appState.speakAloudAutoRead(
                GROUP_ID,
                listOf(projectedTtsEntry(item).copy(messageIdHex = "outside-window")),
                Locale.getDefault(),
            ),
        )
        waitForTts(engine)
        val sessionId = appState.ttsController.state.value.sessionId
        composeRule.mainClock.autoAdvance = false

        doubleTapOnMessageText("https://example.com")
        composeRule.mainClock.advanceTimeBy(
            android.view.ViewConfiguration
                .getDoubleTapTimeout()
                .toLong() + 1L,
        )
        composeRule.waitForIdle()

        assertEquals(Intent.ACTION_VIEW, shadowOf(app).nextStartedActivity?.action)
        assertEquals(sessionId, appState.ttsController.state.value.sessionId)
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
    fun nativeSelectionMenuSpeakUsesSameSpeakPath() {
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

        clickNativeSpeak()
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
    fun nativeSelectionMenuReplacesSystemReadAloudAndKeepsOtherProcessTextActions() {
        registerProcessTextActivity(
            packageName = "com.google.android.marvin.talkback",
            className = "com.google.android.accessibility.selecttospeak.popup.SelectToSpeakPopupActivity",
            label = "Read aloud",
        )
        registerProcessTextActivity(
            packageName = "com.example.reader",
            className = "com.example.reader.ProcessTextActivity",
            label = "Read aloud",
        )
        registerProcessTextActivity(
            packageName = "com.example.translate",
            className = "com.example.translate.ProcessTextActivity",
            label = "Translate",
        )
        val appState = appStateWithTts()
        val item = timelineMessage("Selected sentence. Later sentence.")
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

        longPressOnMessageText("Selected sentence")
        composeRule.waitForIdle()
        composeRule.onNodeWithText(app.getString(R.string.select_text)).performClick()
        composeRule.waitForIdle()

        composeRule.runOnIdle {
            val labels =
                checkNotNull(nativeTextMenuProvider.dataProvider)
                    .data()
                    .components
                    .filterIsInstance<TextContextMenuItem>()
                    .map { it.label }
            assertTrue(labels.count { it == "Read aloud" } == 1)
            assertTrue(labels.contains("Translate"))
            assertTrue(labels.count { it == app.getString(R.string.speak_aloud) } == 1)
        }
    }

    private fun assertNativeSpeakDisplayed() {
        composeRule.waitUntil(timeoutMillis = 10_000) {
            nativeTextMenuProvider.dataProvider != null
        }
        composeRule.runOnIdle {
            assertTrue(
                checkNotNull(nativeTextMenuProvider.dataProvider)
                    .data()
                    .components
                    .filterIsInstance<TextContextMenuItem>()
                    .any { it.label == app.getString(R.string.speak_aloud) },
            )
        }
    }

    private fun clickNativeSpeak() {
        composeRule.runOnIdle {
            val speakItem =
                checkNotNull(nativeTextMenuProvider.dataProvider)
                    .data()
                    .components
                    .filterIsInstance<TextContextMenuItem>()
                    .single { it.label == app.getString(R.string.speak_aloud) }
            speakItem.onClick(nativeTextMenuSession)
        }
    }

    private val nativeTextMenuSession =
        object : androidx.compose.foundation.text.contextmenu.data.TextContextMenuSession {
            override fun close() = Unit
        }

    private val nativeTextMenuProvider = CapturingTextContextMenuProvider()

    private fun registerProcessTextActivity(
        packageName: String,
        className: String,
        label: String,
    ) {
        val resolveInfo =
            ResolveInfo().apply {
                nonLocalizedLabel = label
                activityInfo =
                    ActivityInfo().apply {
                        this.packageName = packageName
                        name = className
                        exported = true
                    }
            }
        shadowOf(context.packageManager).addResolveInfoForIntent(
            Intent(Intent.ACTION_PROCESS_TEXT).setType("text/plain"),
            resolveInfo,
        )
    }

    private fun longPressOnMessageText(substring: String) {
        val pressOnHost = messageTextPositionOnHost(substring)
        composeRule.onNodeWithTag(MESSAGE_HOST_TAG).performTouchInput {
            down(pressOnHost)
            advanceEventTime(viewConfiguration.longPressTimeoutMillis + 100)
            up()
        }
    }

    private fun doubleTapOnMessageText(substring: String) {
        val pressOnHost = messageTextPositionOnHost(substring)
        composeRule.onNodeWithTag(MESSAGE_HOST_TAG).performTouchInput {
            down(pressOnHost)
            up()
            advanceEventTime(
                (viewConfiguration.doubleTapMinTimeMillis + viewConfiguration.doubleTapTimeoutMillis) / 2,
            )
            down(pressOnHost)
            up()
        }
    }

    private fun singleTapOnMessageText(substring: String) {
        val pressOnHost = messageTextPositionOnHost(substring)
        composeRule.onNodeWithTag(MESSAGE_HOST_TAG).performTouchInput {
            down(pressOnHost)
            up()
        }
    }

    private fun messageTextPositionOnHost(substring: String): Offset {
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

        return pressOnHost
    }

    private fun waitForTts(engine: FakeSessionEngine) {
        composeRule.waitForIdle()
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
        val ttsState by appState.ttsController.state.collectAsState()
        CompositionLocalProvider(LocalTextContextMenuToolbarProvider provides nativeTextMenuProvider) {
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
                ttsHighlightPassage = timelineRowTtsHighlightPassage(item.record.messageIdHex, ttsState),
                ttsReadAloudProgress = timelineRowTtsReadAloudProgress(item.record.messageIdHex, ttsState),
            )
        }
    }

    private class CapturingTextContextMenuProvider : TextContextMenuProvider {
        var dataProvider: TextContextMenuDataProvider? = null

        override suspend fun showTextContextMenu(dataProvider: TextContextMenuDataProvider): Nothing {
            this.dataProvider = dataProvider
            awaitCancellation()
        }
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

    private fun timelineMessage(
        body: String,
        contentTokensTruncated: Boolean = false,
    ): TimelineMessage {
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
                            truncated = contentTokensTruncated,
                            blocks =
                                listOf(
                                    MarkdownBlockFfi.Paragraph(
                                        inlines = listOf(MarkdownInlineFfi.Text(body)),
                                    ),
                                ),
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

    private fun timelineMessageWithAutolink(): TimelineMessage {
        val body = "First sentence. Read https://example.com now. Third sentence."
        val item = timelineMessage(body)
        return item.copy(
            record =
                item.record.copy(
                    contentTokens =
                        MarkdownDocumentFfi(
                            truncated = false,
                            blocks =
                                listOf(
                                    MarkdownBlockFfi.Paragraph(
                                        inlines =
                                            listOf(
                                                MarkdownInlineFfi.Text("First sentence. Read "),
                                                MarkdownInlineFfi.Autolink(
                                                    url = "https://example.com",
                                                    kind = MarkdownAutolinkKindFfi.URI,
                                                    classification = MarkdownLinkDestinationKindFfi.WEB,
                                                ),
                                                MarkdownInlineFfi.Text(" now. Third sentence."),
                                            ),
                                    ),
                                ),
                            blankLinesBefore = byteArrayOf(),
                        ),
                ),
        )
    }

    private fun projectedTtsEntry(item: TimelineMessage): TtsSpeakableEntry =
        runBlocking {
            projectTtsSpeakableEntry(
                message = item.record,
                editedText = null,
                senderDisplayName = "Sender",
                parseMarkdown = { error("stored content tokens must be used") },
            )!!
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
