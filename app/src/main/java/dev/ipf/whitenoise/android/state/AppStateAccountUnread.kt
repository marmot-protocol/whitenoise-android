package dev.ipf.whitenoise.android.state

import dev.ipf.marmotkit.AccountSummaryFfi
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

private const val ACCOUNT_UNREAD_ACCOUNT_FANOUT = 4
private const val ACCOUNT_UNREAD_MEMBER_FANOUT = 4

private data class AccountUnreadFoldResult(
    val accountRef: String,
    val value: AccountUnreadValue,
)

@Suppress(
    "ReturnCount", // Empty, stale-empty, and stale-in-flight snapshots each stop before publishing.
    "CyclomaticComplexMethod", // Raw and roster-aware paths deliberately share one guarded publication point.
)
/** Refreshes unread projections only while the captured account list remains current. */
internal suspend fun WhiteNoiseAppState.refreshAccountUnreadCounts(
    accountSummaries: List<AccountSummaryFfi> = accounts,
    loadMemberRosters: Boolean = true,
    stillCurrent: () -> Boolean = { true },
) {
    if (!stillCurrent()) return
    val accountListTokenAtStart = accountListLifetime.capture()
    val refreshGeneration = accountUnreadStore.beginRefresh()

    fun refreshIsCurrent(): Boolean =
        stillCurrent() &&
            accountListLifetime.isCurrent(accountListTokenAtStart) &&
            accountUnreadStore.isRefreshCurrent(refreshGeneration)

    val signingAccounts = accountSummaries.filter { it.isSignedInSigningAccount() && accountSetup.eligible(it) }
    if (signingAccounts.isEmpty()) {
        if (!refreshIsCurrent()) return
        accountUnreadStore.publishRefresh(
            previous = accountUnreadStore.snapshot(),
            refreshed = emptyMap(),
            generation = refreshGeneration,
        )
        return
    }

    val previous = accountUnreadStore.snapshot()
    val previousValues = previous.mapValues { (_, versioned) -> versioned.value }
    val rawCountsByHex =
        runCatchingCancellable {
            accountAttentionCounts()
        }.onFailure { appStateDebug { "account unread summary refresh failed" } }
            .getOrNull()
    if (!refreshIsCurrent()) return
    val rawValues = rawAccountUnreadValues(signingAccounts, rawCountsByHex, previousValues)
    // Publish best-effort freshness before slower exact row folds: present
    // summary rows are current, while omitted/failed rows immediately stop
    // presenting their retained count as confirmed.
    val interimPublication =
        accountUnreadStore.publishRefresh(
            previous = previous,
            refreshed = rawValues,
            generation = refreshGeneration,
        )
    if (!loadMemberRosters) return
    if (!refreshIsCurrent()) return
    val foldBaselineValues = interimPublication.values.mapValues { (_, versioned) -> versioned.value }
    val accountGate = Semaphore(ACCOUNT_UNREAD_ACCOUNT_FANOUT)
    // Share one roster gate across the whole bulk refresh so member FFI
    // fan-out stays bounded across all signed-in accounts, not per account.
    val memberGate = Semaphore(ACCOUNT_UNREAD_MEMBER_FANOUT)
    val refreshedPairs =
        coroutineScope {
            signingAccounts
                .map { summary ->
                    async {
                        accountGate.withPermit {
                            refreshAccountUnreadFold(
                                summary = summary,
                                rawCount = rawCountsByHex?.get(summary.accountIdHex),
                                previous = foldBaselineValues[summary.label],
                                memberGate = memberGate,
                            )
                        }
                    }
                }.awaitAll()
        }
    // A sign-out/account refresh may have completed while the cold-start
    // fold was suspended. Discard that obsolete result rather than
    // resurrecting counts for an account no longer in the authoritative
    // list; the newer lifecycle operation owns convergence.
    if (!refreshIsCurrent()) return
    // Our own interim freshness writes are valid baselines for the exact
    // fold. Values preserved because a controller/notification writer won
    // the race keep their original baseline, so this bulk pass cannot
    // overwrite newer authoritative evidence.
    accountUnreadStore.publishRefresh(
        previous = accountUnreadExactBaseline(previous, interimPublication),
        refreshed = refreshedPairs.associate { it.accountRef to it.value },
        generation = refreshGeneration,
    )
}

