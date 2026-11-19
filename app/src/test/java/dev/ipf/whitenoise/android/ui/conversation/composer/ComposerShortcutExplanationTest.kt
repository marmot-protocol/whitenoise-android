package dev.ipf.whitenoise.android.ui.conversation.composer

import android.content.Context
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Surface
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.click
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isPopup
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import dev.ipf.whitenoise.android.R
import dev.ipf.whitenoise.android.audio.VoiceRecordingController
import dev.ipf.whitenoise.android.core.MentionComposer
import dev.ipf.whitenoise.android.core.MessageTextCopy
import dev.ipf.whitenoise.android.state.ComposerShortcut
import dev.ipf.whitenoise.android.state.ComposerToolbarSettings
import dev.ipf.whitenoise.android.ui.common.ExplanationHostState
import dev.ipf.whitenoise.android.ui.common.ExplanationSheetHost
import dev.ipf.whitenoise.android.ui.common.LocalExplanationHost
import dev.ipf.whitenoise.android.ui.share.emptyAppState
import dev.ipf.whitenoise.android.ui.theme.WhiteNoiseTheme
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Holding a composer control or + menu row explains it instead of running it or opening customization. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "en-w360dp-h780dp-mdpi")
class ComposerShortcutExplanationTest {
    @get:Rule val composeRule = createComposeRule()
    private val context: Context = ApplicationProvider.getApplicationContext()
    private var cameraCalls = 0
    private var galleryCalls = 0
    private var customizeCalls = 0
    private val host = ExplanationHostState()

    @Test
    fun holdingCameraExplainsItWithoutCapturingOrOfferingCustomization() {
        render()
        composeRule.onNodeWithTag("composer.shortcut.camera").performTouchInput { longClick() }
        assertExplanation(R.string.explanation_composer_camera)
        composeRule.onNodeWithText(context.getString(R.string.composer_customize)).assertDoesNotExist()
        assertEquals(listOf(0, 0), listOf(cameraCalls, customizeCalls))
    }

    @Test
    fun explainAccessibilityActionShowsTheSameSheetWithoutCapturing() {
        render()
        val explain =
            composeRule
                .onNodeWithContentDescription(context.getString(R.string.attachment_camera))
                .fetchSemanticsNode()
                .config[SemanticsActions.CustomActions]
                .single { it.label == context.getString(R.string.explanation_action_explain) }
        composeRule.runOnIdle { assertTrue(explain.action()) }
        assertExplanation(R.string.explanation_composer_camera)
        assertEquals(0, cameraCalls)
    }

    @Test
    fun unavailableVoiceNoteSlotStillOffersTalkBackExplain() {
        val voice = voiceController {}
        try {
            // Typed text hides the hold-to-record mic, leaving the unavailable slot in the toolbar.
            render(ComposerShortcut.VoiceNote, voice, text = "Hi")
            explainViaTalkBack(
                composeRule.onNodeWithContentDescription(context.getString(R.string.voice_message_record)),
            )
        } finally {
            voice.release()
        }
    }

    @Test
    fun holdingTheMicStillRecordsWhileTalkBackCanExplainIt() {
        var permissionRequests = 0
        val voice = voiceController { permissionRequests++ }
        try {
            render(ComposerShortcut.VoiceNote, voice)
            val mic = composeRule.onNodeWithTag("composer.voice.launcher")
            mic.performTouchInput { longClick() }
            composeRule.onNodeWithTag("explanation.sheet").assertDoesNotExist()
            assertTrue("holding still tries to record", permissionRequests > 0)
            explainViaTalkBack(mic)
        } finally {
            voice.release()
        }
    }

    @Test
    fun holdingAPlusMenuRowClosesTheMenuAndExplainsItWithoutRunningTheRow() {
        render()
        composeRule.onNodeWithTag("composer.shortcut.more").performTouchInput { click() }
        val gallery = hasText(context.getString(R.string.attachment_photos_videos)) and hasAnyAncestor(isPopup())
        composeRule.onNode(gallery).performTouchInput { longClick() }
        composeRule.onNodeWithTag("conversation.attachment.menu").assertDoesNotExist()
        assertExplanation(R.string.explanation_composer_gallery)
        assertEquals(listOf(0, 0), listOf(galleryCalls, customizeCalls))
    }

    @Test
    fun plusMenuCustomizeRowOpensCustomization() {
        render()
        composeRule.onNodeWithTag("composer.shortcut.more").performTouchInput { click() }
        composeRule.onNodeWithTag("conversation.attachment.menu.customize").performScrollTo().performClick()
        composeRule.onNodeWithTag("conversation.attachment.menu").assertDoesNotExist()
        assertEquals(listOf(0, 1), listOf(galleryCalls, customizeCalls))
    }

