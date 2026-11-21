package dev.ipf.whitenoise.android.state

import dev.ipf.marmotkit.MarmotEventFfi
import dev.ipf.whitenoise.android.R
import kotlinx.coroutines.delay

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
 * app moves to that one, otherwise the unlinked-account prompt covers its chats. Nothing is wiped here: removing the
 * account from this device is the user's explicit choice. The prompt reads the account list, so when that list
 * cannot be re-read even after retries the notice is shown instead, and the unlink is never silent.
 */
internal suspend fun WhiteNoiseAppState.presentDeviceUnlinked(accountRef: String) {
    val name = accounts.firstOrNull { it.label == accountRef }?.accountIdHex?.let(::unlinkedAccountName)
    val listed = readWithRetries { runCatchingCancellable { refreshAccounts() }.isSuccess }
    val active = activeAccountRef == accountRef
    val next = nextSignedInAccount(accounts.map { it.label to it.signedOut }, accountRef)
    if (unlinkNoticeNeeded(listed, active, hasNext = next != null)) {
        presentText(
            title = AppText.Resource(R.string.device_unlinked_title),
            detail = AppText.Resource(R.string.device_unlinked_detail, listOf(name ?: accountRef)),
        )
    }
    if (active && next != null) setActiveAccount(next)
}

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

/**
 * The profile to move to after [unlinked] stopped: the first one still signed in, or null when there is none and
 * the unlinked-account prompt takes over.
 */
internal fun nextSignedInAccount(
    accounts: List<Pair<String, Boolean>>,
    unlinked: String,
): String? = accounts.firstOrNull { (label, signedOut) -> label != unlinked && !signedOut }?.first

/** The name the unlinked notices use: the profile's display name, or its short public key when it has none. */
internal fun WhiteNoiseAppState.unlinkedAccountName(accountIdHex: String): String {
    val name = accountDisplayNameCached(accountIdHex)
    return if (name.equals(accountIdHex, ignoreCase = true)) shortNpub(accountIdHex) else name
}
