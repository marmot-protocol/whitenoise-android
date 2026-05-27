package dev.ipf.darkmatter.core

import kotlin.math.abs

object ReplySwipe {
    fun shouldTriggerReply(totalX: Float, totalY: Float, threshold: Float): Boolean {
        return totalX >= threshold && totalX > abs(totalY) * 1.2f
    }

    fun visualOffset(totalX: Float, maxOffset: Float): Float {
        return totalX.coerceIn(0f, maxOffset)
    }
}
