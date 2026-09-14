package dev.ipf.whitenoise.android.ui.conversation.composer

import androidx.compose.foundation.layout.width
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertIsNotFocused
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import dev.ipf.whitenoise.android.audio.ConversationDictationController
import dev.ipf.whitenoise.android.audio.ConversationDictationDraftSnapshot
import dev.ipf.whitenoise.android.audio.ConversationDictationPlatform
import dev.ipf.whitenoise.android.audio.ConversationDictationRecognitionListener
import dev.ipf.whitenoise.android.audio.ConversationDictationRecognitionSession
import dev.ipf.whitenoise.android.audio.ConversationDictationState
import dev.ipf.whitenoise.android.audio.ConversationDictationTimeoutHandle
import dev.ipf.whitenoise.android.audio.VoiceRecordingController
import dev.ipf.whitenoise.android.core.MessageTextCopy
import dev.ipf.whitenoise.android.state.timelineAppMessage
import dev.ipf.whitenoise.android.ui.theme.WhiteNoiseTheme
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], qualifiers = "w320dp-h640dp-mdpi")
class ComposerDictationControlTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun dictationStartedFromAReplyBannerPinsThatVisibleMessageIdentity() {
        val controller = render(replyingTo = timelineAppMessage(REPLY_MESSAGE_ID))

        composeRule.onNodeWithContentDescription("Dictate text").performClick()

