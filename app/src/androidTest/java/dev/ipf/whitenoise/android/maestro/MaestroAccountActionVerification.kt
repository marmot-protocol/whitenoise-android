package dev.ipf.whitenoise.android.maestro

import dev.ipf.marmotkit.AccountSummaryFfi
import dev.ipf.marmotkit.Marmot
import dev.ipf.whitenoise.android.state.AppPhase
import dev.ipf.whitenoise.android.state.WhiteNoiseAppState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

private const val ACCOUNT_ACTION_DRAFT = "Maestro account action draft"

/** Snapshot real identities, both timelines, untouched drafts and complete public metadata before UI ownership. */
internal suspend fun captureMaestroAccountAction(
    native: Marmot,
    state: WhiteNoiseAppState,
    group: String,
    postcondition: String,
): MaestroAccountActionBaseline? {
    if (!postcondition.startsWith("account-action-")) return null
    val owner = withContext(Dispatchers.Main.immediate) { checkNotNull(state.activeAccountRef) }
    return withContext(Dispatchers.IO) {
        val accounts = native.listAccounts()
        check(accounts.size == 3 && accounts.any { it.label == owner })
        val members = accounts.filter { native.presentedChatListRow(it.label, group) != null }
        check(members.size == 2 && members.any { it.label == owner })
        MaestroAccountActionBaseline(
            accounts,
            owner,
            group,
            members.associate { it.label to maestroSharedMessageSnapshot(native, it.label, group) },
            members.associate { it.label to native.messageDraft(it.label, group) },
            accounts.associate { it.label to native.userProfile(it.accountIdHex) },
        )
    }
}

/** Only matching Android and native outcomes may qualify; unfinished work returns explicit false within 15s. */
internal suspend fun verifyMaestroAccountAction(
    native: Marmot,
    state: WhiteNoiseAppState,
    before: MaestroAccountActionBaseline?,
    postcondition: String?,
): Boolean {
    if (postcondition?.startsWith("account-action-") != true) return true
    val baseline = checkNotNull(before)
    val wiped = postcondition == "account-action-wiped"
    return withTimeoutOrNull(15_000L) {
        var matches = false
        while (!matches) {
            val nativeMatches =
                withContext(Dispatchers.IO) { maestroAccountActionNativeMatches(native, baseline, wiped) }
            val androidMatches =
                withContext(Dispatchers.Main.immediate) {
                    state.activeAccountRef == baseline.messages.keys.first { it != baseline.owner } &&
                        state.phase == AppPhase.Ready &&
                        !state.signOutInProgress &&
                        !state.wipeInProgress &&
                        maestroAccountInventoryMatches(state.accounts, baseline, wiped)
                }
            matches = nativeMatches && androidMatches
            if (!matches) delay(100L)
        }
        true
    } ?: false
}

private fun maestroAccountActionNativeMatches(
    native: Marmot,
    before: MaestroAccountActionBaseline,
    wiped: Boolean,
): Boolean {
    val retained = before.accounts.filterNot { wiped && it.label == before.owner }
    val members = before.messages.keys.filterNot { wiped && it == before.owner }
    return maestroAccountInventoryMatches(native.listAccounts(), before, wiped) &&
        retained.all { native.userProfile(it.accountIdHex) == before.profiles[it.label] } &&
        members.all { maestroAccountActionHistoryMatches(native, before, it, wiped) } &&
        members.all {
            if (it == before.owner) {
                native.messageDraft(it, before.group)?.content == ACCOUNT_ACTION_DRAFT
            } else {
                native.messageDraft(it, before.group) == before.drafts[it]
            }
        }
}

/** Wipe may append authenticated MLS state rows; it must not add/delete/edit ordinary user messages. */
private fun maestroAccountActionHistoryMatches(
    native: Marmot,
    before: MaestroAccountActionBaseline,
    account: String,
    wiped: Boolean,
): Boolean {
    val original = checkNotNull(before.messages[account])
    val current = maestroSharedMessageSnapshot(native, account, before.group)
    if (!wiped) return current == original
    val originalIds = original.map { it.id }.toSet()
    val added = readMaestroMessages(native, account, before.group).filterNot { it.messageIdHex in originalIds }
    return current.filter { it.id in originalIds } == original &&
        added.all { it.kind == 1210uL && it.direction == "system" && it.sourceMessageIdHex == null }
}

private fun maestroAccountInventoryMatches(
    accounts: List<AccountSummaryFfi>,
    before: MaestroAccountActionBaseline,
    wiped: Boolean,
): Boolean {
    val expected = before.accounts.filterNot { wiped && it.label == before.owner }
    if (accounts.size != expected.size) return false
    return expected.all { original ->
        val current = accounts.singleOrNull { it.accountIdHex == original.accountIdHex }
        current != null &&
            current.label == original.label &&
            current.signedOut == (original.label == before.owner || original.signedOut) &&
            current.localSigning == original.localSigning &&
            current.externalSigning == original.externalSigning
    }
}
