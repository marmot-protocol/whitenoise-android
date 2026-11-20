package dev.ipf.whitenoise.android.ui.conversation.messages

/**
 * The offsets the lifted stack may occupy inside a frame [frameHeightPx] tall.
 *
 * A stack shorter than its frame travels between the frame's edges; one that fills the frame has
 * nowhere to go and keeps the scrolling it already had. Both are the same range, so there is no
 * pair of models that have to agree about where the stack is.
 */
internal fun focusedStackTravelRange(
    frameHeightPx: Int,
    stackHeightPx: Int,
): ClosedFloatingPointRange<Float> {
    val slack = (frameHeightPx - stackHeightPx).toFloat()
    return minOf(0f, slack)..maxOf(0f, slack)
}

/**
 * Where the stack rests before anyone moves it: centred on the message it lifted, held in the frame.
 *
 * [anchorCenterPx] is a window coordinate, because that is what the lifted bubble reports, while the
 * offset is applied inside a frame that starts below the top inset. [topInsetPx] converts between
 * them; without it the stack rests a status bar too low.
 *
 * The stack is placed by its preview, not by its own middle: a short reaction rail sits above the
 * lifted message and a tall menu below it, so centring the stack would put the lifted message well
 * above the bubble it came from and the message would appear to jump as the overlay opened.
 * [previewCenterInStackPx] defaults to the stack's middle for a lift that draws no preview.
 *
 * A null [anchorCenterPx] means the lift reported no bubble and no touch point, so the stack has
 * nothing to sit beside and centres on the frame instead.
 */
internal fun focusedStackRestingOffset(
    frameHeightPx: Int,
    stackHeightPx: Int,
    anchorCenterPx: Int?,
    topInsetPx: Int = 0,
    previewCenterInStackPx: Int = stackHeightPx / 2,
): Float {
    val range = focusedStackTravelRange(frameHeightPx, stackHeightPx)
    val centre = anchorCenterPx?.minus(topInsetPx) ?: (frameHeightPx / 2)
    return (centre - previewCenterInStackPx).toFloat().coerceIn(range.start, range.endInclusive)
}
