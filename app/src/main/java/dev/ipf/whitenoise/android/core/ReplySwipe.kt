package dev.ipf.whitenoise.android.core

import kotlin.math.abs

object ReplySwipe {
    fun shouldTriggerReply(
        totalX: Float,
        totalY: Float,
        threshold: Float,
    ): Boolean = totalX >= threshold && isMostlyHorizontalRightward(totalX = totalX, totalY = totalY)

    fun isMostlyHorizontalRightward(
        totalX: Float,
        totalY: Float,
    ): Boolean = totalX > 0f && totalX > abs(totalY) * 1.2f

    fun visualOffset(
        totalX: Float,
        maxOffset: Float,
    ): Float = totalX.coerceIn(0f, maxOffset)
}

data class ReplySwipeGesture(
    val totalX: Float = 0f,
    val totalY: Float = 0f,
) {
    fun dragBy(
        deltaX: Float,
        deltaY: Float,
    ): ReplySwipeGesture = copy(totalX = totalX + deltaX, totalY = totalY + deltaY)

    fun visualOffset(maxOffset: Float): Float =
        if (ReplySwipe.isMostlyHorizontalRightward(totalX = totalX, totalY = totalY)) {
            ReplySwipe.visualOffset(totalX = totalX, maxOffset = maxOffset)
        } else {
            0f
        }

    fun shouldTriggerReply(threshold: Float): Boolean = ReplySwipe.shouldTriggerReply(totalX, totalY, threshold)
}
