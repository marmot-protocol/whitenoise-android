package dev.ipf.whitenoise.android.state

import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import dev.ipf.marmotkit.AccountSummaryFfi
import java.util.Locale

/** Inline account profiles that the chat-list top bar can render at once. */
internal const val MAX_TOP_BAR_OTHER_ACCOUNTS = 3

/**
 * Signed-in signing accounts other than [activeLabel], for the chat-list top
 * bar's one-tap switcher row. Signed-out, read-only (neither local nor
 * external signer), and blank-label entries are excluded (they are not one-tap
 * switch targets from the chat-list chrome).
 *
 * Returns empty when [activeLabel] is null. A destructive Sign Out & Wipe
 * transiently nulls the active account while it drains the wiped account's
 * streams (#610), *before* [WhiteNoiseAppState.accounts] is refreshed. Without
 * this guard the "other accounts" filter (`label != activeLabel`) would match
 * every entry in the still-stale list and flash the just-wiped account — and
 * any account lingering in that pre-refresh snapshot — until the wipe settles
 * (#809). The row's premise is "accounts other than the active one," which is
 * undefined with no active account, so it must present nothing across that
 * transition.
 */

internal fun otherAccountAvatars(
    accounts: List<AccountSummaryFfi>,
    activeLabel: String?,
): List<AccountSummaryFfi> {
    if (activeLabel == null) return emptyList()
    return accounts.filter { account ->
        account.isSignedInSigningAccount() &&
            account.label != activeLabel
    }
}

/**
 * Profiles that must be materialized before an account-switch composition is
 * published. Direct-chat peers seed the target chat list, while the bounded
 * account ids seed the other-account avatars rendered in its top bar.
 */
internal fun accountSwitchProfileSeedIds(
    directPeerIds: List<String>,
    accounts: List<AccountSummaryFfi>,
    targetAccountRef: String,
): List<String> =
    (
        directPeerIds +
            otherAccountAvatars(accounts, targetAccountRef)
                .take(MAX_TOP_BAR_OTHER_ACCOUNTS)
                .map(AccountSummaryFfi::accountIdHex)
    ).distinctBy { it.lowercase(Locale.ROOT) }
