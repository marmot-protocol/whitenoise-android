package dev.ipf.whitenoise.android.state

import dev.ipf.marmotkit.TimelineMessageQueryFfi

/**
 * One group's timeline page, newest first unless a cursor is given. The sender, time range, attachment-type and
 * cross-chat cursor filters MarmotKit also accepts stay unset, so every caller keeps the unfiltered page it asks for.
 */
internal fun TimelineMessageQueryFfi.Companion.page(
    groupIdHex: String?,
    search: String? = null,
    before: ULong? = null,
    beforeMessageId: String? = null,
    after: ULong? = null,
    afterMessageId: String? = null,
    limit: UInt?,
) = TimelineMessageQueryFfi(
    groupIdHex = groupIdHex,
    search = search,
    sender = null,
    since = null,
    until = null,
    cursorGroupId = null,
    before = before,
    beforeMessageId = beforeMessageId,
    after = after,
    afterMessageId = afterMessageId,
    limit = limit,
)
