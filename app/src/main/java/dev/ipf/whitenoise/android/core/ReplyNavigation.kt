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

    // Pick the best available height for the target row before the animated
    // scroll starts (#999). A live visible measurement is freshest; a cached
    // per-message measurement preserves exact centering for off-screen rows
    // already seen during this composition. The median is only a last-resort
    // fallback for never-measured rows.
    fun itemHeightForScrollPx(
        targetMessageId: String,
        measuredItemHeightsByMessageId: Map<String, Int>,
        visibleTargetHeightPx: Int?,
        visibleTimelineItemHeightsPx: List<Int>,
    ): Int? =
        visibleTargetHeightPx?.takeIf { it > 0 }
            ?: measuredItemHeightsByMessageId[targetMessageId]?.takeIf { it > 0 }
            ?: estimateItemHeightPx(visibleTimelineItemHeightsPx)

    // Estimate a target row's height from currently-visible timeline rows when
    // no exact measurement is available. The median resists outliers like a
    // tall media bubble. Returns null when there's nothing to sample, which
    // leaves centeredScrollOffset to center on the row's top edge.
    fun estimateItemHeightPx(visibleItemHeightsPx: List<Int>): Int? {
        val heights = visibleItemHeightsPx.filter { it > 0 }.sorted()
        if (heights.isEmpty()) return null
        val mid = heights.size / 2
        return if (heights.size % 2 == 1) {
            heights[mid]
        } else {
            (heights[mid - 1] + heights[mid]) / 2
        }
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
