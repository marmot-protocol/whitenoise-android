package dev.ipf.whitenoise.android.qualification

import dev.ipf.marmotkit.TimelineMessageQueryFfi

/**
 * The newest [limit] messages of [group]. The baseline and candidate runtimes share this query's fields, so both builds
 * compile this file unchanged.
 */
internal fun qualificationTimelineQuery(
    group: String,
    limit: UInt,
) = TimelineMessageQueryFfi(
    groupIdHex = group,
    search = null,
    sender = null,
    since = null,
    until = null,
    cursorGroupId = null,
    before = null,
    beforeMessageId = null,
    after = null,
    afterMessageId = null,
    limit = limit,
)
