package dev.ipf.whitenoise.android.state

import dev.ipf.marmotkit.TimelineMessageQueryFfi

/**
 * One group's timeline page, newest first unless a cursor is given. The sender, time range, attachment-type and
 * cross-chat cursor filters MarmotKit also accepts stay unset, so every caller keeps the unfiltered page it asks for.
 */
internal fun TimelineMessageQueryFfi.Companion.page(
    groupIdHex: String?,
    search: String?,
    before: ULong?,
    beforeMessageId: String?,
    after: ULong?,
    afterMessageId: String?,
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

/**
 * One page of MDK's account-wide message search (#1944), newest first by wall clock. [groupIdHex] is null to search
 * every chat, [sender] narrows to one exact account, [since] is inclusive and [until] exclusive on the timeline
 * time, and [attachmentType] uses MDK's code (0 any, 1 image, 2 video, 3 audio, 4 file, 5 any attachment). The
 * cursor is the oldest row of the previous page: its time, message id and group, all three or none.
 */
@Suppress("LongParameterList") // Mirrors MDK's typed search filters one to one.
internal fun TimelineMessageQueryFfi.Companion.searchPage(
    groupIdHex: String?,
    search: String?,
    sender: String?,
    since: ULong?,
    until: ULong?,
    attachmentType: UInt,
    cursor: Triple<ULong, String, String>?,
    limit: UInt,
) = TimelineMessageQueryFfi(
    groupIdHex = groupIdHex,
    search = search,
    sender = sender,
    since = since,
    until = until,
    attachmentType = attachmentType,
    searchWallClock = true,
    cursorGroupId = cursor?.third,
    before = cursor?.first,
    beforeMessageId = cursor?.second,
    after = null,
    afterMessageId = null,
    limit = limit,
)

private val ACCOUNT_ID_HEX = Regex("^[0-9a-f]{64}$")

/**
 * The one sender MDK may filter natively, or null to leave every sender to the host check: several senders, or a
 * single key that is not a canonical account id — a corrupted saved search must not fail a whole scan in MDK.
 */
internal fun nativeSenderFilter(senderIds: Set<String>?): String? {
    val single = senderIds?.singleOrNull()
    return single?.takeIf(ACCOUNT_ID_HEX::matches)
}
