package dev.ipf.whitenoise.android.ui.conversation.messages

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
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
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "w360dp-h780dp-mdpi")
class MessageActionMenuLayoutTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun actionModelPreservesCapabilityOrder() {
        assertEquals(
            listOf(
                MessageActionKind.Reply,
                MessageActionKind.Edit,
                MessageActionKind.Select,
                MessageActionKind.SelectText,
                MessageActionKind.CopyText,
                MessageActionKind.Speak,
                MessageActionKind.Forward,
                MessageActionKind.Save,
                MessageActionKind.Info,
            ),
            messageActionKinds(
                canReply = true,
                canEdit = true,
                canSelect = true,
                canSelectText = true,
                canCopyText = true,
                canSpeak = true,
                canForward = true,
                canSave = true,
            ),
        )
    }

    @Test
    fun columnAndHeightEstimatesTrackGridRowsAndDeleteSection() {
        assertEquals(2, messageActionColumnCount(312.dp, 136.dp))
        assertEquals(1, messageActionColumnCount(260.dp, 136.dp))
        assertEquals(394.dp, estimatedMessageActionMenuHeight(9, 2, canReact = true, canDelete = true))
        assertEquals(594.dp, estimatedMessageActionMenuHeight(9, 1, canReact = true, canDelete = true))
    }

    @Test
    fun receivedAttachmentPlacementIsStableAcrossInitialAndMeasuredCallbacksAtFlipBoundaries() {
        listOf(true, false).forEach { canSave ->
            // Exact incoming APK/file capabilities: the filename display copy
            // is copyable, but the synthetic media fallback is not editable,
            // selectable as text, speakable, or forwardable as a text message.
            val actionKinds =
                messageActionKinds(
                    canReply = true,
                    canEdit = false,
                    canSelect = true,
                    canSelectText = false,
                    canCopyText = true,
                    canSpeak = false,
                    canForward = false,
                    canSave = canSave,
                )
            assertEquals(
                buildList {
                    add(MessageActionKind.Reply)
                    add(MessageActionKind.Select)
                    add(MessageActionKind.CopyText)
                    if (canSave) add(MessageActionKind.Save)
                    add(MessageActionKind.Info)
                },
                actionKinds,
            )
            val actionCount = actionKinds.size
            val twoColumnHeight = estimatedHeightPx(actionCount, 2, canReact = true, canDelete = false)
            val belowBoundary = 780 - 8 - twoColumnHeight

            listOf(belowBoundary, belowBoundary + 1).forEach { touchY ->
                val provider = positionProvider(touchY = touchY, actionCount = actionCount)
                val initial = provider.position(window = IntSize(360, 780), popup = IntSize.Zero)
                val measured = provider.position(window = IntSize(360, 780), popup = IntSize(328, twoColumnHeight))

                assertEquals("canSave=$canSave touchY=$touchY", initial.y, measured.y)
                assertEquals(
                    if (touchY == belowBoundary) touchY else touchY - twoColumnHeight,
                    initial.y,
                )
            }
        }
    }

    @Test
    fun placementSideAndYStayStableAcrossActionLayoutAndImeVariants() {
        val actionCount = 5
        val capabilities =
            listOf(
                false to false,
                true to false,
                false to true,
                true to true,
            )
        val windows = listOf(IntSize(360, 780), IntSize(260, 780), IntSize(360, 320))

        capabilities.forEach { (canReact, canDelete) ->
            windows.forEach { window ->
                listOf(false, true).forEach { largeFont ->
                    val rowHeight = if (largeFont) 72 else 48
                    val reactionHeight = if (largeFont) 64 else 48
                    val minimumCellWidth = if (largeFont) 180 else 136
                    val provider =
                        positionProvider(
                            touchY = window.height / 2,
                            actionCount = actionCount,
                            canReact = canReact,
                            canDelete = canDelete,
                            rowHeight = rowHeight,
                            reactionHeight = reactionHeight,
                            minimumCellWidth = minimumCellWidth,
                        )
                    val estimatedContentWidth = minOf(312, window.width - 16)
                    val columns = if (estimatedContentWidth >= minimumCellWidth * 2 + 2) 2 else 1
                    val estimatedHeight =
                        estimatedHeightPx(
                            actionCount,
                            columns,
                            canReact,
                            canDelete,
                            rowHeight,
                            reactionHeight,
                        )
                    val measuredHeight = minOf(estimatedHeight, window.height - 16)
                    val initial = provider.position(window, IntSize.Zero)
                    val measured = provider.position(window, IntSize(minOf(328, window.width), measuredHeight))

                    assertEquals(
                        "react=$canReact delete=$canDelete window=$window largeFont=$largeFont",
                        initial.y,
                        measured.y,
                    )
                }
            }
        }
    }

    @Test
    fun maximumMenuUsesRowMajorTwoColumnGridAndFullWidthDelete() {
        renderMenu(fontScale = 1f)

        val reply = bounds("Reply")
        val edit = bounds("Edit")
        val select = bounds("Select")
        val selectText = bounds("Select text")
        val delete = bounds("Delete")

        assertEquals(reply.top, edit.top, 0.5f)
        assertTrue(reply.left < edit.left)
        assertEquals(select.top, selectText.top, 0.5f)
        assertTrue(select.top > reply.top)
        assertTrue(delete.width > reply.width * 1.8f)
        assertTrue(delete.top > bounds("Message info").bottom)
    }

    @Test
    fun largeFontFallsBackToOneReadableColumn() {
        renderMenu(fontScale = 2f)

        assertTrue(bounds("Edit").top > bounds("Reply").top)
        assertTrue(bounds("Select text").top > bounds("Select").top)
        composeRule.onNodeWithText("Delete", substring = false).performScrollTo().assertIsDisplayed()
    }

    @Test
    fun rtlKeepsRowMajorOrderFromTheStartEdge() {
        renderMenu(fontScale = 1f, layoutDirection = LayoutDirection.Rtl)

        assertEquals(bounds("Reply").top, bounds("Edit").top, 0.5f)
        assertTrue(bounds("Reply").left > bounds("Edit").left)
    }

    @Test
    fun everyMaximumVariantActionInvokesItsOriginalCallback() {
        val callbacks = mutableListOf<String>()
        renderMenu(fontScale = 1f, callbacks = callbacks)

        listOf(
            "Reply",
            "Edit",
            "Select",
            "Select text",
            "Copy text",
            "Speak aloud",
            "Forward",
            "Save",
            "Message info",
            "Delete",
        ).forEach { composeRule.onNodeWithText(it, substring = false).performClick() }

        assertEquals(
            listOf("reply", "edit", "select", "selectText", "copy", "speak", "forward", "save", "info", "delete"),
            callbacks,
        )
    }

    @Test
    fun emojiPickerKeepsMinimumTouchTarget() {
        renderMenu(fontScale = 1f, canReact = true)

        val bounds =
            composeRule
                .onNodeWithContentDescription("Open emoji picker")
                .fetchSemanticsNode()
                .boundsInRoot
        assertTrue(bounds.width >= 48f)
        assertTrue(bounds.height >= 48f)
    }

    private fun renderMenu(
        fontScale: Float,
        layoutDirection: LayoutDirection = LayoutDirection.Ltr,
        callbacks: MutableList<String> = mutableListOf(),
        canReact: Boolean = false,
    ) {
        composeRule.setContent {
            WhiteNoiseTheme {
                val density = LocalDensity.current
                CompositionLocalProvider(
                    LocalDensity provides Density(density.density, fontScale),
                    LocalLayoutDirection provides layoutDirection,
                ) {
                    MessageActionMenu(
                        expanded = true,
                        anchorWindowYPx = 8f,
                        alignEnd = false,
                        canReply = true,
                        canReact = canReact,
                        canDelete = true,
                        canEdit = true,
                        canForward = true,
                        canSelect = true,
                        canCopyText = true,
                        canSpeak = true,
                        canSelectText = true,
                        canSave = true,
                        quickReactionEmojis = if (canReact) listOf("👍") else emptyList(),
                        onDismissRequest = {},
                        onReact = {},
                        onOpenEmojiPicker = {},
                        onReply = { callbacks += "reply" },
                        onEdit = { callbacks += "edit" },
                        onForward = { callbacks += "forward" },
                        onSelect = { callbacks += "select" },
                        onSelectText = { callbacks += "selectText" },
                        onCopyText = { callbacks += "copy" },
                        onSpeak = { callbacks += "speak" },
                        onSave = { callbacks += "save" },
                        onInfo = { callbacks += "info" },
                        onDelete = { callbacks += "delete" },
                    )
                }
            }
        }
        composeRule.waitForIdle()
    }

    private fun bounds(label: String) =
        composeRule
            .onNodeWithText(label, substring = false)
            .fetchSemanticsNode()
            .boundsInRoot

    private fun positionProvider(
        touchY: Int,
        actionCount: Int,
        canReact: Boolean = true,
        canDelete: Boolean = false,
        rowHeight: Int = 48,
        reactionHeight: Int = 48,
        minimumCellWidth: Int = 136,
    ) = MessageActionMenuPositionProvider(
        anchorWindowYPx = touchY.toFloat(),
        alignEnd = false,
        edgeInsetPx = 8,
        estimatedOneColumnHeightPx =
            estimatedHeightPx(actionCount, 1, canReact, canDelete, rowHeight, reactionHeight),
        estimatedTwoColumnHeightPx =
            estimatedHeightPx(actionCount, 2, canReact, canDelete, rowHeight, reactionHeight),
        minimumActionCellWidthPx = minimumCellWidth,
        maximumActionContentWidthPx = 312,
        actionContentPaddingPx = 16,
        actionColumnGapPx = 2,
    )

    private fun MessageActionMenuPositionProvider.position(
        window: IntSize,
        popup: IntSize,
    ) = calculatePosition(IntRect.Zero, window, LayoutDirection.Ltr, popup)

    private fun estimatedHeightPx(
        actionCount: Int,
        columns: Int,
        canReact: Boolean,
        canDelete: Boolean,
        rowHeight: Int = 48,
        reactionHeight: Int = 48,
    ): Int =
        estimatedMessageActionMenuHeight(
            actionCount = actionCount,
            columns = columns,
            canReact = canReact,
            canDelete = canDelete,
            actionRowHeight = rowHeight.dp,
            reactionRowHeight = reactionHeight.dp,
        ).value.toInt()
}
