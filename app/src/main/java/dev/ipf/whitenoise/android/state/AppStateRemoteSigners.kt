package dev.ipf.whitenoise.android.state

import dev.ipf.marmotkit.Nip46PermissionsFfi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/** How often the watcher re-reads MDK's local remote-signer status; the read does no network work. */
private const val REMOTE_SIGNER_STATUS_INTERVAL_MS = 2_000L

/**
 * Reconnects [accountIdHex]'s signer in the background; the watcher shows the outcome. An account whose session
 * could not be resumed tries MDK's resume again first.
 */
internal fun WhiteNoiseAppState.reconnectRemoteSigner(accountIdHex: String) {
    val runtime = marmotOrNull() ?: return
    launchMutation {
        if (marmotOrNull() !== runtime) return@launchMutation
        if (remoteSigners.session(runtime, accountIdHex) == null) {
            resumeRemoteSigner(runtime, accountRefFor(accountIdHex), accountIdHex)
        }
        if (marmotOrNull() !== runtime) return@launchMutation
        val session = remoteSigners.session(runtime, accountIdHex) ?: return@launchMutation
        runCatchingCancellable { session.connect() }
            .onFailure { appStateDebug { "remote signer reconnect failed: ${it.readableMessage()}" } }
        remoteSigners.refresh()
        watchRemoteSigners()
    }
}

/** Approves the exact reviewed grant set for [accountIdHex], then reconnects so signing resumes. */
internal suspend fun WhiteNoiseAppState.approveRemoteSignerPermissions(
    accountIdHex: String,
    reviewed: Nip46PermissionsFfi,
) {
    val session = remoteSigners.session(accountIdHex) ?: return
    try {
        session.approvePermissions(reviewed.version, reviewed.grants)
        session.connect()
    } finally {
        remoteSigners.refresh()
    }
}

/** Records the grant snapshot the user is about to review for [accountIdHex]. */
internal fun WhiteNoiseAppState.reviewRemoteSignerPermissions(accountIdHex: String): Nip46PermissionsFfi? =
    remoteSigners.session(accountIdHex)?.reviewPermissions()

/** Acknowledges the signer's browser approval for [requestId]; the signer's own reply still completes the request. */
internal fun WhiteNoiseAppState.completeRemoteSignerApproval(
    accountIdHex: String,
    requestId: String,
) {
    val session = remoteSigners.session(accountIdHex) ?: return
    runCatchingCancellable { session.completeAuth(requestId) }
        .onFailure { appStateDebug { "remote signer approval was stale: ${it.readableMessage()}" } }
    remoteSigners.refresh()
}

/**
 * Ends this device's pairing for [accountIdHex] without removing the account: MDK asks the signer to forget this
 * device and erases the local connection key. Signing stays paused until the user pairs again.
 */
internal fun WhiteNoiseAppState.disconnectRemoteSigner(accountIdHex: String) {
    val runtime = marmotOrNull() ?: return
    val session = remoteSigners.session(runtime, accountIdHex) ?: return
    launchMutation {
        runCatchingCancellable { session.logout() }
            .onFailure { appStateDebug { "remote signer logout incomplete: ${it.readableMessage()}" } }
        // MDK erased the pairing either way; dropping the handle also lets the status watcher stop.
        if (marmotOrNull() === runtime && remoteSigners.session(runtime, accountIdHex) === session) {
            remoteSigners.markWithoutSession(runtime, accountIdHex, RemoteSignerHealth.PairingEnded)
        }
    }
}

/** Keeps the account-scoped signer statuses current while any remote-signer account exists in this runtime. */
internal fun WhiteNoiseAppState.watchRemoteSigners() {
    if (remoteSigners.watcher?.isActive == true) return
    remoteSigners.watcher =
        mutationsScope.launch(Dispatchers.Default) {
            // Refresh only. Startup and session registration manage runtime ownership.
            while (isActive && remoteSigners.hasSessions()) {
                // Sleeps while no screen shows a signer status; MDK's status is local, so a late read loses nothing.
                remoteSigners.awaitObserver()
                runCatchingCancellable { remoteSigners.refresh() }
                delay(REMOTE_SIGNER_STATUS_INTERVAL_MS)
            }
        }
}
