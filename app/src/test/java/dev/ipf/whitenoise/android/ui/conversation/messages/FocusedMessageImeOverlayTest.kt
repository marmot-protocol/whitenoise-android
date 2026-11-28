package dev.ipf.whitenoise.android.ui.conversation.messages

import android.content.Context
import android.view.View
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.click
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.core.graphics.Insets
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
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

/** The menu uses the full-height frame after dismissing the keyboard. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], qualifiers = "en-rUS-w360dp-h780dp-mdpi")
class FocusedMessageImeOverlayTest {
    @get:Rule val composeRule = createComposeRule()

    /** Checks action scrolling keeps a standard-size left-to-right preview fixed and the final action reachable. */
    @Test
    fun scrollingActionsKeepsTheLiftedMessageVisibleAndLastActionReachable() =
        assertActionsScrollWithoutMovingPreview(fontScale = 1f, layoutDirection = LayoutDirection.Ltr)

    /** Checks action scrolling preserves the preview and final action with doubled text size and RTL layout. */
    @Test
    fun largeTextAndRtlStillKeepTheMessageVisibleAndLastActionReachable() =
        assertActionsScrollWithoutMovingPreview(fontScale = 2f, layoutDirection = LayoutDirection.Rtl)

    /** Checks a tall preview leaves room to scroll to and invoke the final action. */
    @Test
    fun tallMediaPreviewLeavesAnActionViewportAndLastActionReachable() =
        assertActionsScrollWithoutMovingPreview(
            fontScale = 1f,
            layoutDirection = LayoutDirection.Ltr,
            previewHeight = 400,
        )

    /** A preview taller than the frame can spare still leaves the whole menu outline and a row inside it (#4100). */
    @Test
    fun previewTallerThanTheFrameKeepsTheMenuWhole() =
        assertActionsScrollWithoutMovingPreview(
            fontScale = 1f,
            layoutDirection = LayoutDirection.Ltr,
            previewHeight = 700,
        )

    /** The real text preview can fill five 200%-scale lines plus a reply and footer. */
    @Test
    fun longTextPreviewAtLargeFontKeepsDeleteReachable() {
        var deletes = 0
        showLongTextMenu(onDelete = { deletes++ })
        composeRule.waitForIdle()

        val preview = composeRule.onNodeWithTag("message-actions-preview").fetchSemanticsNode().boundsInRoot
        assertPreviewWithinFrame(preview, "the bounded text preview must fit the full-height frame")
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

    /** Checks doubled text size leaves media artwork and its footer visible inside the preview frame. */
    @Test
    fun tallMediaPreviewAtLargeFontKeepsArtworkAndFooterInsideFullHeightFrame() {
        showLongTextMenu(onDelete = {}, preview = { tallMediaPreview() })
        composeRule.waitForIdle()

        val preview = composeRule.onNodeWithTag("message-actions-preview").fetchSemanticsNode().boundsInRoot
        assertPreviewWithinFrame(preview, "the media preview must fit the full-height frame")
        val artwork = composeRule.onNodeWithTag("focused-test-artwork", useUnmergedTree = true)
        val footer = composeRule.onNodeWithText("12:34", useUnmergedTree = true)
        artwork.assertIsDisplayed()
        footer.assertIsDisplayed()
        with(composeRule.density) {
            val artworkBounds = artwork.getUnclippedBoundsInRoot()
            val footerBounds = footer.getUnclippedBoundsInRoot()
            assertTrue("some artwork must remain visible", artworkBounds.top.toPx() >= preview.top)
            assertTrue("artwork must not cover the footer", artworkBounds.bottom.toPx() <= footerBounds.top.toPx())
            assertTrue("footer must fit the preview", footerBounds.bottom.toPx() <= preview.bottom)
        }
    }

    /** Checks keyboard animation hides destructive targets and preserves the stack position when revealed. */
    @Test
    fun steppedImeInsetsDoNotMoveTheLiftedStackInTheFullHeightFrame() {
        lateinit var popupView: View
        var deletes = 0
        showLongTextMenu(onDelete = { deletes++ }, preview = {
            popupView = LocalView.current
            longTextPreview()
        })
        composeRule.waitForIdle()
        composeRule.onNodeWithText("Delete").performScrollTo()
        val deleteBounds = composeRule.onNodeWithText("Delete").fetchSemanticsNode().boundsInRoot
        val original = composeRule.onNodeWithTag(MESSAGE_ACTION_MENU_TEST_TAG).fetchSemanticsNode().boundsInRoot
        for (bottom in listOf(320, 240, 120, 0, 120, 240, 320, 0)) {
            composeRule.runOnUiThread {
                val insets =
                    WindowInsetsCompat
                        .Builder()
                        .setInsets(WindowInsetsCompat.Type.ime(), Insets.of(0, 0, 0, bottom))
                        .setVisible(WindowInsetsCompat.Type.ime(), bottom > 0)
                        .build()
                ViewCompat.dispatchApplyWindowInsets(popupView.rootView, insets)
            }
            composeRule.waitForIdle()
            if (bottom > 0) {
                composeRule.onNodeWithText("Delete").assertDoesNotExist()
                val frame = composeRule.onNodeWithTag(FOCUSED_OVERLAY_FRAME_TEST_TAG)
                val frameBounds = frame.fetchSemanticsNode().boundsInRoot
                frame.performTouchInput {
                    click(Offset(deleteBounds.center.x - frameBounds.left, deleteBounds.center.y - frameBounds.top))
                }
                composeRule.runOnIdle { assertEquals("invisible destructive actions cannot execute", 0, deletes) }
            }
            if (bottom == 0) {
                assertEquals(
                    "IME animation must not reanchor the captured bubble",
                    original,
                    composeRule.onNodeWithTag(MESSAGE_ACTION_MENU_TEST_TAG).fetchSemanticsNode().boundsInRoot,
                )
            }
        }
    }

    /** Checks the overlay frame respects both side and tall top display cutouts. */
    @Test
    fun menuAvoidsASideCutoutAndACutoutTallerThanTheStatusBar() {
        lateinit var popupView: View
        showLongTextMenu(onDelete = {}, preview = {
            popupView = LocalView.current
            longTextPreview()
        })
        composeRule.waitForIdle()
        composeRule.runOnUiThread {
            val insets =
                WindowInsetsCompat
                    .Builder()
                    .setInsets(WindowInsetsCompat.Type.statusBars(), Insets.of(0, 24, 0, 0))
                    .setVisible(WindowInsetsCompat.Type.statusBars(), true)
                    .setInsets(WindowInsetsCompat.Type.displayCutout(), Insets.of(44, 80, 0, 0))
                    .build()
            ViewCompat.dispatchApplyWindowInsets(popupView.rootView, insets)
        }
        composeRule.waitForIdle()
        val frame = composeRule.onNodeWithTag(FOCUSED_OVERLAY_FRAME_TEST_TAG).fetchSemanticsNode().boundsInRoot
        assertTrue("side cutout stays outside the frame", frame.left >= 44f)
        assertTrue("tall cutout stays outside the frame", frame.top >= 80f)
    }

    /** Checks a persistently visible keyboard dismisses the hidden menu exactly once after the deadline. */
    @Test
    fun refusedKeyboardHideDismissesTheHiddenMenuWithinABoundedTime() {
        lateinit var popupView: View
        var dismissals = 0
        showLongTextMenu(onDelete = {}, onDismiss = { dismissals++ }, preview = {
            popupView = LocalView.current
            longTextPreview()
        })
        composeRule.waitForIdle()
        composeRule.mainClock.autoAdvance = false
        composeRule.runOnUiThread {
            val insets =
                WindowInsetsCompat
                    .Builder()
                    .setInsets(WindowInsetsCompat.Type.ime(), Insets.of(0, 0, 0, 320))
                    .setVisible(WindowInsetsCompat.Type.ime(), true)
                    .build()
            ViewCompat.dispatchApplyWindowInsets(popupView.rootView, insets)
        }
        composeRule.mainClock.advanceTimeByFrame()
        composeRule.waitForIdle()
        composeRule.onNodeWithText("Delete").assertDoesNotExist()
        composeRule.mainClock.advanceTimeBy(1_000)
        composeRule.runOnIdle { assertEquals(0, dismissals) }
        composeRule.mainClock.advanceTimeBy(600)
        composeRule.runOnIdle { assertEquals(1, dismissals) }
        composeRule.mainClock.advanceTimeBy(2_000)
        composeRule.runOnIdle { assertEquals(1, dismissals) }
    }

    /** Checks closing the keyboard cancels pending timeout dismissal and reveals actions. */
    @Test
    fun closingTheImeCancelsTheRefusedHideTimeout() {
        lateinit var popupView: View
        var dismissals = 0
        showLongTextMenu(onDelete = {}, onDismiss = { dismissals++ }, preview = {
            popupView = LocalView.current
            longTextPreview()
        })
        composeRule.waitForIdle()
        composeRule.mainClock.autoAdvance = false
        dispatchImeInsets(popupView, 320)
        composeRule.mainClock.advanceTimeByFrame()
        composeRule.mainClock.advanceTimeBy(1_000)
        composeRule.runOnIdle { assertEquals(0, dismissals) }
        dispatchImeInsets(popupView, 0)
        composeRule.mainClock.advanceTimeByFrame()
        composeRule.mainClock.advanceTimeBy(2_000)
        composeRule.runOnIdle { assertEquals(0, dismissals) }
        composeRule.onNodeWithText("Delete").assertExists()
    }

    /** Applies keyboard visibility and bottom inset values to the supplied root view. */
    private fun dispatchImeInsets(
        view: View,
        bottom: Int,
    ) {
        composeRule.runOnUiThread {
            val insets =
                WindowInsetsCompat
                    .Builder()
                    .setInsets(WindowInsetsCompat.Type.ime(), Insets.of(0, 0, 0, bottom))
                    .setVisible(WindowInsetsCompat.Type.ime(), bottom > 0)
                    .build()
            ViewCompat.dispatchApplyWindowInsets(view.rootView, insets)
        }
    }

    /** Mounts the full action set at doubled text size with configurable preview and callbacks. */
    private fun showLongTextMenu(
        onDelete: () -> Unit,
        onDismiss: () -> Unit = {},
        preview: @Composable () -> Unit = { longTextPreview() },
    ) {
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
                    onDismissRequest = onDismiss,
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
                    preview = preview,
                )
            }
        }
        // Delete, the destructive canary these checks follow, waits behind More actions (#4077).
        composeRule.onNodeWithText("More actions").performScrollTo().performClick()
        composeRule.waitForIdle()
    }

    /** Renders the production text preview with a long excerpt, reply, and timestamp. */
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

    /** Renders a captioned tall media fixture with a reply and timestamp. */
    @Composable
    private fun tallMediaPreview() {
        FocusedTextMessagePreview(
            presentation = messageBubblePresentation(deleted = false, mine = false),
            mine = false,
            text = "A media caption with a few words",
            document = null,
            time = "12:34",
            status = MessageStatus.Received,
            showStatus = false,
            reply = { Text("Quoted reply with two lines of context") },
            media = {
                Box(
                    Modifier
                        .size(200.dp, 400.dp)
                        .background(MaterialTheme.colorScheme.primary)
                        .testTag("focused-test-artwork"),
                )
            },
        )
    }

    /** Checks preview bounds stay fixed while scrolling to and invoking the final action. */
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
        assertRowsScrollInsideTheMenuSurface(minimumRows = if (previewHeight > 60) 1 else 0)
        if (previewHeight > 60) {
            val actionViewport =
                composeRule.onNodeWithTag(FOCUSED_ACTION_MENU_SCROLL_TEST_TAG).fetchSemanticsNode().boundsInRoot
            assertTrue("tall media must leave a tappable action viewport", actionViewport.height >= 48f)
        }

        composeRule
            .onNodeWithText("Action 11")
            .performScrollTo()
            .assertIsDisplayed()

        val previewAfter = composeRule.onNodeWithTag("message-actions-preview").fetchSemanticsNode().boundsInRoot
        assertEquals("scrolling actions must not move the lifted message", previewBefore.top, previewAfter.top, 0.5f)
        assertPreviewWithinFrame(previewAfter, "the lifted message must remain fully visible")
        composeRule.onNodeWithText("Action 11").performClick()
        composeRule.runOnIdle { assertEquals("the final action must remain reachable", 1, lastActionClicks) }
        composeRule.onNodeWithTag("message-actions-preview").assertDoesNotExist()
    }

    /**
     * Only the rows scroll, inside the menu's rounded surface: no scroll or clip sits between the stack and the
     * surface, so the surface's outline and its shadow allowance fit the frame (#4100). Screenshots cannot show
     * this, because the baseline renderer draws no shadows inside popups.
     */
    private fun assertRowsScrollInsideTheMenuSurface(minimumRows: Int) {
        val panel = composeRule.onNodeWithTag(FOCUSED_ACTION_MENU_SCROLL_TEST_TAG)
        panel.assert(!hasScrollAction())
        composeRule
            .onNode(hasScrollAction() and hasAnyAncestor(hasTestTag(FOCUSED_ACTION_MENU_SCROLL_TEST_TAG)))
            .assertExists("the action rows must scroll inside the menu surface")
        val panelBounds = panel.fetchSemanticsNode().boundsInRoot
        val frame = composeRule.onNodeWithTag(FOCUSED_OVERLAY_FRAME_TEST_TAG).fetchSemanticsNode().boundsInRoot
        val shadowAllowance = with(composeRule.density) { 8.dp.toPx() }
        assertTrue(
            "the menu and its shadow must end above the frame's bottom edge",
            panelBounds.bottom + shadowAllowance <= frame.bottom + 0.5f,
        )
        val rowHeight = with(composeRule.density) { 48.dp.toPx() }
        assertTrue("the menu must keep $minimumRows whole row(s)", panelBounds.height >= rowHeight * minimumRows)
    }

    /** Checks all preview edges remain inside the overlay frame within rounding tolerance. */
    private fun assertPreviewWithinFrame(
        bounds: Rect,
        message: String,
    ) {
        val frame = composeRule.onNodeWithTag(FOCUSED_OVERLAY_FRAME_TEST_TAG).fetchSemanticsNode().boundsInRoot
        assertTrue(
            message,
            bounds.top >= frame.top - 1f &&
                bounds.bottom <= frame.bottom + 1f &&
                bounds.left >= frame.left - 1f &&
                bounds.right <= frame.right + 1f,
        )
    }

    private companion object {
        val LONG_TEXT_PREVIEW = (1..8).joinToString(" ") { "This sentence fills the lifted message preview." }
    }
}
