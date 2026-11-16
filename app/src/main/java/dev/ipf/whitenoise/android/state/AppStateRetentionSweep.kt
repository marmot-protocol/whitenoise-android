package dev.ipf.whitenoise.android.state

import dev.ipf.marmotkit.RetentionSweepGroupOutcomeFfi
import dev.ipf.marmotkit.RetentionSweepStatusFfi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

/**
 * Sweeps closed conversations across signed-in accounts (#745) on the coarse worker cadence.
 * Expired messages, decrypted L2 media, and stale tray cards are removed without reopening a
 * conversation; [ConversationController.start] handles the open-conversation path.
 *
 * One engine-owned `sweepExpiredRetention` call per account atomically applies clock-skew,
 * unread-anchor, and scan-cap deferrals on its serialized command worker. Android handles
 * tray-card dismissal (#333) and decrypted media-cache eviction (#334) per pruned group.
 *
 * Failures are isolated per account and logged; cancellation propagates. The worker checks
 * MDK's account inventory before starting the notification runtime, and eligible accounts
 * can still bootstrap after process death without a UI.
 */
suspend fun WhiteNoiseAppState.sweepExpiredDisappearingMessages() {
    ensureNotificationRuntimeStarted()
    if (retentionSweepRuntimeOrNull() == null) return
    val signedInAccounts = accounts.filter { it.isSignedInSigningAccount() && accountSetup.eligible(it) }
    for (account in signedInAccounts) {
        currentCoroutineContext().ensureActive()
        runRetentionSweep(account.label, System.currentTimeMillis())
    }
}

/**
 * Run the engine-owned retention sweep for one account and apply the
 * Android-owned consequences for every pruned group. A group matching
 * [handledGroupIdHex] is skipped and its outcome returned instead, so an
 * open conversation's controller can process its own group against the
 * loaded timeline's precise cache keys. Returns null when the sweep
 * failed or no group matched [handledGroupIdHex].
 */
internal suspend fun WhiteNoiseAppState.runRetentionSweep(
    accountRef: String,
    nowMillis: Long,
    handledGroupIdHex: String? = null,
): RetentionSweepGroupOutcomeFfi? {
    val report =
        runCatchingCancellable { marmotIo { sweepExpiredRetention(accountRef, nowMillis.toULong()) } }
            .onFailure {
                appStateDebug(it) { "retention sweep failed acct=${accountRef.take(8)}: ${it.readableMessage()}" }
            }.getOrNull()
            ?: return null
    var handledOutcome: RetentionSweepGroupOutcomeFfi? = null
    for (outcome in report.groups) {
        currentCoroutineContext().ensureActive()
        // Keep the old per-group signal: a persistently deferring or
        // failing group must not be silent just because the account call
        // succeeded.
        if (outcome.status != RetentionSweepStatusFfi.PRUNED &&
            outcome.status != RetentionSweepStatusFfi.NO_EXPIRED_MESSAGES
        ) {
            appStateDebug {
                val kind = outcome.failureKind?.let { " kind=$it" }.orEmpty()
                "retention sweep ${outcome.status} group=${outcome.groupIdHex.take(8)}$kind"
            }
        }
        // Case-insensitive like every other hex-id comparison here: a
        // casing drift must not reroute the open group to the fail-closed
        // whole-slice path.
        if (handledGroupIdHex != null && outcome.groupIdHex.equals(handledGroupIdHex, ignoreCase = true)) {
            handledOutcome = outcome
            continue
        }
        processRetentionSweepOutcome(accountRef, outcome)
    }
    return handledOutcome
}

private suspend fun WhiteNoiseAppState.processRetentionSweepOutcome(
    accountRef: String,
    outcome: RetentionSweepGroupOutcomeFfi,
) {
    // When the engine actually pruned rows, clear the conversation's tray
    // card so it can't keep pointing at a now-vanished message.
    if (outcome.prunedMessages > 0uL) {
        dismissConversationNotifications(accountRef, outcome.groupIdHex)
    }
    val expiredCiphertextSha256 = outcome.mediaCiphertextSha256.toSet()
    if (expiredCiphertextSha256.isEmpty()) return
    // The engine ran gate and prune atomically, so no pre-prune media rows
    // exist to map ciphertext tags to exact cache keys. Fail closed for
    // the in-memory tier: drop the group's whole L1 slice — decrypted
    // bytes must not outlive the retention window, and a closed
    // conversation re-hydrates lazily from disk. The disk tier evicts
    // precisely by its persisted ciphertext tag, independent of load
    // state.
    withContext(Dispatchers.Main.immediate) {
        mediaMemoryCacheKeysSnapshot()
            .filter { mediaCacheKeyInGroup(it, accountRef, outcome.groupIdHex) }
            .forEach(::removeMediaMemoryCacheEntry)
    }
    withContext(Dispatchers.IO) {
        diskMediaCache.removeByCiphertextTags(expiredCiphertextSha256)
    }
}
