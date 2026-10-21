package dev.ipf.whitenoise.android.ui.conversation.messages

import android.app.Application
import android.content.ClipboardManager
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.pm.ResolveInfo
import android.provider.Settings
import android.speech.tts.TextToSpeech
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
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
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import com.github.takahirom.roborazzi.captureRoboImage
import dev.ipf.marmotkit.AccountSummaryFfi
import dev.ipf.marmotkit.AppBlobEndpointFfi
import dev.ipf.marmotkit.AppGroupEncryptedMediaComponentFfi
import dev.ipf.marmotkit.AppGroupRecordFfi
import dev.ipf.marmotkit.AppMessageRecordFfi
import dev.ipf.marmotkit.AppProtocolProfileFfi
import dev.ipf.marmotkit.EncryptedMediaVersionFfi
import dev.ipf.marmotkit.MarkdownAutolinkKindFfi
import dev.ipf.marmotkit.MarkdownBlockFfi
import dev.ipf.marmotkit.MarkdownCodeBlockKindFfi
import dev.ipf.marmotkit.MarkdownDocumentFfi
import dev.ipf.marmotkit.MarkdownInlineFfi
import dev.ipf.marmotkit.MarkdownLinkDestinationKindFfi
import dev.ipf.marmotkit.TimelinePageFfi
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
import dev.ipf.whitenoise.android.state.ScriptedConversationLiveSubscriptions
import dev.ipf.whitenoise.android.state.ScriptedConversationTimelineSubscription
import dev.ipf.whitenoise.android.state.SwipeAction
import dev.ipf.whitenoise.android.state.SwipeBinding
import dev.ipf.whitenoise.android.state.TimelineMessage
import dev.ipf.whitenoise.android.state.WhiteNoiseAppState
import dev.ipf.whitenoise.android.ui.conversation.composer.ComposerGate
import dev.ipf.whitenoise.android.ui.conversation.composer.ComposerTextState
import dev.ipf.whitenoise.android.ui.conversation.timelineRowTtsHighlightPassage
import dev.ipf.whitenoise.android.ui.conversation.timelineRowTtsReadAloudProgress
import dev.ipf.whitenoise.android.ui.theme.WhiteNoiseTheme
import java.util.Locale
import java.util.TimeZone
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

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "w360dp-h240dp-mdpi")
@Suppress("LargeClass") // Message-body gestures share one production bubble and native Compose fixture.
class MessageBubbleTextSelectionSpeakTest {
    @get:Rule
    val composeRule = createComposeRule()

    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()
    private val app = ApplicationProvider.getApplicationContext<Application>()

    /** The action menu uses the actual frozen text hit through app state and speech preparation. */
    @Test
    fun longPressActionMenuSpeakQueuesThePressedSentence() {
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

        composeRule
            .onNodeWithText(app.getString(R.string.read_aloud))
            .performScrollTo()
            .assertIsDisplayed()
            .performClick()
        waitForTts(engine, appState)

        assertTrue(
            engine.spoken
                .first()
                .text
                .endsWith("Second sentence."),
        )
    }

    @Test
    fun longPressGutterStillStartsAtMessageTop() {
        val engine = FakeSessionEngine()
        val appState = appStateWithTts(engine)
        val item =
            timelineMessage(
                "First sentence. Second sentence is deliberately long enough to wrap onto another line, " +
                    "leaving a short end.",
            )
        val controller = conversationController(appState)
        var menuOpen by mutableStateOf(false)
        composeRule.setContent {
            WhiteNoiseTheme {
                Box(Modifier.fillMaxWidth().testTag(MESSAGE_HOST_TAG)) {
                    messageBubbleHost(item, controller, appState, false, {}, menuOpen, { menuOpen = it })
                }
            }
        }
        val layouts = mutableListOf<TextLayoutResult>()
        val textNode = composeRule.onNodeWithText("First sentence", substring = true, useUnmergedTree = true)
        textNode.performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
        val layout = layouts.single()
        val line = layout.lineCount - 1
        val lineRight = layout.getLineRight(line)
        assertTrue(layout.size.width - lineRight > 4f)
        val textBounds = textNode.getUnclippedBoundsInRoot()
        val hostBounds = composeRule.onNodeWithTag(MESSAGE_HOST_TAG).getUnclippedBoundsInRoot()
        val gutter =
            Offset(
                textBounds.left.value - hostBounds.left.value + (lineRight + layout.size.width) / 2f,
                textBounds.top.value - hostBounds.top.value +
                    (layout.getLineTop(line) + layout.getLineBottom(line)) / 2f,
            )
        composeRule.onNodeWithTag(MESSAGE_HOST_TAG).performTouchInput {
            down(gutter)
            advanceEventTime(viewConfiguration.longPressTimeoutMillis + 100)
            up()
        }
        composeRule.waitForIdle()
        assertTrue(menuOpen)
        composeRule.onNodeWithText(app.getString(R.string.read_aloud)).performScrollTo().performClick()
        waitForTts(engine, appState)
        assertTrue(
            engine.spoken
                .first()
                .text
                .endsWith("First sentence."),
        )
    }

