package dev.ipf.whitenoise.android.ui.conversation.composer

import android.content.Context
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Surface
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
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

    @Test fun activeControlsSurviveManyActionsAndARemovedLauncher() = assertActiveControls(360)

    @Test fun narrowActiveControlsScrollWithoutOverlappingTheOptionalToolbar() = assertActiveControls(200)

    @Test fun shortNarrowActiveRowKeepsInputAndScrollableMandatoryOutcomes() = assertActiveControls(200, 100)

    // Controller ownership, production render and geometry assertions form one case.
    @Suppress("LongMethod")
    private fun assertActiveControls(
        width: Int,
        height: Int = 600,
    ) {
        val settings =
            ComposerToolbarSettings(
                ComposerShortcut.entries - ComposerShortcut.Dictation,
                ComposerToolbarLayout.AlwaysExpanded,
            )
        context
            .getSharedPreferences("whitenoise", Context.MODE_PRIVATE)
            .edit()
            .clear()
            .putString("composer_toolbar_v1", settings.encode())
            .commit()
        val appState = emptyAppState()
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
        assertTrue(controller.requestStart("synthetic-account", "synthetic-chat", TextFieldValue()))
        platform.listener.onReady()
        val text = ComposerTextState(TextFieldValue())
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
                                dictationAccountRef = "synthetic-account",
                                dictationGroupIdHex = "synthetic-chat",
                                textState = text,
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
}
