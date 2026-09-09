package dev.ipf.whitenoise.android.state

import dev.ipf.marmotkit.AppGroupMemberRecordFfi
import dev.ipf.marmotkit.MarkdownDocumentFfi
import dev.ipf.marmotkit.NotificationUpdateFfi
import dev.ipf.marmotkit.TimelineMessageRecordFfi
import dev.ipf.whitenoise.android.core.ReplyMediaKind

/** Live reads from the current app-state owner; this adapter stores no protocol data. */
@Suppress("TooManyFunctions") // One bounded read adapter avoids a callback class per cold-path dependency.
internal interface NotificationContentSource {
    /** Reads the account-scoped local contact alias. */
    fun contactNickname(
        accountRef: String?,
        accountIdHex: String,
    ): String?

    /** Reads the persisted display name through the existing off-main binding. */
    suspend fun readDisplayName(accountIdHex: String): String?

    /** Reads the current notification payload hint. */
    fun displayNameHint(accountIdHex: String): String?

    /** Returns the existing shortened identity fallback. */
    fun cachedShortNpub(accountIdHex: String): String

    /** Reads already hydrated profile presentation under its owner's lock. */
    fun hydratedDisplayName(accountIdHex: String): String?

    /** Schedules profile hydration only when the caller permits remote work. */
    fun requestProfile(accountIdHex: String)

    /** Decodes an identity through the existing binding boundary. */
    suspend fun accountIdHex(bech32: String): String?

    /** Parses message content through the canonical Markdown binding. */
    suspend fun parseMarkdown(raw: String): MarkdownDocumentFfi

    /** Finds the recipient identity from the owner's current accounts. */
    fun recipientAccountIdHex(ref: String): String?

    /** Reads the exact stored timeline record for this account and update. */
    suspend fun timelineRecord(update: NotificationUpdateFfi): TimelineMessageRecordFfi?

    /** Reads authoritative group membership for this account and conversation. */
    suspend fun groupMembers(update: NotificationUpdateFfi): List<AppGroupMemberRecordFfi>

    /** Classifies media from the exact stored message. */
    suspend fun mediaKind(update: NotificationUpdateFfi): ReplyMediaKind

    /** Counts current signing accounts when recipient subtext is being resolved. */
    fun signedInAccountCount(): Int
}
