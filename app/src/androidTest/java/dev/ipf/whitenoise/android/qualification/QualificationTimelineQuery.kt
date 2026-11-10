package dev.ipf.whitenoise.android.qualification

import dev.ipf.marmotkit.TimelineMessageQueryFfi

/**
 * The newest [limit] messages of [group] on the candidate runtime. The baseline build replaces this file with
 * scripts/mdk-baseline-overlay/QualificationTimelineQuery.kt.txt, since the query's fields differ between the two.
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
