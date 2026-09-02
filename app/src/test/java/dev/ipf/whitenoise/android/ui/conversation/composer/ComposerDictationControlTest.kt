package dev.ipf.whitenoise.android.ui.conversation.composer

import androidx.compose.foundation.layout.width
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
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
import androidx.compose.ui.test.performSemanticsAction
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
    fun availableDictationDoesNotDisplaceOrMoveTheEmojiAction() {
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
        assertEquals(before.top, after.top)
        assertEquals(before.right, after.right)
        assertEquals(before.bottom, after.bottom)
        composeRule.onNodeWithContentDescription("Dictate text").assertIsDisplayed()
    }

    @Test
    fun dictationRemainsReachableBesideSendForANonBlankDraft() {
        render(draft = TextFieldValue("Ready"))

        composeRule.onNodeWithContentDescription("Dictate text").assertIsDisplayed()
        composeRule.onNodeWithContentDescription("Send").assertIsDisplayed()
    }

    /** Verifies app-owned controls replace only their compact slot and leave adjacent actions stable. */
    @Test
    fun compactComposerActionMorphsToAppOwnedDoneAndCancelWithoutMovingNeighbors() {
        val controller = render(draft = TextFieldValue("Ready"), withAttachments = true)
        val field = composeRule.onNode(hasSetTextAction()).performClick().assertIsFocused()
        val emojiBefore = composeRule.onNodeWithContentDescription("Open emoji picker").getUnclippedBoundsInRoot()
        val attachmentBefore = composeRule.onNodeWithContentDescription("Add attachment").getUnclippedBoundsInRoot()
        val sendBefore = composeRule.onNodeWithContentDescription("Send").getUnclippedBoundsInRoot()

        composeRule.onNodeWithContentDescription("Dictate text").performClick()

        assertTrue(controller.state is ConversationDictationState.Starting)
        assertTrue(controller.ownsMicrophone)
        composeRule.onNodeWithTag(COMPOSER_DICTATION_STRIP_TAG).assertDoesNotExist()
        composeRule.onNodeWithContentDescription("Done").assertIsDisplayed()
        composeRule.onNodeWithContentDescription("Cancel dictation").assertIsDisplayed()
        field.assertIsDisplayed().assertIsFocused()
        assertEquals(
            emojiBefore,
            composeRule.onNodeWithContentDescription("Open emoji picker").getUnclippedBoundsInRoot(),
        )
        assertEquals(
            attachmentBefore,
            composeRule.onNodeWithContentDescription("Add attachment").getUnclippedBoundsInRoot(),
        )
        assertEquals(sendBefore, composeRule.onNodeWithContentDescription("Send").getUnclippedBoundsInRoot())
    }

    /** Verifies the wider dictation actions keep the attachment anchored when Send is absent. */
    @Test
    fun blankComposerDictationMorphDoesNotMoveAttachment() {
        val controller = render(withAttachments = true)
        val attachmentBefore =
            composeRule.onNodeWithContentDescription("Add attachment").getUnclippedBoundsInRoot()

        composeRule.onNodeWithContentDescription("Dictate text").performClick()

        assertTrue(controller.state is ConversationDictationState.Starting)
        composeRule.onNodeWithContentDescription("Done").assertIsDisplayed()
        composeRule.onNodeWithContentDescription("Cancel dictation").assertIsDisplayed()
        assertEquals(
            attachmentBefore,
            composeRule.onNodeWithContentDescription("Add attachment").getUnclippedBoundsInRoot(),
        )
    }

    /** Verifies the same attachment anchor contract under mirrored layout direction. */
    @Test
    fun blankRtlComposerDictationMorphDoesNotMoveAttachment() {
        val controller = render(rtl = true, withAttachments = true)
        val attachmentBefore =
            composeRule.onNodeWithContentDescription("Add attachment").getUnclippedBoundsInRoot()

        composeRule.onNodeWithContentDescription("Dictate text").performClick()

        assertTrue(controller.state is ConversationDictationState.Starting)
        composeRule.onNodeWithContentDescription("Done").assertIsDisplayed()
        composeRule.onNodeWithContentDescription("Cancel dictation").assertIsDisplayed()
        assertEquals(
            attachmentBefore,
            composeRule.onNodeWithContentDescription("Add attachment").getUnclippedBoundsInRoot(),
        )
    }

    /** Verifies starting dictation does not focus a composer whose keyboard was already closed. */
    @Test
    fun appOwnedDictationDoesNotOpenAnInitiallyClosedComposer() {
        val controller = render(draft = TextFieldValue("Ready"))
        val field = composeRule.onNode(hasSetTextAction()).assertIsNotFocused()

        composeRule.onNodeWithContentDescription("Dictate text").performClick()

        assertTrue(controller.state is ConversationDictationState.Starting)
        field.assertIsDisplayed().assertIsNotFocused()
        composeRule.onNodeWithContentDescription("Done").assertIsDisplayed()
        composeRule.onNodeWithContentDescription("Cancel dictation").assertIsDisplayed()
    }

    /** Verifies the unified microphone morphs to dictation controls without exposing a second voice action. */
    @Test
    fun composerOwnedDictationMorphsTheUnifiedMicrophone() {
        val voiceRecording = previewVoiceRecordingController()
        try {
            render(voiceRecordingController = voiceRecording)
            composeRule.onNodeWithContentDescription("Dictate text").assertIsDisplayed()
            composeRule.onNodeWithContentDescription("Hold to record voice message").assertDoesNotExist()

            composeRule.onNodeWithContentDescription("Dictate text").performClick()

            composeRule.onNodeWithContentDescription("Dictate text").assertDoesNotExist()
            composeRule.onNodeWithContentDescription("Hold to record voice message").assertDoesNotExist()
            composeRule.onNodeWithContentDescription("Done").assertIsDisplayed()
            composeRule.onNodeWithContentDescription("Cancel dictation").assertIsDisplayed()
        } finally {
            voiceRecording.release()
        }
    }

    /** Verifies the visible mic is one slot: tap dictates and long press retains voice-note access. */
    @Test
    fun microphoneSlotOwnsBothTapToDictateAndLongPressVoiceNoteActions() {
        val voiceRecording = previewVoiceRecordingController()
        try {
            val controller = render(voiceRecordingController = voiceRecording)
            val microphone =
                composeRule
                    .onNodeWithContentDescription("Dictate text")
                    .assertIsDisplayed()
                    .assert(SemanticsMatcher.keyIsDefined(SemanticsActions.OnLongClick))

            composeRule.onNodeWithContentDescription("Hold to record voice message").assertDoesNotExist()
            microphone.performClick()

            assertTrue(controller.state is ConversationDictationState.Starting)
            composeRule.onNodeWithContentDescription("Done").assertIsDisplayed()
            composeRule.onNodeWithContentDescription("Cancel dictation").assertIsDisplayed()
        } finally {
            voiceRecording.release()
        }
    }

    /** Verifies assistive long-click ownership starts voice capture without also starting dictation. */
    @Test
    fun microphoneSlotLongClickStartsAndLocksVoiceWithoutStartingDictation() {
        val voiceRecording = previewVoiceRecordingController()
        try {
            val controller = render(voiceRecordingController = voiceRecording)

            composeRule
                .onNodeWithContentDescription("Dictate text")
                .performSemanticsAction(SemanticsActions.OnLongClick)

            assertTrue(voiceRecording.isRecording)
            assertTrue(voiceRecording.locked)
            assertTrue(controller.state is ConversationDictationState.Idle)
        } finally {
            voiceRecording.cancel()
            voiceRecording.release()
        }
    }

    /** Verifies the exact visible microphone enters disclosure before it can acquire audio. */
    @Test
    fun visibleMicrophoneStartsDisclosurePreflight() {
        val controller = render(disclosureAccepted = false)

        composeRule.onNodeWithContentDescription("Dictate text").performClick()

        assertTrue(controller.state is ConversationDictationState.DisclosureRequired)
        assertEquals(0L, controller.permissionRequestId)
    }

    /** Verifies the exact visible microphone requests runtime access instead of looking active. */
    @Test
    fun visibleMicrophoneStartsPermissionPreflightWhenAccessIsMissing() {
        val controller = render(permissionGranted = false)

        composeRule.onNodeWithContentDescription("Dictate text").performClick()

        assertTrue(controller.state is ConversationDictationState.PermissionRequired)
        assertEquals(1L, controller.permissionRequestId)
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
        assertTrue(action.right - action.left >= 48.dp)
        assertTrue(action.bottom - action.top >= 48.dp)
        assertTrue(dictation.left >= root.left && dictation.right <= root.right)
        assertTrue(dictation.top >= root.top && dictation.bottom <= root.bottom)
        assertTrue(dictation.right - dictation.left >= 48.dp)
        assertTrue(dictation.bottom - dictation.top >= 48.dp)
        assertTrue("RTL emoji action must remain on the leading side", action.left >= field.left)
        assertTrue(
            "RTL microphone $dictation and emoji $action actions must not overlap",
            dictation.right <= action.left,
        )
    }

    private fun render(
        fontScale: Float = 1f,
        rtl: Boolean = false,
        draft: TextFieldValue = TextFieldValue(""),
        withAttachments: Boolean = false,
        voiceRecordingController: VoiceRecordingController? = null,
        permissionGranted: Boolean = true,
        disclosureAccepted: Boolean = true,
    ): ConversationDictationController {
        val dictationController =
            idleDictationController(
                draft = draft,
                permissionGranted = permissionGranted,
                disclosureAccepted = disclosureAccepted,
            )
        composeRule.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(
                LocalDensity provides Density(density.density, fontScale),
                LocalLayoutDirection provides if (rtl) LayoutDirection.Rtl else LayoutDirection.Ltr,
            ) {
                WhiteNoiseTheme(darkTheme = false) {
                    ComposerBar(
                        replyingTo = null,
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

    private fun idleDictationController(
        draft: TextFieldValue,
        permissionGranted: Boolean,
        disclosureAccepted: Boolean,
    ): ConversationDictationController =
        ConversationDictationController(
            platform = FakeDictationPlatform(permissionGranted),
            readDraft = { _, _ -> ConversationDictationDraftSnapshot(draft, 0L) },
            writeDraft = { _, _, _, _ -> true },
            disclosureAccepted = { disclosureAccepted },
            markDisclosureAccepted = {},
            scheduleTimeout = { _, _ -> ConversationDictationTimeoutHandle {} },
        )

    private class FakeDictationPlatform(
        private val permissionGranted: Boolean,
    ) : ConversationDictationPlatform {
        override fun hasRecordAudioPermission() = permissionGranted

        override fun recognitionAvailable() = true

        @Suppress("MaxLineLength")
        override fun createSession(listener: ConversationDictationRecognitionListener): ConversationDictationRecognitionSession =
            object : ConversationDictationRecognitionSession {
                override fun start() = Unit

                override fun stop() = Unit

                override fun cancel() = Unit

                override fun destroy() = Unit
            }
    }

    private companion object {
        const val ROOT_TAG = "composer-dictation-control-root"
        const val ACCOUNT = "account"
        const val GROUP = "group"
    }
}
