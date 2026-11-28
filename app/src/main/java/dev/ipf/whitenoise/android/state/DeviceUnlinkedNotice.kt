package dev.ipf.whitenoise.android.state

import dev.ipf.marmotkit.MarmotEventFfi
import dev.ipf.whitenoise.android.R
import dev.ipf.whitenoise.android.notifications.activeNotificationGroupIds
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/**
 * The profile [event] says was unlinked from its account by another of the account's devices, or null for any
 * other event. This is the account-wide removal of this device, which MDK reports once after it has signed the
 * account out here. Being removed from a single chat is a different thing and is not reported this way.
 */
internal fun deviceUnlinkedAccount(event: MarmotEventFfi) = (event as? MarmotEventFfi.DeviceUnlinked)?.accountLabel

/**
 * Tells the user this device was unlinked from [accountRef] and brings the app in line with MDK, which has already
 * signed the account out. The account list is re-read so every per-account flow keyed to a signed-in profile lets
 * go of it, whether or not it was the active one. If it was the active profile and another is still signed in, the
 * app moves to that one, otherwise the unlinked-account prompt covers its chats. The account's notifications and
 * conversation shortcuts are taken down, as signing out here does, but its chats are not wiped: removing the
 * account from this device is the user's explicit choice. The prompt reads the account list, so when that list
 * cannot be re-read even after retries the notice is shown instead, and the unlink is never silent. The user can
 * remove the account, and even sign in to it again, before this has spoken. [copy] is the account's copy as it stood
 * when the unlink was reported, and once that copy is removed this neither announces it nor moves away from it.
 */
internal suspend fun WhiteNoiseAppState.presentDeviceUnlinked(
    accountRef: String,
    copy: Long = accountSession.copyLifetimes.capture(accountRef),
) {
    val copies = accountSession.copyLifetimes
    val name = accounts.firstOrNull { it.label == accountRef }?.accountIdHex?.let(::unlinkedAccountName)
    val listed = readWithRetries { runCatchingCancellable { refreshAccounts() }.isSuccess }
    val active = activeAccountRef == accountRef
    val next = signOutOutcome(accounts, accountRef).nextActiveRef
    val gone = unlinkedCopyGone(listed, accountListLoaded, isListed = accounts.any { it.label == accountRef })
    if (!gone && unlinkNoticeNeeded(listed, active, hasNext = next != null)) {
        copies.runIfCurrent(accountRef, copy) {
            presentText(
                ToastMessage(
                    title = AppText.Resource(R.string.device_unlinked_title),
                    detail = AppText.Resource(R.string.device_unlinked_detail, listOf(name ?: accountRef)),
                    unlinkedAccount = accountRef,
                ),
            )
        }
    }
    // Beginning a switch supersedes one the user has pending, so an outdated handler must not begin it at all, and
    // the switch is decided before the cleanup below suspends, so it cannot override a choice the user makes meanwhile.
    // A switch that fails still leaves the account's notifications to clear.
    try {
        if (active && next != null && copies.isCurrent(accountRef, copy)) {
            setActiveAccount(next, shouldActivate = { copies.isCurrent(accountRef, copy) })
        }
    } finally {
        clearUnlinkedAccountNotifications(accountRef, copy, listed)
    }
}

/**
 * Takes the unlinked account's notifications, conversation shortcuts and their channels off this device, as signing
 * out here does (#4115). MDK has already signed the account out, so a removed device, perhaps a lost one, should not
 * keep showing its messages on the lock screen or launcher. A copy that was removed, or signed in again, is left alone.
 */
private suspend fun WhiteNoiseAppState.clearUnlinkedAccountNotifications(
    accountRef: String,
    copy: Long,
    listed: Boolean,
) {
    val copies = accountSession.copyLifetimes
    val unlinked = accounts.firstOrNull { it.label == accountRef }?.unlinked
    if (!unlinkedNotificationsClearable(listed, unlinked) || !copies.isCurrent(accountRef, copy)) return
    // MDK has already signed the account out, so its group inventory may be incomplete: every group still identified,
    // from that inventory or from the account named on an active card, is cleared. Recording the departing channel
    // owners lets startup retention finish if the process dies part way.
    val inventory = runCatchingCancellable { captureAccountKnownGroupIds(accountRef, revoked = true) }.getOrNull()
    val carded = withContext(Dispatchers.IO) { activeNotificationGroupIds(appContext, accountRef) }
    val knownGroupIds = inventory.orEmpty() + carded
    if (!copies.isCurrent(accountRef, copy)) return
    clearRevokedConversationShortcutsForAccount(
        accountRef = accountRef,
        includeUnscopedLegacy = accounts.none { it.label != accountRef && it.isSignedInSigningAccount() },
        knownGroupIds = knownGroupIds,
    )
}

/**
 * Whether an unlinked account's notifications may be cleared. A list this handler re-read ([listed]) that shows the
 * account linked again ([unlinked] false) means it was signed in again, and its new notifications stay. An unreadable
 * list cannot show that, so MDK's report of the unlink is trusted.
 */
internal fun unlinkedNotificationsClearable(
    listed: Boolean,
    unlinked: Boolean?,
): Boolean = !listed || unlinked != false

/**
 * Takes down the notice that [accountRef] was unlinked once that account is gone from this device, and outdates any
 * such notice still on its way. The notice asks the user to remove the account, so it stops being true when no copy
 * is left, and it stays while one is. A notice about another account, or about anything else, stays too.
 */
internal fun WhiteNoiseAppState.retireDeviceUnlinkedNotice(accountRef: String) {
    if (accounts.any { it.label == accountRef }) return
    accountSession.copyLifetimes.copyRemoved(accountRef) { if (toast?.unlinkedAccount == accountRef) clearToast() }
}

/**
 * Whether an unlink was reported only after its copy had left this device, which shows as the account missing from
 * the list. That is believed only of a list this handler re-read ([listed]) and that was published at least once
 * ([listLoaded]). A list that could not be read may predate the account, and staying quiet then would hide the unlink.
 */
internal fun unlinkedCopyGone(
    listed: Boolean,
    listLoaded: Boolean,
    isListed: Boolean,
): Boolean = listed && listLoaded && !isListed

/**
 * Whether the unlink needs its own notice. With nowhere to move to, the unlinked-account prompt says it itself and
 * a second notice would only repeat it, but that prompt appears only once the account list was re-read ([listed]).
 */
internal fun unlinkNoticeNeeded(
    listed: Boolean,
    active: Boolean,
    hasNext: Boolean,
): Boolean = !listed || !active || hasNext

/** Runs [read] until it succeeds, at most [attempts] times with a doubling pause, and says whether it did. */
internal suspend fun readWithRetries(
    attempts: Int = UNLINK_READ_ATTEMPTS,
    firstPauseMs: Long = UNLINK_READ_FIRST_PAUSE_MS,
    read: suspend () -> Boolean,
): Boolean {
    var pause = firstPauseMs
    repeat(attempts) { attempt ->
        if (read()) return true
        if (attempt < attempts - 1) delay(pause)
        pause *= 2
    }
    return false
}

private const val UNLINK_READ_ATTEMPTS = 3
private const val UNLINK_READ_FIRST_PAUSE_MS = 1_000L

/** The name the unlinked notices use: the profile's display name, or its short public key when it has none. */
internal fun WhiteNoiseAppState.unlinkedAccountName(accountIdHex: String): String {
    val name = accountDisplayNameCached(accountIdHex)
    return if (name.equals(accountIdHex, ignoreCase = true)) shortNpub(accountIdHex) else name
}
