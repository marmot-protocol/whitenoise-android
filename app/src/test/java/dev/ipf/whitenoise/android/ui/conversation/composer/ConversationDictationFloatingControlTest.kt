package dev.ipf.whitenoise.android.ui.conversation.composer

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import dev.ipf.whitenoise.android.audio.ConversationDictationController
import dev.ipf.whitenoise.android.audio.ConversationDictationDraftSnapshot
import dev.ipf.whitenoise.android.audio.ConversationDictationFailure
import dev.ipf.whitenoise.android.audio.ConversationDictationPlatform
import dev.ipf.whitenoise.android.audio.ConversationDictationRecognitionListener
import dev.ipf.whitenoise.android.audio.ConversationDictationRecognitionSession
import dev.ipf.whitenoise.android.audio.ConversationDictationState
import dev.ipf.whitenoise.android.audio.ConversationDictationTimeoutHandle
import dev.ipf.whitenoise.android.ui.theme.WhiteNoiseTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], qualifiers = "w320dp-h640dp-mdpi")
class ConversationDictationFloatingControlTest {
    @get:Rule
    val composeRule = createComposeRule()

    /** Verifies listening and processing announce distinct states and expose only valid actions. */
    @Test
    fun listeningAndProcessingExposeDistinctStateAndCancellationActions() {
        val fixture = fixture(TextFieldValue("Draft", TextRange(5)))
        fixture.controller.requestStart(ACCOUNT, GROUP, fixture.draft)
        fixture.platform.listener.onReady()
        render(fixture)

        composeRule
            .onNodeWithTag(APP_DICTATION_FLOAT_TAG)
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "Dictating…"))
        val stop = composeRule.onNodeWithContentDescription("Done").assertIsDisplayed()
        val cancel = composeRule.onNodeWithContentDescription("Cancel dictation").assertIsDisplayed()
        listOf(stop, cancel).forEach { action ->
            val bounds = action.getUnclippedBoundsInRoot()
            assertTrue(bounds.right - bounds.left >= 48.dp)
            assertTrue(bounds.bottom - bounds.top >= 48.dp)
        }
        composeRule.onNodeWithContentDescription("Record voice message").assertDoesNotExist()

        fixture.platform.listener.onEndOfSpeech()

        composeRule
            .onNodeWithTag(APP_DICTATION_FLOAT_TAG)
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "Transcribing…"))
        composeRule.onNodeWithContentDescription("Done").assertDoesNotExist()
        composeRule.onNodeWithContentDescription("Cancel dictation").assertIsDisplayed()
    }

    /** Verifies an ambiguous merge retains explicit copy, insert, and discard choices. */
    @Test
    fun ambiguousMergeOffersExplicitCopyInsertOrDiscardReview() {
        val fixture = fixture(TextFieldValue("Original anchor", TextRange(8)))
        fixture.controller.requestStart(ACCOUNT, GROUP, fixture.draft)
        fixture.platform.listener.onReady()
        fixture.platform.listener.onResult("dictated words")
        fixture.edit(TextFieldValue("Rewritten draft", TextRange(15)))
        fixture.controller.stop()
        render(fixture)

        composeRule.onNodeWithContentDescription("Review dictated text").performClick()

        composeRule.onNodeWithTag(COMPOSER_DICTATION_REVIEW_DIALOG_TAG).assertIsDisplayed()
        composeRule.onNodeWithText("dictated words").assertIsDisplayed()
        composeRule.onNodeWithText("Copy").assertIsDisplayed()
        composeRule.onNodeWithText("Discard").assertIsDisplayed()
        composeRule.onNodeWithText("Insert at end").performClick()

        assertEquals("Rewritten draft dictated words", fixture.draft.text)
    }

    /** Verifies navigation-owned listening remains visible and can still be cancelled. */
    @Test
    fun rootFloatKeepsListeningSessionVisibleAndCancellable() {
        val fixture = fixture(TextFieldValue("Draft", TextRange(5)))
        fixture.controller.requestStart(ACCOUNT, GROUP, fixture.draft)
        fixture.platform.listener.onReady()
        render(fixture)

        composeRule
            .onNodeWithTag(APP_DICTATION_FLOAT_TAG)
            .assert(
                SemanticsMatcher.expectValue(
                    SemanticsProperties.StateDescription,
                    "Dictating…",
                ),
            ).assertIsDisplayed()
        composeRule.onNodeWithContentDescription("Cancel dictation").performClick()

        assertTrue(fixture.controller.state is ConversationDictationState.Idle)
    }

    /** Verifies opening review from the root control never discards retained transcript text. */
    @Test
    fun rootFloatReviewActionDoesNotDiscardTranscript() {
        val fixture = fixture(TextFieldValue("Original anchor", TextRange(8)))
        fixture.controller.requestStart(ACCOUNT, GROUP, fixture.draft)
        fixture.platform.listener.onReady()
        fixture.platform.listener.onResult("dictated words")
        fixture.edit(TextFieldValue("Rewritten draft", TextRange(15)))
        fixture.controller.stop()
        render(fixture)

        composeRule.onNodeWithContentDescription("Review dictated text").performClick()

        composeRule.onNodeWithTag(COMPOSER_DICTATION_REVIEW_DIALOG_TAG).assertIsDisplayed()
        composeRule.onNodeWithText("dictated words").assertIsDisplayed()
        assertTrue(fixture.controller.state is ConversationDictationState.ReviewRequired)
    }

    /** Verifies provider-readiness feedback and cancellation fit at large font in RTL. */
    @Test
    fun readinessFeedbackFitsTheRootFloatAtLargeFontRtl() {
        val fixture = fixture(TextFieldValue("Keep"), deferActivityReadiness = true)
        fixture.controller.requestProviderActivityStart(ACCOUNT, GROUP, fixture.draft)
        render(fixture, fontScale = 2f, rtl = true)

        val root = composeRule.onNodeWithTag(ROOT_TAG).getUnclippedBoundsInRoot()
        val control = composeRule.onNodeWithTag(APP_DICTATION_FLOAT_TAG).getUnclippedBoundsInRoot()
        assertTrue(control.left >= root.left && control.right <= root.right)
        composeRule.onNodeWithContentDescription("Checking speech service…").assertIsDisplayed()
        val cancel =
            composeRule
                .onNodeWithContentDescription("Cancel dictation")
                .assertIsDisplayed()
                .getUnclippedBoundsInRoot()
        assertTrue(cancel.left >= root.left && cancel.right <= root.right)
        assertTrue(cancel.right - cancel.left >= 48.dp)
    }

    /** Verifies retry and dismiss actions remain visible and touch-accessible in compact RTL. */
    @Test
    fun compactLargeFontRtlFloatDoesNotClipItsActions() {
        val fixture = fixture(TextFieldValue(""))
        fixture.controller.requestStart(ACCOUNT, GROUP, fixture.draft)
        fixture.platform.listener.onError(ConversationDictationFailure.ProviderUnavailable)
        render(fixture, fontScale = 2f, rtl = true)

        val root = composeRule.onNodeWithTag(ROOT_TAG).getUnclippedBoundsInRoot()
        listOf("Retry", "Dismiss").forEach { label ->
            val action = composeRule.onNodeWithContentDescription(label).assertIsDisplayed().getUnclippedBoundsInRoot()
            assertTrue(action.left >= root.left && action.right <= root.right)
            assertTrue(action.top >= root.top && action.bottom <= root.bottom)
            assertTrue(action.right - action.left >= 48.dp)
            assertTrue(action.bottom - action.top >= 48.dp)
        }
    }

    /** Renders the app-root control under configurable density-direction conditions. */
    private fun render(
        fixture: Fixture,
        fontScale: Float = 1f,
        rtl: Boolean = false,
    ) {
        composeRule.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(
                LocalDensity provides Density(density.density, fontScale),
                LocalLayoutDirection provides if (rtl) LayoutDirection.Rtl else LayoutDirection.Ltr,
            ) {
                WhiteNoiseTheme {
                    Box(Modifier.width(268.dp).testTag(ROOT_TAG)) {
                        ConversationDictationFloatingControl(
                            state = fixture.controller.state,
                            controller = fixture.controller,
                        )
                    }
                }
            }
        }
    }

    /** Builds a mutable-draft controller fixture with optional deferred provider readiness. */
    private fun fixture(
        initial: TextFieldValue,
        deferActivityReadiness: Boolean = false,
    ): Fixture {
        val platform = FakePlatform(deferActivityReadiness)
        var draft = initial
        var revision = 0L
        val controller =
            ConversationDictationController(
                platform = platform,
                readDraft = { _, _ -> ConversationDictationDraftSnapshot(draft, revision) },
                writeDraft = { _, _, expected, value ->
                    if (expected != revision) {
                        false
                    } else {
                        draft = value
                        revision += 1L
                        true
                    }
                },
                disclosureAccepted = { true },
                markDisclosureAccepted = {},
                scheduleTimeout = { _, _ -> ConversationDictationTimeoutHandle {} },
            )
        return Fixture(
            controller = controller,
            platform = platform,
            readDraft = { draft },
            editDraft = { value ->
                draft = value
                revision += 1L
            },
        )
    }

    private data class Fixture(
        val controller: ConversationDictationController,
        val platform: FakePlatform,
        private val readDraft: () -> TextFieldValue,
        private val editDraft: (TextFieldValue) -> Unit,
    ) {
        val draft: TextFieldValue
            get() = readDraft()

        fun edit(value: TextFieldValue) = editDraft(value)
    }

    @Suppress("MaxLineLength")
    private class FakePlatform(
        private val deferActivityReadiness: Boolean,
    ) : ConversationDictationPlatform {
        lateinit var listener: ConversationDictationRecognitionListener

        override fun hasRecordAudioPermission() = true

        override fun recognitionAvailable() = true

        override fun checkRecognitionActivity(callback: (Boolean) -> Unit): ConversationDictationTimeoutHandle {
            if (!deferActivityReadiness) callback(true)
            return ConversationDictationTimeoutHandle {}
        }

        override fun createSession(listener: ConversationDictationRecognitionListener): ConversationDictationRecognitionSession {
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
        const val ACCOUNT = "account"
        const val GROUP = "group"
        const val ROOT_TAG = "dictation-strip-test-root"
    }
}