        assertEquals(REPLY_MESSAGE_ID, controller.state.target?.replyToMessageIdHex)
    }

    /** Focusing unfolds the editor without displacing emoji from its action row. */
    @Test
    fun focusingUnfoldsTheEditorWithoutDisplacingEmojiFromItsActionRow() {
        render()

        composeRule.onNodeWithContentDescription("Dictate text").assertIsDisplayed()

        val before =
            composeRule
                .onNodeWithContentDescription("Open emoji picker")
                .assertIsDisplayed()
                .getUnclippedBoundsInRoot()

        composeRule.onNode(hasSetTextAction()).performClick()
        composeRule.waitForIdle()

        val after =
            composeRule
                .onNodeWithContentDescription("Open emoji picker")
                .assertIsDisplayed()
                .getUnclippedBoundsInRoot()

        assertEquals(before.left, after.left)
        assertEquals(before.right, after.right)
        assertEquals(before.bottom - before.top, after.bottom - after.top)
        val dictation =
            composeRule
                .onNodeWithContentDescription("Dictate text")
                .assertIsDisplayed()
                .getUnclippedBoundsInRoot()
        assertEquals(after.top, dictation.top)
        assertEquals(after.bottom, dictation.bottom)
        // Focusing opens the prototype editing row above the unchanged action row.
        assertEquals(32.dp, after.top - before.top)
        composeRule.onNode(hasSetTextAction()).assertIsFocused()
    }

    @Test
    fun dictationRemainsReachableBesideSendForANonBlankDraft() {
        render(draft = TextFieldValue("Ready"))

        composeRule.onNodeWithContentDescription("Dictate text").assertIsDisplayed()
        composeRule.onNodeWithContentDescription("Send").assertIsDisplayed()
    }

    /** Verifies app-owned controls replace only their compact slot and leave adjacent actions stable. */
    @Test
    fun compactComposerActionMorphsToCancelPasteSendWithoutMovingEmojiOrFocus() {
        val controller = render(draft = TextFieldValue("Ready"))
        val field = composeRule.onNode(hasSetTextAction()).performClick().assertIsFocused()
        val emojiBefore = composeRule.onNodeWithContentDescription("Open emoji picker").getUnclippedBoundsInRoot()

        composeRule.onNodeWithContentDescription("Dictate text").performClick()

        assertTrue(controller.state is ConversationDictationState.Starting)
        assertTrue(controller.ownsMicrophone)
        composeRule.onNodeWithTag(COMPOSER_DICTATION_STRIP_TAG).assertDoesNotExist()
        composeRule.onNodeWithContentDescription("Paste").assertIsDisplayed()
        composeRule.onNodeWithContentDescription("Cancel").assertIsDisplayed()
        field.assertIsDisplayed().assertIsFocused()
        assertEquals(
            emojiBefore,
            composeRule.onNodeWithContentDescription("Open emoji picker").getUnclippedBoundsInRoot(),
        )
        composeRule.onNodeWithContentDescription("Send").assertIsDisplayed()
        assertEquals(1, composeRule.onAllNodesWithContentDescription("Send").fetchSemanticsNodes().size)
    }

    /** Verifies starting dictation does not focus a composer whose keyboard was already closed. */
    @Test
    fun appOwnedDictationDoesNotOpenAnInitiallyClosedComposer() {
        val controller = render(draft = TextFieldValue("Ready"))
        val field = composeRule.onNode(hasSetTextAction()).assertIsNotFocused()

        composeRule.onNodeWithContentDescription("Dictate text").performClick()

        assertTrue(controller.state is ConversationDictationState.Starting)
        field.assertIsDisplayed().assertIsNotFocused()
        composeRule.onNodeWithContentDescription("Paste").assertIsDisplayed()
        composeRule.onNodeWithContentDescription("Cancel").assertIsDisplayed()
        composeRule.onNodeWithContentDescription("Send").assertIsDisplayed()
    }

    /** Verifies app-owned dictation removes the competing voice-note microphone from the same composer. */
    @Test
    fun composerOwnedDictationSuppressesTheCompetingVoiceNoteMicrophone() {
        val voiceRecording = previewVoiceRecordingController()
        try {
            render(voiceRecordingController = voiceRecording)
            composeRule.onNodeWithContentDescription("Hold to record voice message").assertExists()

            composeRule.onNodeWithContentDescription("Dictate text").performClick()

            composeRule.onNodeWithContentDescription("Hold to record voice message").assertDoesNotExist()
            composeRule.onNodeWithContentDescription("Paste").assertIsDisplayed()
            composeRule.onNodeWithContentDescription("Cancel").assertIsDisplayed()
            composeRule.onNodeWithContentDescription("Send").assertIsDisplayed()
        } finally {
            voiceRecording.release()
        }
    }

    @Test
    fun attachmentSheetDoesNotDuplicateTheComposerDictationAction() {
        val controller = render(withAttachments = true)
        composeRule.onNodeWithContentDescription("Add attachment").performClick()

        val dictationActions = composeRule.onAllNodesWithContentDescription("Dictate text")
        assertEquals(1, dictationActions.fetchSemanticsNodes().size)
        assertTrue(controller.state is ConversationDictationState.Idle)
        assertFalse(controller.ownsMicrophone)
    }

    /** Compact large font rtl layout keeps the emoji action reachable without clipping. */
    @Test
    fun compactLargeFontRtlLayoutKeepsTheEmojiActionReachableWithoutClipping() {
        render(fontScale = 2f, rtl = true)

        val root = composeRule.onNodeWithTag(ROOT_TAG).getUnclippedBoundsInRoot()
        val action =
            composeRule
                .onNodeWithContentDescription("Open emoji picker")
                .assertIsDisplayed()
                .getUnclippedBoundsInRoot()
        val dictation =
            composeRule
                .onNodeWithContentDescription("Dictate text")
                .assertIsDisplayed()
                .getUnclippedBoundsInRoot()
        val field = composeRule.onNode(hasSetTextAction()).assertIsDisplayed().getUnclippedBoundsInRoot()

        assertTrue(action.left >= root.left && action.right <= root.right)
        assertTrue(action.top >= root.top && action.bottom <= root.bottom)
        val minimumTouchTarget = with(composeRule.density) { 48.dp.toPx() }
        val emojiTouch =
            composeRule.onNodeWithContentDescription("Open emoji picker").fetchSemanticsNode().touchBoundsInRoot
        assertTrue(emojiTouch.width >= minimumTouchTarget && emojiTouch.height >= minimumTouchTarget)
        assertTrue(dictation.left >= root.left && dictation.right <= root.right)
        assertTrue(dictation.top >= root.top && dictation.bottom <= root.bottom)
        val dictationTouch =
            composeRule.onNodeWithContentDescription("Dictate text").fetchSemanticsNode().touchBoundsInRoot
        assertTrue(dictationTouch.width >= minimumTouchTarget && dictationTouch.height >= minimumTouchTarget)
        assertTrue("RTL emoji action must remain on the leading side", action.left >= field.left)
        assertTrue("RTL dictation action must not overlap the text field", dictation.right <= field.left)
    }

    /** All four native commands remain independently reachable inside the actual narrow RTL composer. */
    @Test
    @Config(qualifiers = "w240dp-h780dp-mdpi")
    fun narrowLargeRtlDictationActionsScrollWithoutOverlappingTheLeadingTools() {
        val controller = render(fontScale = 2f, rtl = true, withAttachments = true)
        composeRule.onNodeWithContentDescription("Dictate text").performClick()

        /** Records the dispatched action. */
        fun action(label: String) {
            val node = composeRule.onNodeWithContentDescription(label).performScrollTo().assertIsDisplayed()
            val bounds = node.fetchSemanticsNode().boundsInRoot
            val root = composeRule.onNodeWithTag(ROOT_TAG).fetchSemanticsNode().boundsInRoot
            val emoji = composeRule.onNodeWithContentDescription("Open emoji picker").fetchSemanticsNode().boundsInRoot
            assertTrue(bounds.left >= root.left && bounds.right <= root.right)
            assertTrue("RTL actions must stay clear of leading tools", bounds.right <= emoji.left)
            node.performClick()
        }
        action("Pause dictation")
        FakeDictationPlatform.listener.onResult("first")
        action("Resume dictation")
        FakeDictationPlatform.listener.onBeginningOfSpeech()
        action("Paste")
        FakeDictationPlatform.listener.onResult("second")
        assertTrue(controller.state is ConversationDictationState.Idle)
        composeRule.onNodeWithContentDescription("Dictate text").performClick()
        action("Send")
        val late = FakeDictationPlatform.listener
        action("Cancel")
        late.onResult("must not deliver")
        assertTrue(controller.state is ConversationDictationState.Idle)
    }

    private fun render(
        fontScale: Float = 1f,
        rtl: Boolean = false,
        draft: TextFieldValue = TextFieldValue(""),
        withAttachments: Boolean = false,
        voiceRecordingController: VoiceRecordingController? = null,
        replyingTo: dev.ipf.marmotkit.AppMessageRecordFfi? = null,
    ): ConversationDictationController {
        val dictationController = idleDictationController(draft)
        composeRule.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(
                LocalDensity provides Density(density.density, fontScale),
                LocalLayoutDirection provides if (rtl) LayoutDirection.Rtl else LayoutDirection.Ltr,
            ) {
                WhiteNoiseTheme(darkTheme = false) {
                    ComposerBar(
                        replyingTo = replyingTo,
                        messageTextCopy = MessageTextCopy.Default,
                        onCancelReply = {},
                        onSend = { _, _ -> },
                        onPickFromGallery = if (withAttachments) ({}) else null,
                        initialDraft = draft,
                        dictationController = dictationController,
                        dictationAccountRef = ACCOUNT,
                        dictationGroupIdHex = GROUP,
                        voiceRecordingController = voiceRecordingController,
                        modifier = Modifier.width(320.dp).testTag(ROOT_TAG),
                    )
                }
            }
        }
        return dictationController
    }

    /** Creates a no-I/O voice recorder used only to exercise composer action ownership. */
    private fun previewVoiceRecordingController(): VoiceRecordingController {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        return VoiceRecordingController(
            context = context,
            outputDirectory = context.cacheDir,
            scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined),
            onPermissionRequest = { true },
            onRecordingComplete = { _, _ -> },
            onError = {},
        )
    }

    /** Builds an idle dictation controller fixture. */
    private fun idleDictationController(draft: TextFieldValue): ConversationDictationController =
        ConversationDictationController(
            platform = FakeDictationPlatform,
            readDraft = { _, _ -> ConversationDictationDraftSnapshot(draft, 0L) },
            writeDraft = { _, _, _, _ -> true },
            disclosureAccepted = { true },
            markDisclosureAccepted = {},
            scheduleTimeout = { _, _ -> ConversationDictationTimeoutHandle {} },
        )

    private data object FakeDictationPlatform : ConversationDictationPlatform {
        lateinit var listener: ConversationDictationRecognitionListener

        /** Has record audio permission. */
        override fun hasRecordAudioPermission() = true

        /** Recognition available. */
        override fun recognitionAvailable() = true

        /** Fake recognizer: creates a session. */
        @Suppress("MaxLineLength")
        override fun createSession(listener: ConversationDictationRecognitionListener): ConversationDictationRecognitionSession {
            this.listener = listener
            return object : ConversationDictationRecognitionSession {
                override fun start() = Unit

                /** Fake playback: stops. */
                override fun stop() = Unit

                /** Fake operation: cancels. */
                override fun cancel() = Unit

                override fun destroy() = Unit
            }
        }
    }

    private companion object {
        const val ROOT_TAG = "composer-dictation-control-root"
        const val ACCOUNT = "account"
        const val GROUP = "group"
        const val REPLY_MESSAGE_ID = "reply-message"
    }
}