    @Test
    fun longPressReadAloudReplacesAnAdvancingSessionAtItsPressedSentence() {
        val engine = FakeSessionEngine()
        val appState = appStateWithTts(engine)
        val item = timelineMessage("First sentence. Second sentence.")
        val controller = conversationController(appState)
        var menuOpen by mutableStateOf(false)
        composeRule.setContent {
            WhiteNoiseTheme {
                Box(Modifier.fillMaxWidth().testTag(MESSAGE_HOST_TAG)) {
                    messageBubbleHost(item, controller, appState, false, {}, menuOpen, { menuOpen = it })
                }
            }
        }
        assertTrue(
            appState.speakAloudAutoRead(
                GROUP_ID,
                listOf(projectedTtsEntry(item).copy(messageIdHex = "previous-session")),
                Locale.getDefault(),
            ),
        )
        waitForTts(engine, appState)
        longPressOnMessageText("Second sentence")
        composeRule.waitForIdle()
        val generation = appState.ttsController.playbackCallbackGeneration()
        engine.complete(0)
        assertEquals(generation, appState.ttsController.playbackCallbackGeneration())
        val submissions = engine.spoken.size
        composeRule.onNodeWithText(app.getString(R.string.read_aloud)).performScrollTo().performClick()
        composeRule.waitUntil(timeoutMillis = 10_000) {
            shadowOf(android.os.Looper.getMainLooper()).idle()
            engine.spoken.size > submissions
        }
        assertTrue(engine.spoken[submissions].text.endsWith("Second sentence."))
    }

    @Test
    fun longPressKeepsOriginalSentenceAfterBubbleMovesWithItsMenuOpen() {
        val engine = FakeSessionEngine()
        val appState = appStateWithTts(engine)
        val item = timelineMessage("First sentence. Second sentence.")
        val controller = conversationController(appState)
        var menuOpen by mutableStateOf(false)
        var offset by mutableStateOf(0.dp)
        composeRule.setContent {
            WhiteNoiseTheme {
                Box(Modifier.fillMaxWidth().offset(y = offset).testTag(MESSAGE_HOST_TAG)) {
                    messageBubbleHost(item, controller, appState, false, {}, menuOpen, { menuOpen = it })
                }
            }
        }
        longPressOnMessageText("Second sentence")
        composeRule.waitForIdle()
        assertTrue(menuOpen)
        composeRule.runOnIdle { offset = 32.dp }
        composeRule.onNodeWithText(app.getString(R.string.read_aloud)).performScrollTo().performClick()
        waitForTts(engine, appState)
        assertTrue(
            engine.spoken
                .first()
                .text
                .endsWith("Second sentence."),
        )
    }

