package dev.ipf.whitenoise.android.state

import kotlinx.coroutines.Deferred

/** Runs one shared account catch-up; see [PushWakeRecoveryController.catchUpAccounts]. */
suspend fun WhiteNoiseAppState.catchUpAccounts() {
    pushWakeRecovery.catchUpAccounts()
}

/** Starts the process-owned chat-list catch-up shared by callers for the same identity. */
internal fun WhiteNoiseAppState.launchCatchUpAccounts(): Deferred<AccountCatchUpResult> =
    pushWakeRecovery.launchCatchUpAccounts()

/** Best-effort catch-up when the app returns to the foreground. */
suspend fun WhiteNoiseAppState.catchUpAfterForegroundActivation() {
    pushWakeRecovery.catchUpAfterForegroundActivation()
}

/** Fences teardown while preserving existing screen-lock notification redaction. */
internal fun WhiteNoiseAppState.pushWakeRecoveryAllowed(): Boolean = pushWakeRecovery.pushWakeRecoveryAllowed()

/** Acknowledges the foreground-service instance that currently owns background runtime. */
internal fun WhiteNoiseAppState.acknowledgePushWakeServiceOwner(owner: Any) =
    pushWakeRecovery.acknowledgePushWakeServiceOwner(owner)

/** Releases only the matching service lease. */
internal fun WhiteNoiseAppState.releasePushWakeServiceOwner(owner: Any) =
    pushWakeRecovery.releasePushWakeServiceOwner(owner)

/** Accepts a process-owned drain only while an Activity or acknowledged service owns its lifetime. */
internal fun WhiteNoiseAppState.acceptPushWakeRecovery(): Boolean = pushWakeRecovery.acceptPushWakeRecovery()

/** Runs one bounded push-wake recovery attempt shared by the service and worker owners. */
internal suspend fun WhiteNoiseAppState.runPushWakeRecoveryAttempt() = pushWakeRecovery.runPushWakeRecoveryAttempt()
