package dev.ipf.whitenoise.android.state

import android.util.Log
import dev.ipf.whitenoise.android.BuildConfig
import dev.ipf.whitenoise.android.ui.onboarding.setup.AccountSetupCoordinator
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.withTimeoutOrNull

/** Default time allowed for cold-start work before the shell becomes actionable. */
internal const val BOOTSTRAP_ACTIONABLE_TIMEOUT_MILLIS = 15_000L

/** Emits operational detail only from debug builds so release logs remain privacy-bounded. */
internal inline fun appStateDebug(message: () -> String) {
    // Debug-only: these INFO lines are operational/diagnostic and some carry
    // sender/group context, so they must not ship in release logcat. See #39.
    if (BuildConfig.DEBUG) Log.i("DMAppState", message())
}

/** Emits a debug throwable while retaining only a generic release-build failure marker. */
internal inline fun appStateDebug(
    error: Throwable,
    message: () -> String,
) {
    if (BuildConfig.DEBUG) {
        Log.e("DMAppState", message(), error)
    } else {
        Log.e("DMAppState", "operation_failed")
    }
}

/** Waits for one bootstrap attempt without propagating an actionable-shell timeout. */
internal suspend fun awaitBootstrapAttempt(
    attempt: Deferred<Unit>,
    timeoutMillis: Long,
): Boolean =
    withTimeoutOrNull(timeoutMillis) {
        attempt.await()
        true
    } ?: false

/** Returns a trimmed non-empty value or null for absent and blank configuration. */
internal fun String?.nonBlankOrNull(): String? = this?.trim()?.takeIf { it.isNotEmpty() }

/** Blocks notification mutations whenever the app-lock surface is protecting local state. */
internal fun notificationActionsAllowed(appLockScreenVisible: Boolean): Boolean = !appLockScreenVisible

/** Builds the account-scoped key used by cached group-member snapshots. */
internal fun groupMemberSnapshotKey(
    accountRef: String?,
    groupIdHex: String,
): String? {
    val account = accountRef?.takeIf { it.isNotBlank() } ?: return null
    return "$account:$groupIdHex"
}

/** Recovers one acknowledged checkpoint and refreshes the published account state after success. */
internal suspend fun AccountSetupCoordinator.recoverAndRefresh(
    account: String,
    refreshAccounts: suspend () -> Unit,
): Boolean =
    runCatchingCancellable {
        if (!recover(account)) return@runCatchingCancellable false
        refreshAccounts()
        true
    }.onFailure { failure ->
        appStateDebug(failure) { "onboarding checkpoint recovery failed" }
    }.getOrDefault(false)
