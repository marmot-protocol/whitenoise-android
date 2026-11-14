package dev.ipf.whitenoise.android.ui.conversation

import android.content.Context
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.test.core.app.ApplicationProvider
import dev.ipf.whitenoise.android.R
import dev.ipf.whitenoise.android.ui.common.Explanation
import dev.ipf.whitenoise.android.ui.common.ExplanationHost
import dev.ipf.whitenoise.android.ui.conversation.composer.EmojiCategoryBar
import dev.ipf.whitenoise.android.ui.conversation.composer.VoiceReviewComposerContent
import dev.ipf.whitenoise.android.ui.conversation.media.MediaPreviewSendBar
import dev.ipf.whitenoise.android.ui.conversation.messages.FocusedActionMenu
import dev.ipf.whitenoise.android.ui.conversation.messages.FocusedMessageAction
import dev.ipf.whitenoise.android.ui.theme.WhiteNoiseTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Holding a conversation control explains it without running it, while a quick tap still does. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "en-w360dp-h780dp-mdpi")
class ConversationExplanationTest {
    @get:Rule val composeRule = createComposeRule()
    private val context: Context = ApplicationProvider.getApplicationContext()
    private var calls = 0

    @Test
    fun resumeFollowExplainsOnHoldRunsOnTapAndOffersTalkBackExplain() {
        render { TtsResumeFollowButton(onClick = { calls++ }) }
        val button = composeRule.onNodeWithContentDescription(context.getString(R.string.tts_resume_follow))
        button.performClick()
        assertEquals(1, calls)
        assertHoldExplains(button, R.string.tts_resume_follow, R.string.explanation_conversation_tts_resume_follow)
        val explain =
            button
                .fetchSemanticsNode()
                .config[SemanticsActions.CustomActions]
                .single { it.label == context.getString(R.string.explanation_action_explain) }
        composeRule.runOnIdle { assertTrue(explain.action()) }
        composeRule.onNodeWithTag("explanation.sheet").assertIsDisplayed()
    }

    @Test
    fun voiceReviewDiscardExplainsOnHoldAndRunsOnTap() {
        render {
            VoiceReviewComposerContent(
                durationMs = 4_000L,
                positionMs = 0,
                playing = false,
                waveform = null,
                onPlay = {},
                onDiscard = { calls++ },
                onRecordAgain = {},
                onSend = {},
            )
        }
        assertHoldAndTap(R.string.discard, R.string.explanation_conversation_voice_discard)
    }

    @Test
    fun messageActionCellExplainsOnHoldAndRunsOnTap() {
        val explanation = Explanation("Reply", context.getString(R.string.explanation_message_action_reply))
        render {
            FocusedActionMenu(
                listOf(
                    FocusedMessageAction(
                        label = "Reply",
                        supportingLabel = null,
                        enabled = true,
                        destructive = false,
                        icon = {},
                        onClick = { calls++ },
                        explanation = explanation,
                    ),
                ),
            )
        }
        val cell = composeRule.onNode(hasText("Reply"))
        cell.performClick()
        assertEquals(1, calls)
        assertHoldExplains(cell, "Reply", R.string.explanation_message_action_reply)
    }

    @Test
    fun mediaPreviewSendExplainsOnHoldAndRunsOnTap() {
        render { MediaPreviewSendBar(initialCaption = "", sending = false, sendEnabled = true, onSend = { calls++ }) }
        assertHoldAndTap(R.string.send, R.string.explanation_media_preview_send)
    }

    @Test
    fun emojiSearchExplainsOnHoldAndRunsOnTap() {
        render {
            EmojiCategoryBar(
                categories = emptyList(),
                selected = null,
                state = rememberLazyListState(),
                onSearch = { calls++ },
                onConfigure = null,
                onBackspace = null,
                onCategory = {},
            )
        }
        assertHoldAndTap(R.string.emoji_search_hint, R.string.explanation_emoji_search)
    }

    private fun assertHoldAndTap(
        label: Int,
        body: Int,
    ) {
        val control = composeRule.onNodeWithContentDescription(context.getString(label))
        control.performClick()
        assertEquals(1, calls)
        assertHoldExplains(control, label, body)
    }

    private fun assertHoldExplains(
        control: SemanticsNodeInteraction,
        title: Int,
        body: Int,
    ) = assertHoldExplains(control, context.getString(title), body)

    private fun assertHoldExplains(
        control: SemanticsNodeInteraction,
        title: String,
        body: Int,
    ) {
        control.performTouchInput { longClick() }
        composeRule.onNodeWithTag("explanation.sheet").assertIsDisplayed()
        composeRule.onNodeWithTag("explanation.body").assertTextEquals(context.getString(body))
        composeRule.onNode(hasText(title) and hasAnyAncestor(hasTestTag("explanation.sheet"))).assertIsDisplayed()
        assertEquals("holding must not run the control again", 1, calls)
    }

    private fun render(content: @Composable () -> Unit) {
        composeRule.setContent {
            WhiteNoiseTheme { ExplanationHost { Surface { content() } } }
        }
        composeRule.waitForIdle()
    }
}