    private fun explainViaTalkBack(node: SemanticsNodeInteraction) {
        val actions = node.fetchSemanticsNode().config[SemanticsActions.CustomActions]
        assertTrue(actions.any { it.label == context.getString(R.string.composer_customize) })
        val explain = actions.single { it.label == context.getString(R.string.explanation_action_explain) }
        composeRule.runOnIdle { assertTrue(explain.action()) }
        assertExplanation(R.string.explanation_composer_voice_note)
        assertEquals(0, customizeCalls)
    }

    private fun voiceController(onPermissionRequest: () -> Unit) =
        VoiceRecordingController(
            context = context,
            outputDirectory = context.cacheDir,
            scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined),
            onPermissionRequest = {
                onPermissionRequest()
                false
            },
            onRecordingComplete = { _, _ -> },
            onError = {},
        )

    /**
     * Rows the composer lays out above its editor, such as the mention picker or the dictation recovery status, stay
     * visible: the explanation's edge is the top of the composer's whole surface, not the editor row below them.
     */
    @Test
    fun explanationSitsAboveTheRowsOverTheEditor() {
        render(mentions = listOf(mentionCandidate))
        composeRule.onNode(hasSetTextAction()).performClick().performTextInput("@")
        composeRule.waitForIdle()
        val pickerTop =
            composeRule
                .onNodeWithText("Member 1")
                .fetchSemanticsNode()
                .positionInWindow.y

        composeRule.onNodeWithTag("composer.shortcut.camera").performTouchInput { longClick() }

        composeRule.runOnIdle {
            val edge = checkNotNull(host.bottomWindowY) { "the explanation did not open above the composer" }
            assertTrue("the explanation's edge $edge is below the mention picker's top $pickerTop", edge <= pickerTop)
        }
        composeRule.onNode(hasTestTag("explanation.sheet") and hasAnyAncestor(isPopup())).assertIsDisplayed()
        assertEquals(0, cameraCalls)
    }

    /** The explanation shows [body] in the composer's keyboard-safe popup, never the focusable modal sheet. */
    private fun assertExplanation(body: Int) {
        composeRule.onNode(hasTestTag("explanation.sheet") and hasAnyAncestor(isPopup())).assertIsDisplayed()
        composeRule.onNodeWithTag("explanation.body").assertTextEquals(context.getString(body))
    }

    /**
     * Renders ComposerBar with the More shortcut and [extra] pinned, inside the composer's explanation scope, offering
     * [mentions] to the mention picker.
     */
    private fun render(
        extra: ComposerShortcut = ComposerShortcut.Camera,
        voice: VoiceRecordingController? = null,
        text: String = "",
        mentions: List<MentionComposer.Candidate> = emptyList(),
    ) {
        context
            .getSharedPreferences("whitenoise", Context.MODE_PRIVATE)
            .edit()
            .clear()
            .putString(
                "composer_toolbar_v1",
                ComposerToolbarSettings(listOf(ComposerShortcut.More, extra)).encode(),
            ).commit()
        val appState = emptyAppState()
        composeRule.setContent {
            WhiteNoiseTheme {
                CompositionLocalProvider(LocalExplanationHost provides host) {
                    Surface {
                        Box(Modifier.width(360.dp).height(600.dp), contentAlignment = Alignment.BottomCenter) {
                            ExplainAboveComposer {
                                ComposerBar(
                                    appState = appState,
                                    onCustomizeToolbar = { customizeCalls++ },
                                    draftAccountRef = null,
                                    replyingTo = null,
                                    mentionCandidates = mentions,
                                    mentionPickerEnabled = mentions.isNotEmpty(),
                                    messageTextCopy = MessageTextCopy.Default,
                                    onCancelReply = {},
                                    onSend = { _, _ -> },
                                    textState = ComposerTextState(TextFieldValue(text)),
                                    onCaptureFromCamera = { cameraCalls++ },
                                    onPickFromGallery = { galleryCalls++ },
                                    onPickDocument = {},
                                    voiceRecordingController = voice,
                                )
                            }
                        }
                    }
                }
                ExplanationSheetHost(host)
            }
        }
        composeRule.waitForIdle()
    }
}

/** One group member the mention picker can offer. */
private val mentionCandidate = MentionComposer.Candidate("1".padStart(64, '0'), "npub1" + "q".repeat(58), "Member 1")
