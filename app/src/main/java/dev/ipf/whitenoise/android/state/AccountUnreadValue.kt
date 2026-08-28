package dev.ipf.whitenoise.android.state

import dev.ipf.marmotkit.AccountSummaryFfi
import dev.ipf.marmotkit.ChatListRowFfi

internal enum class AccountUnreadFreshness {
    CONFIRMED,
    UNKNOWN,
}

/**
 * One account's latest unread evidence.
 *
 * [unreadCount] is retained while a refresh is unknown so a transient partial
 * result does not manufacture a false zero. UI consumers must use
 * [confirmedUnreadCount] and [showsUnreadDot], which never present that retained
 * value as current. A nullable [hasManualUnread] means the row projection has
 * not established the manual-attention bit yet.
 */
internal data class AccountUnreadValue(
    val unreadCount: ULong,
    val freshness: AccountUnreadFreshness,
    val hasManualUnread: Boolean?,
)

@Suppress("MaxLineLength")
internal fun AccountUnreadValue?.confirmedUnreadCount(): ULong = if (this?.freshness == AccountUnreadFreshness.CONFIRMED) unreadCount else 0uL

@Suppress("MaxLineLength")
internal fun AccountUnreadValue?.showsUnreadDot(): Boolean = confirmedUnreadCount() > 0uL || this?.hasManualUnread == true

/**
 * Seed per-account values from Marmot's cheap, best-effort aggregate.
 *
 * A missing account row is explicitly unknown, not an assertion that its old
 * value is still current. The retained count remains available for a later
 * authoritative merge, while presentation stays quiet until that evidence
 * arrives.
 */
internal fun rawAccountUnreadValues(
    accounts: Iterable<AccountSummaryFfi>,
    rawCountsByAccountId: Map<String, ULong>?,
    previous: Map<String, AccountUnreadValue> = emptyMap(),
): Map<String, AccountUnreadValue> =
    accounts.associate { account ->
        val previousValue = previous[account.label]
        val hasFreshValue = rawCountsByAccountId?.containsKey(account.accountIdHex) == true
        account.label to
            if (hasFreshValue) {
                AccountUnreadValue(
                    unreadCount = checkNotNull(rawCountsByAccountId).getValue(account.accountIdHex),
                    freshness = AccountUnreadFreshness.CONFIRMED,
                    hasManualUnread = previousValue?.hasManualUnread,
                )
            } else {
                AccountUnreadValue(
                    unreadCount = previousValue?.unreadCount ?: 0uL,
                    freshness = AccountUnreadFreshness.UNKNOWN,
                    hasManualUnread = previousValue?.hasManualUnread,
                )
            }
    }

internal fun accountUnreadValueAfterRefresh(
    rawCount: ULong?,
    previous: AccountUnreadValue?,
    exactUnreadCount: ULong?,
    exactHasManualUnread: Boolean?,
): AccountUnreadValue =
    when {
        exactUnreadCount != null ->
            AccountUnreadValue(
                unreadCount = exactUnreadCount,
                freshness = AccountUnreadFreshness.CONFIRMED,
                hasManualUnread = exactHasManualUnread,
            )

        rawCount != null ->
            AccountUnreadValue(
                unreadCount = rawCount,
                freshness = AccountUnreadFreshness.CONFIRMED,
                hasManualUnread = previous?.hasManualUnread,
            )

        else ->
            AccountUnreadValue(
                unreadCount = previous?.unreadCount ?: 0uL,
                freshness = AccountUnreadFreshness.UNKNOWN,
                hasManualUnread = previous?.hasManualUnread,
            )
    }

internal fun accountUnreadValueFromRows(
    rows: Iterable<ChatListRowFfi>,
    activeAccountIdHex: String?,
): AccountUnreadValue =
    AccountUnreadValue(
        unreadCount = accountUnreadCount(rows, activeAccountIdHex, emptyMap()),
        freshness = AccountUnreadFreshness.CONFIRMED,
        hasManualUnread = accountHasManualUnread(rows, activeAccountIdHex, emptyMap()),
    )
