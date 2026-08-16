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
class ConversationDictationStripTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun listeningAndProcessingExposeDistinctStateAndCancellationActions() {
        val fixture = fixture(TextFieldValue("Draft", TextRange(5)))
        fixture.controller.requestStart(ACCOUNT, GROUP, fixture.draft)
        fixture.platform.listener.onReady()
        render(fixture)

        composeRule
            .onNodeWithTag(COMPOSER_DICTATION_STRIP_TAG)
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "Dictating…"))
        val stop = composeRule.onNodeWithContentDescription("Stop dictation").assertIsDisplayed()
        val cancel = composeRule.onNodeWithContentDescription("Cancel dictation").assertIsDisplayed()
        listOf(stop, cancel).forEach { action ->
            val bounds = action.getUnclippedBoundsInRoot()
            assertTrue(bounds.right - bounds.left >= 48.dp)
            assertTrue(bounds.bottom - bounds.top >= 48.dp)
        }
        composeRule.onNodeWithContentDescription("Record voice message").assertDoesNotExist()

        fixture.platform.listener.onEndOfSpeech()

        composeRule
            .onNodeWithTag(COMPOSER_DICTATION_STRIP_TAG)
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "Transcribing…"))
        composeRule.onNodeWithContentDescription("Stop dictation").assertDoesNotExist()
        composeRule.onNodeWithContentDescription("Cancel dictation").assertIsDisplayed()
    }

    @Test
    fun ambiguousMergeOffersExplicitCopyInsertOrDiscardReview() {
        val fixture = fixture(TextFieldValue("Original anchor", TextRange(8)))
        fixture.controller.requestStart(ACCOUNT, GROUP, fixture.draft)
        fixture.edit(TextFieldValue("Rewritten draft", TextRange(15)))
        fixture.platform.listener.onResult("dictated words")
        render(fixture)

        composeRule.onNodeWithContentDescription("Review dictated text").performClick()

        composeRule.onNodeWithTag(COMPOSER_DICTATION_REVIEW_DIALOG_TAG).assertIsDisplayed()
        composeRule.onNodeWithText("dictated words").assertIsDisplayed()
        composeRule.onNodeWithText("Copy").assertIsDisplayed()
        composeRule.onNodeWithText("Discard").assertIsDisplayed()
        composeRule.onNodeWithText("Insert at end").performClick()

        assertEquals("Rewritten draft dictated words", fixture.draft.text)
    }

    @Test
    fun compactElsewhereActionKeepsListeningSessionVisibleAndCancellable() {
        val fixture = fixture(TextFieldValue("Draft", TextRange(5)))
        fixture.controller.requestStart(ACCOUNT, GROUP, fixture.draft)
        fixture.platform.listener.onReady()
        renderElsewhereAction(fixture)

        composeRule
            .onNodeWithTag(COMPOSER_DICTATION_ELSEWHERE_ACTION_TAG)
            .assert(
                SemanticsMatcher.expectValue(
                    SemanticsProperties.StateDescription,
                    "Dictation is active in another chat",
                ),
            ).assertIsDisplayed()
        composeRule.onNodeWithContentDescription("Cancel dictation").performClick()

        assertTrue(fixture.controller.state is ConversationDictationState.Idle)
    }

    @Test
    fun compactElsewhereReviewActionDoesNotDiscardTranscript() {
        val fixture = fixture(TextFieldValue("Original anchor", TextRange(8)))
        fixture.controller.requestStart(ACCOUNT, GROUP, fixture.draft)
        fixture.edit(TextFieldValue("Rewritten draft", TextRange(15)))
        fixture.platform.listener.onResult("dictated words")
        renderElsewhereAction(fixture)

        composeRule.onNodeWithContentDescription("Review dictated text").performClick()

        composeRule.onNodeWithTag(COMPOSER_DICTATION_REVIEW_DIALOG_TAG).assertIsDisplayed()
        composeRule.onNodeWithText("dictated words").assertIsDisplayed()
        assertTrue(fixture.controller.state is ConversationDictationState.ReviewRequired)
    }

    @Test
    fun compactLargeFontRtlStripDoesNotClipItsActions() {
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
                        ConversationDictationStrip(
                            state = fixture.controller.state,
                            controller = fixture.controller,
                            modifier = Modifier.width(268.dp),
                        )
                    }
                }
            }
        }
    }

    private fun renderElsewhereAction(fixture: Fixture) {
        composeRule.setContent {
            WhiteNoiseTheme {
                ConversationDictationElsewhereAction(
                    state = fixture.controller.state,
                    controller = fixture.controller,
                )
            }
        }
    }

    private fun fixture(initial: TextFieldValue): Fixture {
        val platform = FakePlatform()
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
    private class FakePlatform : ConversationDictationPlatform {
        lateinit var listener: ConversationDictationRecognitionListener

        override fun hasRecordAudioPermission() = true

        override fun recognitionAvailable() = true

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
