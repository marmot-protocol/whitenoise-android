package dev.ipf.whitenoise.android.ui.conversation.composer

import android.content.Context
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Surface
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import com.github.takahirom.roborazzi.captureRoboImage
import dev.ipf.whitenoise.android.R
import dev.ipf.whitenoise.android.audio.ConversationDictationController
import dev.ipf.whitenoise.android.audio.ConversationDictationDraftSnapshot
import dev.ipf.whitenoise.android.audio.ConversationDictationPlatform
import dev.ipf.whitenoise.android.audio.ConversationDictationRecognitionListener
import dev.ipf.whitenoise.android.audio.ConversationDictationRecognitionSession
import dev.ipf.whitenoise.android.audio.ConversationDictationTimeoutHandle
import dev.ipf.whitenoise.android.core.MessageTextCopy
import dev.ipf.whitenoise.android.state.ComposerShortcut
import dev.ipf.whitenoise.android.state.ComposerToolbarLayout
import dev.ipf.whitenoise.android.state.ComposerToolbarSettings
import dev.ipf.whitenoise.android.ui.share.emptyAppState
import dev.ipf.whitenoise.android.ui.theme.WhiteNoiseTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

private typealias DictationSession = ConversationDictationRecognitionSession

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "en-w420dp-h780dp-mdpi")
class ComposerToolbarDictationTest {
    @get:Rule val composeRule = createComposeRule()
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private lateinit var text: ComposerTextState
    private var cameraCalls = 0
    private var leaves = 0

    @Test fun activeControlsSurviveManyActionsAndARemovedLauncher() = assertActiveControls(360)

    @Test fun narrowActiveControlsScrollWithoutOverlappingTheOptionalToolbar() = assertActiveControls(200)

    @Test fun shortNarrowActiveRowKeepsInputAndScrollableMandatoryOutcomes() = assertActiveControls(200, 100)

    /**
     * A pinned Emoji opens the picker while dictation runs, and its emoji joins the draft without ending the dictation,
     * while Camera and the picker's exits to other screens stay unavailable.
     */
    @Test
    fun pinnedEmojiAddsToTheDraftWhileDictationRuns() {
        val controller = renderDictating(listOf(ComposerShortcut.Emoji, ComposerShortcut.Camera))
        try {
            composeRule.onNodeWithContentDescription(context.getString(R.string.attachment_camera)).assertIsNotEnabled()
            composeRule
                .onNodeWithContentDescription(context.getString(R.string.open_emoji_picker))
                .assertIsEnabled()
                .performClick()
            pickGrinningFace()

            assertEquals("😀", text.valueState.value.text)
            assertDictationStillRunning(controller)
            composeRule.onNodeWithTag(EMOJI_PICKER_BROWSE_SETS_TEST_TAG, useUnmergedTree = true).assertDoesNotExist()
            composeRule.onNodeWithTag(EMOJI_PICKER_SETTINGS_TEST_TAG, useUnmergedTree = true).assertDoesNotExist()
            assertEquals(0, cameraCalls + leaves)
        } finally {
            controller.cancel()
        }
    }

    /** The Plus menu offers Emoji while dictation runs and leaves out attachments, Dictate and Customize. */
    @Test
    fun plusMenuOffersEmojiButNoAttachmentsWhileDictationRuns() {
        val controller = renderDictating(listOf(ComposerShortcut.More))
        try {
            composeRule.onNodeWithContentDescription(context.getString(R.string.attach_options)).performClick()
            composeRule.waitForIdle()
            composeRule.onNodeWithTag("conversation.attachment.menu").assertIsDisplayed()
            composeRule.onNodeWithText(context.getString(R.string.attachment_camera)).assertDoesNotExist()
            composeRule.onNodeWithText(context.getString(R.string.composer_customize)).assertDoesNotExist()
            composeRule.onNodeWithTag("conversation.attachment.menu.dictation").assertDoesNotExist()
            composeRule.onNodeWithTag("conversation.attachment.menu.emoji").performClick()
            pickGrinningFace()

            assertEquals("😀", text.valueState.value.text)
            assertDictationStillRunning(controller)
            assertEquals(0, cameraCalls)
        } finally {
            controller.cancel()
        }
    }

    /** Renders the production composer with [shortcuts] pinned while a dictation of this chat is listening. */
    private fun renderDictating(shortcuts: List<ComposerShortcut>): ConversationDictationController {
        saveToolbar(ComposerToolbarSettings(shortcuts, ComposerToolbarLayout.AlwaysExpanded))
        val controller = listeningController()
        text = ComposerTextState(TextFieldValue())
        composeRule.setContent {
            WhiteNoiseTheme {
                Surface {
                    Box(Modifier.width(420.dp).height(780.dp), contentAlignment = Alignment.BottomCenter) {
                        ComposerBar(
                            appState = emptyAppState(),
                            replyingTo = null,
                            messageTextCopy = MessageTextCopy.Default,
                            onCancelReply = {},
                            onSend = { _, _ -> },
                            onCaptureFromCamera = { cameraCalls++ },
                            dictationController = controller,
                            dictationAccountRef = DICTATION_ACCOUNT,
                            dictationGroupIdHex = DICTATION_CHAT,
                            textState = text,
                            onBrowseEmojiSets = { leaves++ },
                            onOpenEmojiSettings = { leaves++ },
                        )
                    }
                }
            }
        }
        composeRule.waitForIdle()
        return controller
    }