    @Test
    fun preparedAutoReadStartsFromTheAppStateIntegration() =
        runBlocking {
            val engine = FakeSessionEngine()
            val appState = appStateWithTts(engine)
            val item = timelineMessage("First sentence. Second sentence.")
            val started =
                appState.speakAloudAutoRead(
                    GROUP_ID,
                    listOf(projectedTtsEntry(item)),
                    Locale.US,
                    backgroundPreparation = true,
                )

            assertTrue(
                "state=${appState.ttsController.state.value}; failure=${appState.ttsController.lastStartFailure}",
                started,
            )
            assertTrue(engine.spoken.isNotEmpty())
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
        waitForTts(engine, appState)
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

    /** Double tap seeks inside active truncated message projection. */
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
        composeRule
            .onNodeWithText(app.getString(R.string.read_aloud))
            .performScrollTo()
            .assertIsDisplayed()
            .performClick()
        waitForTts(engine, appState)
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
        waitForTts(engine, appState)
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
        waitForTts(engine, appState)
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
    fun doubleTapLinkOutsideActiveQueueSeeksItsSpokenNeighborWithoutOpening() {
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
        waitForTts(engine, appState)
        val sessionId = appState.ttsController.state.value.sessionId
        val submissionsBeforeSeek = engine.spoken.size
        composeRule.mainClock.autoAdvance = false

        doubleTapOnMessageText("https://example.com")
        composeRule.mainClock.advanceTimeBy(
            android.view.ViewConfiguration
                .getDoubleTapTimeout()
                .toLong() + 1L,
        )
        composeRule.waitForIdle()

        composeRule.waitUntil(timeoutMillis = 10_000) {
            shadowOf(android.os.Looper.getMainLooper()).idle()
            engine.spoken.size > submissionsBeforeSeek
        }
        assertEquals(null, shadowOf(app).nextStartedActivity)
        assertEquals(sessionId, appState.ttsController.state.value.sessionId)
        assertEquals(1, appState.ttsController.state.value.sentenceIndexWithinMessage)
        assertTrue(engine.spoken[submissionsBeforeSeek].text.endsWith("Read now."))
    }

    @Test
    fun doubleTapOtherUnqueuedBubbleSeeksWithoutReplacingPlaybackSession() {
        val engine = FakeSessionEngine()
        val appState = appStateWithTts(engine)
        val item = timelineMessage("First sentence. Second sentence.")
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
        waitForTts(engine, appState)
        val session = appState.ttsController.state.value.sessionId
        val submissions = engine.spoken.size
        doubleTapOnMessageText("Second sentence")
        composeRule.waitUntil(timeoutMillis = 10_000) {
            shadowOf(android.os.Looper.getMainLooper()).idle()
            engine.spoken.size > submissions
        }
        assertEquals(session, appState.ttsController.state.value.sessionId)
        assertEquals(
            item.record.messageIdHex,
            appState.ttsController.state.value.passage
                ?.messageIdHex,
        )
        assertEquals(1, appState.ttsController.state.value.sentenceIndexWithinMessage)
        assertTrue(engine.spoken[submissions].text.endsWith("Second sentence."))
    }

    @Test
    fun unmappableRenderedTargetDoesNotPauseOrChangeCurrentSpeech() {
        val engine = FakeSessionEngine()
        val appState = appStateWithTts(engine)
        val item = timelineMessage("First sentence. Second sentence.")
        val entry = projectedTtsEntry(item)
        assertTrue(appState.speakAloudAutoRead(GROUP_ID, listOf(entry), Locale.getDefault()))
        waitForTts(engine, appState)
        val state = appState.ttsController.state.value
        val spoken = engine.spoken.size
        var resolved = false
        assertTrue(
            appState.ttsHistorySession.requestRenderedSentenceSeek(
                item.record.messageIdHex,
                item.record.recordedAt,
                expectedSource = appState.ttsHistorySession.conversationSource.value!!,
                resolveTarget = {
                    dev.ipf.whitenoise.android.audio.tts.TtsRenderedSeekRequest(
                        entry,
                        sentenceIndex = { null },
                        canCommit = {
                            resolved = true
                            true
                        },
                    )
                },
                onCommitted = { error("An unmappable hit must not commit") },
            ),
        )
        composeRule.waitUntil(timeoutMillis = 10_000) {
            shadowOf(android.os.Looper.getMainLooper()).idle()
            resolved && appState.ttsHistorySession.edgeState.value == null
        }
        assertEquals(state, appState.ttsController.state.value)
        assertEquals(spoken, engine.spoken.size)
        appState.stopSpeaking()
    }

    @Test
    fun singleTapLinkInUnqueuedBubbleStillOpensDuringPlayback() {
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
        waitForTts(engine, appState)
        composeRule.mainClock.autoAdvance = false
        singleTapOnMessageText("https://example.com")
        composeRule.mainClock.advanceTimeBy(
            android.view.ViewConfiguration
                .getDoubleTapTimeout()
                .toLong() + 1L,
        )
        composeRule.waitForIdle()
        assertEquals(Intent.ACTION_VIEW, shadowOf(app).nextStartedActivity?.action)
    }

    @Test
    fun doubleTapNonCurrentQueuedBubbleDoesNotRequireAnActiveHighlightProjection() {
        val engine = FakeSessionEngine()
        val appState = appStateWithTts(engine)
        val item = timelineMessage("First sentence. Second sentence.")
        val controller = conversationController(appState)
        composeRule.setContent {
            WhiteNoiseTheme {
                Box(Modifier.fillMaxWidth().testTag(MESSAGE_HOST_TAG)) {
                    messageBubbleHost(item, controller, appState, false, {})
                }
            }
        }
        val target = projectedTtsEntry(item)
        assertTrue(
            appState.speakAloudAutoRead(
                GROUP_ID,
                listOf(target.copy(messageIdHex = "currently-speaking"), target),
                Locale.getDefault(),
            ),
        )
        waitForTts(engine, appState)
        val session = appState.ttsController.state.value.sessionId
        val submissions = engine.spoken.size
        doubleTapOnMessageText("Second sentence")
        composeRule.waitUntil(timeoutMillis = 10_000) {
            shadowOf(android.os.Looper.getMainLooper()).idle()
            engine.spoken.size > submissions
        }
        assertEquals(session, appState.ttsController.state.value.sessionId)
        assertEquals(
            item.record.messageIdHex,
            appState.ttsController.state.value.passage
                ?.messageIdHex,
        )
        assertTrue(engine.spoken[submissions].text.endsWith("Second sentence."))
    }

    /** Selecting a scrolled filename keeps the source viewport and copies the visible word through the native menu. */
    @Test
    @Suppress("LongMethod")
    @Config(qualifiers = "en-w360dp-h240dp-mdpi")
    fun codePathNativeSelectionKeepsScrolledFilename() {
        withCodePathClock {
            val appState = appStateWithTts(FakeSessionEngine())
            val path = "directory/".repeat(12) + "ENDTARGET"
            val original = timelineMessage("```\n$path\n```")
            val item =
                original.copy(
                    record =
                        original.record.copy(
                            contentTokens =
                                MarkdownDocumentFfi(
                                    listOf(MarkdownBlockFfi.CodeBlock(MarkdownCodeBlockKindFfi.FENCED, "", path)),
                                    false,
                                    byteArrayOf(),
                                ),
                        ),
                )
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
            val node = composeRule.onNodeWithText(path, useUnmergedTree = true)
            val range = node.fetchSemanticsNode().config[SemanticsProperties.HorizontalScrollAxisRange]
            node.performSemanticsAction(SemanticsActions.ScrollBy) { action ->
                assertTrue(action(range.maxValue(), 0f))
            }
            composeRule.waitForIdle()
            composeRule
                .onNodeWithTag(MESSAGE_HOST_TAG)
                .captureRoboImage("src/test/snapshots/message_bubble_code_path_end.png")
            val host = composeRule.onNodeWithTag(MESSAGE_HOST_TAG)
            val hostBounds = host.fetchSemanticsNode().boundsInRoot
            val textBounds = node.fetchSemanticsNode().boundsInRoot
            host.performTouchInput {
                down(Offset(textBounds.right - hostBounds.left - 20f, textBounds.center.y - hostBounds.top))
                advanceEventTime(viewConfiguration.longPressTimeoutMillis + 100)
                up()
            }
            composeRule.waitForIdle()
            assertTrue(actionMenuOpen)
            composeRule.onNodeWithText(app.getString(R.string.select_text)).performScrollTo().performClick()
            composeRule.waitForIdle()
            assertTrue(textSelectionMode)
            val selectedRange = node.fetchSemanticsNode().config[SemanticsProperties.HorizontalScrollAxisRange]
            assertEquals(selectedRange.maxValue(), selectedRange.value(), 1f)
            composeRule.waitUntil(timeoutMillis = 5_000) { nativeTextMenuProvider.dataProvider != null }
            composeRule
                .onNodeWithTag(MESSAGE_HOST_TAG)
                .captureRoboImage("src/test/snapshots/message_bubble_code_path_selection.png")
            composeRule.runOnIdle {
                val copy =
                    checkNotNull(nativeTextMenuProvider.dataProvider)
                        .data()
                        .components
                        .filterIsInstance<TextContextMenuItem>()
                        .single { it.label == app.getString(android.R.string.copy) }
                copy.onClick(nativeTextMenuSession)
            }
            val clipboard = app.getSystemService(ClipboardManager::class.java)
            composeRule.waitUntil(timeoutMillis = 5_000) {
                shadowOf(android.os.Looper.getMainLooper()).idle()
                clipboard.primaryClip
                    ?.getItemAt(0)
                    ?.coerceToText(app)
                    ?.toString() == "ENDTARGET"
            }
        }
    }

    /** A fitting code leaf must leave horizontal movement available to the bubble's real reply action. */
    @Test
    fun swipeOnFittingCodeBlockBeginsReply() {
        withCodePathClock {
            val controller = renderSwipeBlock(MarkdownBlockFfi.CodeBlock(MarkdownCodeBlockKindFfi.FENCED, "", "ls -la"))
            swipeSource("ls -la", right = true, captureFile = "message_bubble_fitting_code_reply_swipe.png")
            assertEquals(SWIPE_TEST_MESSAGE_ID, controller.replyingTo?.messageIdHex)
        }
    }

    /** Math shares the code viewport and must not absorb a reply gesture when all source fits. */
    @Test
    fun swipeOnFittingMathBlockBeginsReply() {
        withCodePathClock {
            val controller = renderSwipeBlock(MarkdownBlockFfi.MathBlock("a + b"))
            swipeSource("a + b", right = true, captureFile = "message_bubble_fitting_math_reply_swipe.png")
            assertEquals(SWIPE_TEST_MESSAGE_ID, controller.replyingTo?.messageIdHex)
        }
    }

    /** Overflow keeps both horizontal directions, even at an edge, without falling through to reply. */
    @Test
    fun overflowingCodeOwnsHorizontalDragsAtBothEdges() {
        val path = "directory/".repeat(20) + "ENDTARGET"
        val controller = renderSwipeBlock(MarkdownBlockFfi.CodeBlock(MarkdownCodeBlockKindFfi.FENCED, "", path))
        val node = composeRule.onNodeWithText(path, useUnmergedTree = true)
        val range = node.fetchSemanticsNode().config[SemanticsProperties.HorizontalScrollAxisRange]
        assertTrue(range.maxValue() > 0f)
        swipeSource(path, right = true)
        assertEquals(null, controller.replyingTo)
        assertEquals(0f, range.value(), 1f)
        swipeSource(path, right = false)
        assertTrue(range.value() > 0f)
        assertEquals(null, controller.replyingTo)
        node.performSemanticsAction(SemanticsActions.ScrollBy) { action -> assertTrue(action(range.maxValue(), 0f)) }
        composeRule.waitForIdle()
        swipeSource(path, right = false)
        assertEquals(range.maxValue(), range.value(), 1f)
        assertEquals(null, controller.replyingTo)
        swipeSource(path, right = true)
        assertTrue(range.value() < range.maxValue())
        assertEquals(null, controller.replyingTo)
    }

    /** Uses the real bubble host with both physical swipe bindings set to Reply to detect accidental fallthrough. */
    private fun renderSwipeBlock(block: MarkdownBlockFfi): ConversationController {
        val appState = appStateWithTts(FakeSessionEngine())
        appState.swipePreferences.set(SwipeBinding.MessageLeft, SwipeAction.Reply)
        appState.swipePreferences.set(SwipeBinding.MessageRight, SwipeAction.Reply)
        val source =
            when (block) {
                is MarkdownBlockFfi.CodeBlock -> "```\n${block.content}\n```"
                is MarkdownBlockFfi.MathBlock -> "\$\$\n${block.content}\n\$\$"
                else -> error("The swipe fixture requires code or math source")
            }
        val record =
            swipeTestProjectedRecord(reacted = false, mine = false, media = false).copy(
                plaintext = source,
                contentTokens = MarkdownDocumentFfi(listOf(block), false, byteArrayOf()),
            )
        val controller = conversationController(appState)
        runBlocking {
            controller.testRefreshCurrentTimeline(ACCOUNT_REF) {
                TimelinePageFfi(listOf(record), hasMoreBefore = false, hasMoreAfter = false)
            }
        }
        val item = controller.timeline.single()
        composeRule.setContent {
            WhiteNoiseTheme {
                Box(Modifier.fillMaxWidth().testTag(MESSAGE_HOST_TAG)) {
                    messageBubbleHost(item, controller, appState, false, {})
                }
            }
        }
        composeRule.waitForIdle()
        return controller
    }

    /** Starts on the source's clipped visible text, rather than the outer bubble gutter. */
    private fun swipeSource(
        text: String,
        right: Boolean,
        captureFile: String? = null,
    ) {
        val source = composeRule.onNodeWithText(text, useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
        val host = composeRule.onNodeWithTag(MESSAGE_HOST_TAG)
        val bounds = host.fetchSemanticsNode().boundsInRoot
        host.performTouchInput {
            val startX = if (right) source.left + 8f else source.right - 8f
            down(Offset(startX - bounds.left, source.center.y - bounds.top))
            repeat(4) { moveBy(Offset(if (right) 30f else -30f, 0f), delayMillis = 40) }
        }
        composeRule.waitForIdle()
        if (captureFile != null) host.captureRoboImage("src/test/snapshots/$captureFile")
        host.performTouchInput { up() }
        composeRule.waitForIdle()
    }

    /** Pins only this golden's clock inputs and restores them even if capture or native selection fails. */
    private inline fun withCodePathClock(block: () -> Unit) {
        val originalZone = TimeZone.getDefault()
        val originalTimeFormat = Settings.System.getString(app.contentResolver, Settings.System.TIME_12_24)
        try {
            TimeZone.setDefault(TimeZone.getTimeZone("UTC"))
            Settings.System.putString(app.contentResolver, Settings.System.TIME_12_24, "12")
            block()
        } finally {
            try {
                Settings.System.putString(app.contentResolver, Settings.System.TIME_12_24, originalTimeFormat)
            } finally {
                TimeZone.setDefault(originalZone)
            }
        }
    }

    /** Select text from action menu speak clears mode and starts at pressed sentence. */
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

        composeRule
            .onNodeWithText(app.getString(R.string.select_text))
            .performScrollTo()
            .assertIsDisplayed()
            .performClick()
        composeRule.waitForIdle()
        assertTrue(textSelectionMode)
        assertNativeSpeakDisplayed()

        clickNativeSpeak()
        waitForTts(engine, appState)

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

    /** Native selection menu speak uses same speak path. */
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
        composeRule
            .onNodeWithText(app.getString(R.string.select_text))
            .performScrollTo()
            .assertIsDisplayed()
            .performClick()
        composeRule.waitForIdle()
        assertNativeSpeakDisplayed()

        clickNativeSpeak()
        waitForTts(engine, appState)

        assertTrue(
            engine.spoken
                .first()
                .text
                .contains("Accessibility sentence"),
        )
    }

    /** Native selection menu replaces system read aloud and keeps other process text actions. */
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
        composeRule
            .onNodeWithText(app.getString(R.string.select_text))
            .performScrollTo()
            .assertIsDisplayed()
            .performClick()
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

    private fun waitForTts(
        engine: FakeSessionEngine,
        appState: WhiteNoiseAppState,
    ) {
        try {
            composeRule.waitUntil(timeoutMillis = 10_000) {
                shadowOf(android.os.Looper.getMainLooper()).idle()
                engine.spoken.isNotEmpty()
            }
        } catch (timeout: androidx.compose.ui.test.ComposeTimeoutException) {
            throw AssertionError(
                "state=${appState.ttsController.state.value}; failure=${appState.ttsController.lastStartFailure}",
                timeout,
            )
        }
    }

    /** Message bubble host. */
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
        // Gesture tests require a healthy native feed; feed loss is covered separately.
        appState.liveSubscriptionOverrides.conversation =
            ScriptedConversationLiveSubscriptions(
                timelineScripts = listOf(ScriptedConversationTimelineSubscription(snapshotPage = null)),
                group = group(),
            ).subscriptions
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
