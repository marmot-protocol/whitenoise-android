package dev.ipf.darkmatter.core

import dev.ipf.marmotkit.AppMessageRecordFfi
import dev.ipf.marmotkit.TimelineMessageRecordFfi

object ReplyNavigation {
    const val MaxOlderPages = 20

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
