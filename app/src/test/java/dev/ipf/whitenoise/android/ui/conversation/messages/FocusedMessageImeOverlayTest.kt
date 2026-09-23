package dev.ipf.whitenoise.android.ui.conversation.messages

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import dev.ipf.whitenoise.android.ui.theme.WhiteNoiseTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** The short visible frame models a conversation above an open soft keyboard. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], qualifiers = "en-rUS-w360dp-h320dp-mdpi")
class FocusedMessageImeOverlayTest {
    @get:Rule val composeRule = createComposeRule()

    @Test
    fun scrollingActionsKeepsTheLiftedMessageVisibleAndLastActionReachable() =
        assertActionsScrollWithoutMovingPreview(fontScale = 1f, layoutDirection = LayoutDirection.Ltr)

    @Test
    fun largeTextAndRtlStillKeepTheMessageVisibleAndLastActionReachable() =
        assertActionsScrollWithoutMovingPreview(fontScale = 2f, layoutDirection = LayoutDirection.Rtl)

    private fun assertActionsScrollWithoutMovingPreview(
        fontScale: Float,
        layoutDirection: LayoutDirection,
    ) {
        var lastActionClicks = 0
        composeRule.setContent {
            CompositionLocalProvider(LocalLayoutDirection provides layoutDirection) {
                WhiteNoiseTheme(fontScale = fontScale) {
                    FocusedMessageActions(
                        sourceBounds = IntRect(0, 180, 360, 240),
                        touchY = 210f,
                        mine = true,
                        actions =
                            List(12) { index ->
                                FocusedMessageAction(
                                    label = "Action $index",
                                    supportingLabel = null,
                                    enabled = true,
                                    destructive = false,
                                    icon = {},
                                    onClick = { if (index == 11) lastActionClicks++ },
                                )
                            },
                        quickReactions = listOf("👍", "❤️", "😂", "😮", "😢", "👏"),
                        canReact = true,
                        selectedReactions = emptySet(),
                        previewDescription = "Lifted message",
                        previewReady = true,
                        preview = {
                            Box(Modifier.size(200.dp, 60.dp).background(MaterialTheme.colorScheme.surface)) {
                                Text("Lifted message")
                            }
                        },
                        onReact = {},
                        onMoreReactions = {},
                        onDismiss = {},
                    )
                }
            }
        }
        composeRule.waitForIdle()

        val previewBefore = composeRule.onNodeWithTag("message-actions-preview").fetchSemanticsNode().boundsInRoot
        assertTrue(
            "the message must start inside the short frame",
            previewBefore.top >= 0f && previewBefore.bottom <= 320f,
        )
        composeRule.onNodeWithTag(FOCUSED_ACTION_MENU_SCROLL_TEST_TAG).assertIsDisplayed()

        composeRule
            .onNodeWithText("Action 11")
            .performScrollTo()
            .assertIsDisplayed()
            .performClick()
        composeRule.waitForIdle()

        val previewAfter = composeRule.onNodeWithTag("message-actions-preview").fetchSemanticsNode().boundsInRoot
        assertEquals("scrolling actions must not move the lifted message", previewBefore.top, previewAfter.top, 0.5f)
        assertTrue(
            "the lifted message must remain fully visible",
            previewAfter.top >= 0f && previewAfter.bottom <= 320f,
        )
        assertEquals("the final action must remain reachable", 1, lastActionClicks)
    }
}
