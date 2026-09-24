package dev.ipf.whitenoise.android.ui.conversation.messages

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import dev.ipf.whitenoise.android.R
import dev.ipf.whitenoise.android.state.MessageStatus
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

    @Test
    fun tallMediaPreviewLeavesAnActionViewportAndLastActionReachable() =
        assertActionsScrollWithoutMovingPreview(
            fontScale = 1f,
            layoutDirection = LayoutDirection.Ltr,
            previewHeight = 400,
        )

    /** The real text preview can fill five 200%-scale lines plus a reply and footer. */
    @Test
    fun longTextPreviewAtLargeFontKeepsDeleteReachable() {
        var deletes = 0
        showLongTextMenu { deletes++ }
        composeRule.waitForIdle()

        val preview = composeRule.onNodeWithTag("message-actions-preview").fetchSemanticsNode().boundsInRoot
        assertPreviewWithinFrame(preview, "the bounded text preview must fit the short frame")
        val viewport = composeRule.onNodeWithTag(FOCUSED_ACTION_MENU_SCROLL_TEST_TAG).fetchSemanticsNode().boundsInRoot
        assertTrue("long text must leave a tappable action viewport", viewport.height >= 48f)
        val excerpt = composeRule.onNodeWithTag("message-actions-excerpt", useUnmergedTree = true)
        val footer = composeRule.onNodeWithText("12:34", useUnmergedTree = true)
        excerpt.assertIsDisplayed()
        footer.assertIsDisplayed()
        val excerptBounds = excerpt.getUnclippedBoundsInRoot()
        val footerBounds = footer.getUnclippedBoundsInRoot()
        with(composeRule.density) {
            assertTrue(
                "target excerpt must fit the visible preview",
                excerptBounds.top.toPx() >= preview.top && excerptBounds.bottom.toPx() <= preview.bottom,
            )
            assertTrue(
                "timestamp must fit the visible preview",
                footerBounds.top.toPx() >= preview.top && footerBounds.bottom.toPx() <= preview.bottom,
            )
        }
        val delete = ApplicationProvider.getApplicationContext<Context>().getString(R.string.delete)
        composeRule
            .onNodeWithText(delete)
            .performScrollTo()
            .assertIsDisplayed()
            .performClick()
        composeRule.runOnIdle { assertEquals(1, deletes) }
    }

    private fun showLongTextMenu(onDelete: () -> Unit) {
        composeRule.setContent {
            WhiteNoiseTheme(fontScale = 2f) {
                MessageActionMenu(
                    expanded = true,
                    anchorBoundsInWindow = IntRect(0, 180, 360, 240),
                    anchorWindowYPx = 210f,
                    canReply = true,
                    canReact = true,
                    canDelete = true,
                    canEdit = true,
                    canForward = true,
                    canSelect = true,
                    canCopyText = true,
                    canSpeak = true,
                    canSelectText = true,
                    canSave = true,
                    quickReactionEmojis = listOf("👍", "❤️", "😂", "😮", "😢", "👏"),
                    onDismissRequest = {},
                    onReact = {},
                    onOpenEmojiPicker = {},
                    onReply = {},
                    onEdit = {},
                    onForward = {},
                    onSelect = {},
                    onSelectText = {},
                    onCopyText = {},
                    onSpeak = {},
                    onSave = {},
                    onInfo = {},
                    onDelete = onDelete,
                    previewDescription = "Long lifted text message",
                    preview = { longTextPreview() },
                )
            }
        }
    }

    @Composable
    private fun longTextPreview() {
        FocusedTextMessagePreview(
            presentation = messageBubblePresentation(deleted = false, mine = false),
            mine = false,
            text = LONG_TEXT_PREVIEW,
            document = null,
            time = "12:34",
            status = MessageStatus.Received,
            showStatus = false,
            reply = { Text("Quoted reply with two lines of context") },
        )
    }

    private fun assertActionsScrollWithoutMovingPreview(
        fontScale: Float,
        layoutDirection: LayoutDirection,
        previewHeight: Int = 60,
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
                            Box(Modifier.size(200.dp, previewHeight.dp).background(MaterialTheme.colorScheme.surface)) {
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
        assertPreviewWithinFrame(previewBefore, "the message must start inside the short frame")
        composeRule.onNodeWithTag(FOCUSED_ACTION_MENU_SCROLL_TEST_TAG).assertIsDisplayed()
        if (previewHeight > 60) {
            val actionViewport =
                composeRule.onNodeWithTag(FOCUSED_ACTION_MENU_SCROLL_TEST_TAG).fetchSemanticsNode().boundsInRoot
            assertTrue("tall media must leave a tappable action viewport", actionViewport.height >= 48f)
        }

        composeRule
            .onNodeWithText("Action 11")
            .performScrollTo()
            .assertIsDisplayed()
            .performClick()
        composeRule.waitForIdle()

        val previewAfter = composeRule.onNodeWithTag("message-actions-preview").fetchSemanticsNode().boundsInRoot
        assertEquals("scrolling actions must not move the lifted message", previewBefore.top, previewAfter.top, 0.5f)
        assertPreviewWithinFrame(previewAfter, "the lifted message must remain fully visible")
        assertEquals("the final action must remain reachable", 1, lastActionClicks)
    }

    private fun assertPreviewWithinFrame(
        bounds: Rect,
        message: String,
    ) {
        assertTrue(message, bounds.top >= 0f && bounds.bottom <= 320f)
    }

    private companion object {
        val LONG_TEXT_PREVIEW = (1..8).joinToString(" ") { "This sentence fills the lifted message preview." }
    }
}
