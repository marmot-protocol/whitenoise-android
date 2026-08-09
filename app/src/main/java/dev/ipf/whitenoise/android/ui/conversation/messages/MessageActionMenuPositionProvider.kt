package dev.ipf.whitenoise.android.ui.conversation.messages

import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.window.PopupPositionProvider
import kotlin.math.roundToInt

internal class MessageActionMenuPositionProvider(
    private val anchorWindowYPx: Float?,
    private val alignEnd: Boolean,
    private val edgeInsetPx: Int,
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
        val touchY = anchorWindowYPx?.roundToInt() ?: (windowSize.height / 2)
        val x =
            if (alignEnd) {
                windowSize.width - popupContentSize.width - edgeInsetPx
            } else {
                edgeInsetPx
            }.coerceIn(0, (windowSize.width - popupContentSize.width).coerceAtLeast(0))
        val estimatedContentWidth =
            minOf(
                maximumActionContentWidthPx,
                (windowSize.width - actionContentPaddingPx).coerceAtLeast(0),
            )
        val effectiveHeight =
            if (estimatedContentWidth >= minimumActionCellWidthPx * 2 + actionColumnGapPx) {
                estimatedTwoColumnHeightPx
            } else {
                estimatedOneColumnHeightPx
            }
        val bottomLimit = windowSize.height - edgeInsetPx
        val y =
            when {
                touchY + effectiveHeight <= bottomLimit -> touchY
                effectiveHeight <= touchY - edgeInsetPx -> touchY - effectiveHeight
                else -> edgeInsetPx
            }.coerceIn(
                edgeInsetPx,
                (windowSize.height - effectiveHeight).coerceAtLeast(edgeInsetPx),
            )
        return IntOffset(x, y)
    }
}
