package dev.ipf.whitenoise.android.ui.conversation.messages

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.click
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.dp
import dev.ipf.whitenoise.android.ui.theme.WhiteNoiseTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Which taps close the focused-message overlay.
 *
 * The overlay is a stack — a reaction rail, the lifted message, and the action menu — floating over a
 * scrim. Everything that is not one of those controls is "outside", and #2607 reported that only some
 * of it dismissed: the lifted message installed a tap detector that swallowed taps and did nothing.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], qualifiers = "en-rUS-w360dp-h780dp-mdpi")
class FocusedMessageOutsideDismissTest {
    @get:Rule val composeRule = createComposeRule()

    /** A tap on the lifted message leaves the overlay, as a tap on the scrim does. */
    @Test
    fun tappingTheLiftedMessageDismisses() {
        var dismissals = 0
        render(onDismiss = { dismissals += 1 })

        composeRule.onNodeWithTag(PREVIEW_TAG).performTouchInput { click() }

        assertEquals("the lifted message must dismiss", 1, dismissals)
    }

    /**
     * The same dismissal is reachable without a tap.
     *
     * A screen reader could describe the lifted message but had no way to leave from it, so the
     * preview carries the dismissal as a click action too.
     */
    @Test
    fun theLiftedMessageOffersAnAccessibleDismissal() {
        var dismissals = 0
        render(onDismiss = { dismissals += 1 })

        composeRule.onNodeWithTag(PREVIEW_TAG).assertHasClickAction().performClick()

        assertEquals("the accessible dismissal must dismiss", 1, dismissals)
    }

    /** An action still acts instead of dismissing: the menu consumes its own taps first. */
    @Test
    fun tappingAnActionDoesNotDismiss() {
        var dismissals = 0
        var acted = 0
        render(onDismiss = { dismissals += 1 }, onAction = { acted += 1 })

        composeRule.onNodeWithText(ACTION_LABEL).performClick()

        assertEquals("the action must run", 1, acted)
        assertEquals("the action must not also dismiss", 0, dismissals)
    }

    /** A reaction still reacts rather than dismissing, for the same reason. */
    @Test
    fun tappingAReactionDoesNotDismiss() {
        var dismissals = 0
        var reactions = 0
        render(onDismiss = { dismissals += 1 }, onReact = { reactions += 1 })

        composeRule.onNodeWithText(QUICK_REACTION).performClick()

        assertEquals("the reaction must register", 1, reactions)
        assertEquals("the reaction must not also dismiss", 0, dismissals)
    }

    private fun render(
        onDismiss: () -> Unit = {},
        onAction: () -> Unit = {},
        onReact: (String) -> Unit = {},
    ) {
        composeRule.setContent {
            WhiteNoiseTheme {
                FocusedMessageActions(
                    sourceBounds = IntRect(0, 200, 360, 280),
                    touchY = 240f,
                    mine = true,
                    actions =
                        listOf(
                            FocusedMessageAction(
                                label = ACTION_LABEL,
                                supportingLabel = null,
                                enabled = true,
                                destructive = false,
                                icon = {},
                                onClick = onAction,
                            ),
                        ),
                    quickReactions = listOf(QUICK_REACTION),
                    canReact = true,
                    selectedReactions = emptySet(),
                    previewDescription = "Lifted message",
                    previewReady = true,
                    preview = {
                        Box(Modifier.size(200.dp, 60.dp).background(MaterialTheme.colorScheme.surface)) {
                            Text(PREVIEW_TEXT)
                        }
                    },
                    onReact = onReact,
                    onMoreReactions = {},
                    onDismiss = onDismiss,
                )
            }
        }
        composeRule.waitForIdle()
    }

    private companion object {
        const val PREVIEW_TAG = "message-actions-preview"
        const val PREVIEW_TEXT = "The lifted message"
        const val ACTION_LABEL = "Reply"
        const val QUICK_REACTION = "👍"
    }
}
