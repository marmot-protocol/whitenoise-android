package dev.ipf.whitenoise.android.core

import dev.ipf.marmotkit.AppMessageRecordFfi
import dev.ipf.marmotkit.TimelineMessageRecordFfi

object ReplyNavigation {
    const val MaxOlderPages = 20

    fun centeredScrollOffset(
        viewportHeightPx: Int,
        itemHeightPx: Int? = null,
    ): Int {
        if (viewportHeightPx <= 0) return 0
        val itemHeight = itemHeightPx?.coerceAtLeast(0) ?: 0
        return -((viewportHeightPx - itemHeight).coerceAtLeast(0) / 2)
    }

    fun targetMessageId(
        record: AppMessageRecordFfi,
        projected: TimelineMessageRecordFfi?,
    ): String? =
        projected
            ?.replyPreview
            ?.messageIdHex
            ?.takeIf { it.isNotBlank() }
            ?: projected
                ?.replyToMessageIdHex
                ?.takeIf { it.isNotBlank() }
            ?: MessageProjector.replyTargetMessageId(record)

    fun shouldLoadOlder(
        targetLoaded: Boolean,
        hasMoreBefore: Boolean,
        loadedPageCount: Int,
        maxOlderPages: Int = MaxOlderPages,
    ): Boolean = !targetLoaded && hasMoreBefore && loadedPageCount < maxOlderPages
}