    /** Waits for the picker's emoji to load, then picks the grinning face. */
    private fun pickGrinningFace() {
        composeRule.waitUntil(5_000) {
            composeRule.onAllNodesWithText("😀").fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onAllNodesWithText("😀").onFirst().performClick()
        composeRule.waitForIdle()
    }

    /** Asserts that [controller]'s dictation still owns this chat's draft and still needs its completion. */
    private fun assertDictationStillRunning(controller: ConversationDictationController) {
        assertTrue(controller.isOwnedBy(DICTATION_ACCOUNT, DICTATION_CHAT))
        assertTrue(controller.completionControlsRequired)
    }

    /** Checks that mandatory dictation outcomes remain reachable beside the configured toolbar. */
    @Suppress("LongMethod")
    private fun assertActiveControls(
        width: Int,
        height: Int = 600,
    ) {
        saveToolbar(
            ComposerToolbarSettings(
                ComposerShortcut.entries - ComposerShortcut.Dictation,
                ComposerToolbarLayout.AlwaysExpanded,
            ),
        )
        val appState = emptyAppState()
        val controller = listeningController()
        val draft = ComposerTextState(TextFieldValue())
        composeRule.mainClock.autoAdvance = false
        try {
            composeRule.setContent {
                WhiteNoiseTheme {
                    Surface {
                        Box(Modifier.width(width.dp).height(height.dp), contentAlignment = Alignment.BottomCenter) {
                            ComposerBar(
                                appState = appState,
                                replyingTo = null,
                                messageTextCopy = MessageTextCopy.Default,
                                onCancelReply = {},
                                onSend = { _, _ -> },
                                onCaptureFromCamera = {},
                                onPickFromGallery = {},
                                onPickDocument = {},
                                onShareLocation = {},
                                onShareUser = {},
                                onShareContact = {},
                                onCreatePoll = {},
                                dictationController = controller,
                                dictationAccountRef = DICTATION_ACCOUNT,
                                dictationGroupIdHex = DICTATION_CHAT,
                                textState = draft,
                            )
                        }
                    }
                }
            }
            composeRule.mainClock.advanceTimeBy(300)
            val toolbars =
                if (height >= 300) {
                    composeRule.onAllNodesWithTag("composer.shortcuts").fetchSemanticsNodes().map { it.boundsInRoot }
                } else {
                    emptyList()
                }
            val controls =
                composeRule
                    .onNodeWithTag(COMPOSER_DICTATION_COMPACT_ACTIONS_TAG)
                    .assertIsDisplayed()
                    .fetchSemanticsNode()
                    .boundsInRoot
            assertTrue("completion controls retain a usable viewport", controls.width >= 48f)
            if (height >= 300) {
                assertTrue("physical shortcut groups are present", toolbars.isNotEmpty())
                toolbars.forEach { toolbar ->
                    assertTrue(
                        "each optional group stays clear of mandatory controls",
                        toolbar.right <= controls.left + 1f ||
                            toolbar.left >= controls.right - 1f ||
                            toolbar.bottom <= controls.top + 1f ||
                            toolbar.top >= controls.bottom - 1f,
                    )
                }
            } else {
                val editor = composeRule.onNode(hasSetTextAction()).fetchSemanticsNode().boundsInRoot
                assertTrue("short active row reserves usable input", editor.width >= 95f)
                assertTrue("input stays clear of completion commands", editor.right <= controls.left + 1f)
            }
            if (height == 100) {
                composeRule.onRoot().captureRoboImage("src/test/snapshots/composer_dictation_short_narrow.png")
            }
            composeRule.mainClock.autoAdvance = true
            listOf(R.string.send, R.string.paste_dictation_transcript, R.string.cancel).forEach { label ->
                val button =
                    composeRule
                        .onNodeWithContentDescription(context.getString(label))
                        .performScrollTo()
                        .assertIsDisplayed()
                        .fetchSemanticsNode()
                assertTrue(
                    "each mandatory glyph slot can be fully reached",
                    button.boundsInRoot.width >= 35f && button.boundsInRoot.height >= 47f,
                )
                assertTrue(
                    "each compact command retains a 48dp touch target",
                    button.touchBoundsInRoot.width >= 47f && button.touchBoundsInRoot.height >= 47f,
                )
            }
        } finally {
            composeRule.mainClock.autoAdvance = true
            controller.cancel()
        }
    }

    /** Persists [settings] as the composer toolbar the next render reads. */
    private fun saveToolbar(settings: ComposerToolbarSettings) {
        context
            .getSharedPreferences("whitenoise", Context.MODE_PRIVATE)
            .edit()
            .clear()
            .putString("composer_toolbar_v1", settings.encode())
            .commit()
    }

    /** A dictation controller whose recognizer is ready and listening for this test's chat. */
    private fun listeningController(): ConversationDictationController {
        val platform = ListeningPlatform()
        val controller =
            ConversationDictationController(
                platform = platform,
                readDraft = { _, _ -> ConversationDictationDraftSnapshot(TextFieldValue(), 0L) },
                writeDraft = { _, _, _, _ -> 0L },
                disclosureAccepted = { true },
                markDisclosureAccepted = {},
                scheduleTimeout = { _, _ -> ConversationDictationTimeoutHandle {} },
            )
        assertTrue(controller.requestStart(DICTATION_ACCOUNT, DICTATION_CHAT, TextFieldValue()))
        platform.listener.onReady()
        return controller
    }

    private class ListeningPlatform : ConversationDictationPlatform {
        lateinit var listener: ConversationDictationRecognitionListener

        override fun hasRecordAudioPermission() = true

        override fun recognitionAvailable() = true

        override fun createSession(listener: ConversationDictationRecognitionListener): DictationSession {
            this.listener = listener
            return object : ConversationDictationRecognitionSession {
                override fun start() = Unit

                override fun stop() = Unit

                override fun cancel() = Unit

                override fun destroy() = Unit
            }
        }
    }

    private companion object {
        const val DICTATION_ACCOUNT = "synthetic-account"
        const val DICTATION_CHAT = "synthetic-chat"
    }
}
