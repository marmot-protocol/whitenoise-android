package dev.ipf.whitenoise.android.ui.conversation.messages

import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.window.PopupPositionProvider
import kotlin.math.roundToInt

internal class MessageActionMenuPositionProvider(
    private val anchorBoundsInWindow: IntRect?,
    private val anchorWindowYPx: Float?,
    private val alignEnd: Boolean,
    private val edgeInsetPx: Int,
    private val anchorGapPx: Int,
    private val estimatedOneColumnHeightPx: Int,
    private val estimatedTwoColumnHeightPx: Int,
    private val minimumActionCellWidthPx: Int,
    private val maximumActionContentWidthPx: Int,
    private val actionContentPaddingPx: Int,
    private val actionColumnGapPx: Int,
) : PopupPositionProvider {
    override fun calculatePosition(
        anchorBounds: IntRect,
        windowSize: IntSize,
        layoutDirection: LayoutDirection,
        popupContentSize: IntSize,
    ): IntOffset {
        // The popup is constrained to the matching deterministic width/height
        // in MessageActionMenu. Do not derive placement from its transient
        // reported dimensions; first and measured callbacks must be identical.
        val estimatedPopupWidth = estimatedPopupWidth(windowSize.width)
        val estimatedContentWidth = (estimatedPopupWidth - actionContentPaddingPx).coerceAtLeast(0)
        val effectiveHeight = effectiveHeight(estimatedContentWidth)
        val x = horizontalPosition(windowSize.width, estimatedPopupWidth, layoutDirection)
        val y = verticalPosition(windowSize.height, effectiveHeight)
        return IntOffset(x, y)
    }

    private fun estimatedPopupWidth(windowWidth: Int): Int =
        minOf(
            maximumActionContentWidthPx + actionContentPaddingPx,
            (windowWidth - edgeInsetPx * 2).coerceAtLeast(0),
        )

    private fun effectiveHeight(estimatedContentWidth: Int): Int =
        if (estimatedContentWidth >= minimumActionCellWidthPx * 2 + actionColumnGapPx) {
            estimatedTwoColumnHeightPx
        } else {
            estimatedOneColumnHeightPx
        }

    private fun horizontalPosition(
        windowWidth: Int,
        popupWidth: Int,
        layoutDirection: LayoutDirection,
    ): Int {
        val alignRight = if (layoutDirection == LayoutDirection.Ltr) alignEnd else !alignEnd
        val desiredX =
            anchorBoundsInWindow?.let { bounds ->
                if (alignRight) bounds.right - popupWidth else bounds.left
            } ?: if (alignRight) {
                windowWidth - popupWidth - edgeInsetPx
            } else {
                edgeInsetPx
            }
        return desiredX.coerceIn(
            edgeInsetPx,
            (windowWidth - popupWidth - edgeInsetPx).coerceAtLeast(edgeInsetPx),
        )
    }

    private fun verticalPosition(
        windowHeight: Int,
        popupHeight: Int,
    ): Int {
        val touchY = anchorWindowYPx?.roundToInt() ?: anchorBoundsInWindow?.center?.y ?: (windowHeight / 2)
        val bottomLimit = windowHeight - edgeInsetPx
        val preferredY =
            anchorBoundsInWindow?.let { bounds ->
                bubbleRelativeY(bounds, popupHeight, touchY, bottomLimit)
            } ?: touchRelativeY(popupHeight, touchY, bottomLimit)
        return preferredY.coerceIn(
            edgeInsetPx,
            (windowHeight - popupHeight - edgeInsetPx).coerceAtLeast(edgeInsetPx),
        )
    }

    private fun bubbleRelativeY(
        bounds: IntRect,
        popupHeight: Int,
        touchY: Int,
        bottomLimit: Int,
    ): Int {
        val below = bounds.bottom + anchorGapPx
        val above = bounds.top - anchorGapPx - popupHeight
        return when {
            below + popupHeight <= bottomLimit -> below
            above >= edgeInsetPx -> above
            else -> touchY - popupHeight / 2
        }
    }

    private fun touchRelativeY(
        popupHeight: Int,
        touchY: Int,
        bottomLimit: Int,
    ): Int =
        when {
            touchY + popupHeight <= bottomLimit -> touchY
            popupHeight <= touchY - edgeInsetPx -> touchY - popupHeight
            else -> edgeInsetPx
        }
}