private suspend fun WhiteNoiseAppState.refreshAccountUnreadFold(
    summary: AccountSummaryFfi,
    rawCount: ULong?,
    previous: AccountUnreadValue?,
    memberGate: Semaphore,
): AccountUnreadFoldResult {
    val cheapZero =
        rawCount == 0uL &&
            previous?.hasManualUnread == false
    // The cheap engine total can't see the client's manual-unread flag, so
    // an account believed to be manually flagged still takes the row fold.
    if (!cheapZero) {
        refreshEffectiveAccountUnreadCount(summary, memberGate)?.let { return it }
    }
    return AccountUnreadFoldResult(
        accountRef = summary.label,
        value =
            accountUnreadValueAfterRefresh(
                rawCount = rawCount,
                previous = previous,
                exactUnreadCount = if (cheapZero) 0uL else null,
                exactHasManualUnread = if (cheapZero) false else null,
            ),
    )
}

/**
 * Reads one account's durable chat-list rows and folds them with loaded
 * member rosters so removed/left groups no longer contribute frozen unread
 * totals to [accountUnreadCounts]. This intentionally uses the same
 * removed-group semantics as the active chat-list projection (#625), but is
 * scoped to [summary.accountIdHex] instead of the currently-active account so
 * cross-account indicators stay honest for background accounts too (#662).
 */
private suspend fun WhiteNoiseAppState.refreshEffectiveAccountUnreadCount(
    summary: AccountSummaryFfi,
    memberGate: Semaphore = Semaphore(ACCOUNT_UNREAD_MEMBER_FANOUT),
): AccountUnreadFoldResult? {
    val ref = summary.label.takeIf { it.isNotBlank() } ?: return null
    return runCatchingCancellable {
        marmotIo {
            val rows = chatList(ref, includeArchived = true)
            val membersByGroupId =
                loadUnreadMemberRosters(
                    rows = rows,
                    gate = memberGate,
                    onFailure = { _, _ -> appStateDebug { "account unread member refresh failed" } },
                ) { groupIdHex ->
                    groupMembers(ref, groupIdHex)
                }
            AccountUnreadFoldResult(
                accountRef = ref,
                value =
                    AccountUnreadValue(
                        unreadCount = accountUnreadCount(rows, summary.accountIdHex, membersByGroupId),
                        freshness = AccountUnreadFreshness.CONFIRMED,
                        hasManualUnread = accountHasManualUnread(rows, summary.accountIdHex, membersByGroupId),
                    ),
            )
        }
    }.onFailure { appStateDebug { "account unread refresh failed" } }
        .getOrNull()
}

/**
 * Refresh the unread count for a single account, merging the result into
 * [accountUnreadCounts] without disturbing the other accounts' counts.
 *
 * On a per-notification hot path we only ever need the one account that
 * changed, so we avoid fanning out an all-account scan (#473) while still
 * applying removed-group suppression for that account (#662). This remains
 * intentionally more expensive than the old raw-row fold: it loads the
 * account's chat list plus member rosters for unread rows, so keep callers
 * scoped to the changed account until Marmot exposes a suppressed summary.
 */
internal suspend fun WhiteNoiseAppState.refreshAccountUnreadCount(accountRef: String) {
    val ref = accountRef.takeIf { it.isNotBlank() } ?: return
    // Only signed-in signing accounts are tracked in accountUnreadCounts;
    // skip refs we don't know about (matches refreshAccountUnreadCounts'
    // filter).
    val summary = accounts.firstOrNull { it.isSignedInSigningAccount() && it.label == ref } ?: return
    val previous = accountUnreadStore.snapshot()[ref]
    val result = refreshEffectiveAccountUnreadCount(summary) ?: return
    accountUnreadStore.publishExactIfUnchanged(ref, previous, result.value)
}
