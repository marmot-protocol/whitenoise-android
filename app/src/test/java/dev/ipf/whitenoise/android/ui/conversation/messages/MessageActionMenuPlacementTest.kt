package dev.ipf.whitenoise.android.ui.conversation.messages

import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], qualifiers = "w360dp-h780dp-mdpi")
class MessageActionMenuPlacementTest {
    /** The model emits the prototype's action order, not the capability-argument order. */
    @Test
    fun actionModelPreservesCapabilityOrder() {
        assertEquals(
            listOf(
                // Leads so a two-column grid always puts it in the first row.
                MessageActionKind.KeepOnScreen,
                MessageActionKind.Edit,
                MessageActionKind.SelectText,
                MessageActionKind.Reply,
                MessageActionKind.Forward,
                MessageActionKind.Share,
                MessageActionKind.Save,
                MessageActionKind.CopyText,
                MessageActionKind.Speak,
                MessageActionKind.Select,
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
                canKeepOnScreen = true,
                canShare = true,
                canSave = true,
            ),
        )
    }

    /** Literal-code reading is a production-only action that trails Read Aloud. */
    @Test
    fun literalCodeReadingFollowsReadAloud() {
        val kinds =
            messageActionKinds(
                canReply = false,
                canEdit = false,
                canSelect = false,
                canSelectText = false,
                canCopyText = false,
                canSpeak = true,
                canSpeakCodeLiterally = true,
                canForward = false,
                canSave = false,
                canInfo = false,
            )

        assertEquals(listOf(MessageActionKind.Speak, MessageActionKind.SpeakCodeLiterally), kinds)
    }

    @Test
    fun actionModelCanSuppressInfoForDeleteOnlyTombstoneMenu() {
        assertEquals(
            emptyList<MessageActionKind>(),
            messageActionKinds(
                canReply = false,
                canEdit = false,
                canSelect = false,
                canSelectText = false,
                canCopyText = false,
                canSpeak = false,
                canForward = false,
                canSave = false,
                canInfo = false,
            ),
        )
    }

    @Test
    fun columnAndHeightEstimatesTrackGridRowsWithIntegratedDelete() {
        assertEquals(2, messageActionColumnCount(312.dp, 136.dp))
        assertEquals(1, messageActionColumnCount(260.dp, 136.dp))
        assertEquals(379.dp, estimatedMessageActionMenuHeight(10, 2, canReact = true, canDelete = true))
        assertEquals(629.dp, estimatedMessageActionMenuHeight(10, 1, canReact = true, canDelete = true))
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
                    canShare = true,
                    canSave = canSave,
                )
            assertEquals(
                buildList {
                    add(MessageActionKind.Reply)
                    add(MessageActionKind.Share)
                    if (canSave) add(MessageActionKind.Save)
                    add(MessageActionKind.CopyText)
                    add(MessageActionKind.Select)
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

                assertEquals("canSave=$canSave touchY=$touchY", initial, measured)
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
                        initial,
                        measured,
                    )
                }
            }
        }
    }

    @Test
    fun tallerReportedHeightDoesNotMoveTheClippedMenu() {
        val provider =
            MessageActionMenuPositionProvider(
                anchorBoundsInWindow = null,
                anchorWindowYPx = 450f,
                centerOverAnchor = false,
                edgeInsetPx = 8,
                anchorGapPx = 8,
                estimatedOneColumnHeightPx = 200,
                estimatedTwoColumnHeightPx = 100,
                minimumActionCellWidthPx = 136,
                maximumActionContentWidthPx = 312,
                actionContentPaddingPx = 16,
                actionColumnGapPx = 2,
            )
        val window = IntSize(360, 500)

        val initial = provider.position(window, IntSize.Zero)
        val measured = provider.position(window, IntSize(328, 180))

        assertEquals(350, initial.y)
        assertEquals(initial, measured)
    }

    @Test
    fun textMessageMenuCentersBelowIncomingBubble() {
        val provider =
            positionProvider(
                touchY = 450,
                actionCount = 5,
                anchorBounds = IntRect(160, 400, 340, 460),
            )

        val position = provider.position(window = IntSize(500, 780), popup = IntSize(328, 180))

        assertEquals(86, position.x)
        assertEquals(468, position.y)
    }

    @Test
    fun reactionMenuCentersOnOutgoingImageBubble() {
        val provider =
            positionProvider(
                touchY = 520,
                actionCount = 5,
                centerOverAnchor = true,
                anchorBounds = IntRect(240, 400, 480, 650),
            )

        val position = provider.position(window = IntSize(720, 900), popup = IntSize(328, 180))

        assertEquals(196, position.x)
        assertEquals(411, position.y)
    }

    @Test
    fun centeredBubblePlacementIsDirectionIndependent() {
        val provider =
            positionProvider(
                touchY = 220,
                actionCount = 3,
                anchorBounds = IntRect(150, 300, 350, 360),
            )

        val ltr =
            provider.position(
                window = IntSize(500, 780),
                popup = IntSize(328, 180),
            )
        val rtl =
            provider.position(
                window = IntSize(500, 780),
                popup = IntSize(328, 180),
                layoutDirection = LayoutDirection.Rtl,
            )

        assertEquals(ltr, rtl)
        assertEquals(86, rtl.x)
    }

    @Test
    fun centeredMediaMenuOnlyClampsAtTheWindowEdge() {
        val provider =
            positionProvider(
                touchY = 30,
                actionCount = 3,
                centerOverAnchor = true,
                anchorBounds = IntRect(80, 0, 320, 60),
            )

        val position = provider.position(window = IntSize(400, 780), popup = IntSize(328, 180))

        assertEquals(36, position.x)
        assertEquals(8, position.y)
    }

    @Test
    fun textMessageMenuMovesAboveOnlyWhenBelowDoesNotFit() {
        val provider =
            positionProvider(
                touchY = 450,
                actionCount = 5,
                anchorBounds = IntRect(160, 400, 340, 460),
            )

        val position = provider.position(window = IntSize(500, 500), popup = IntSize(328, 180))

        assertEquals(86, position.x)
        assertEquals(163, position.y)
    }

    private fun positionProvider(
        touchY: Int,
        actionCount: Int,
        centerOverAnchor: Boolean = false,
        anchorBounds: IntRect? = null,
        canReact: Boolean = true,
        canDelete: Boolean = false,
        rowHeight: Int = 48,
        reactionHeight: Int = 48,
        minimumCellWidth: Int = 136,
    ) = MessageActionMenuPositionProvider(
        anchorBoundsInWindow = anchorBounds,
        anchorWindowYPx = touchY.toFloat(),
        centerOverAnchor = centerOverAnchor,
        edgeInsetPx = 8,
        anchorGapPx = 8,
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
        layoutDirection: LayoutDirection = LayoutDirection.Ltr,
    ) = calculatePosition(IntRect.Zero, window, layoutDirection, popup)

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
