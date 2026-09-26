package dev.ipf.whitenoise.android.state

import dev.ipf.marmotkit.GroupRecoveryStatusFfi
import kotlinx.coroutines.delay
import java.util.Locale

internal const val GROUP_RECOVERY_READ_RETRY_ATTEMPTS: Int = 3
internal const val GROUP_RECOVERY_READ_RETRY_BACKOFF_MS: Long = 250L

/** One conversation this device created and opened, pinned to the account and runtime that did it. */
internal data class FreshGroupCreation(
    val accountRef: String,
    val groupIdHex: String,
    val runtimeGeneration: Int,
)

/**
 * The group whose canonical creation was opened most recently, and nothing older.
 *
 * A group that was just created has no recovery story yet, but its advisory `group_recovery_status`
 * read can still fail against a projection that is settling. Remembering which conversation is in
 * that moment lets the presentation tell "we have no answer yet for a group we just made" apart
 * from "the engine could not answer for a group that has been around". One slot is the whole point:
 * creating another group, or an older creation completing late, cannot leave a second conversation
 * claiming freshness, and the account and runtime generation are part of the identity so a switch
 * or a restart retires it too.
 */
internal class FreshGroupCreationRegistry {
    private var current: FreshGroupCreation? = null

    /** Records a canonical creation that has just been opened, superseding any earlier one. */
    @Synchronized
    fun record(
        accountRef: String?,
        groupIdHex: String,
        runtimeGeneration: Int,
    ) {
        current =
            accountRef?.takeIf { groupIdHex.isNotBlank() }?.let {
                FreshGroupCreation(it, groupIdHex.normalizedGroupId(), runtimeGeneration)
            }
    }

    /** Whether this exact conversation, under this exact runtime, is the current fresh creation. */
    @Synchronized
    fun isFresh(
        accountRef: String?,
        groupIdHex: String,
        runtimeGeneration: Int,
    ): Boolean {
        val creation = current ?: return false
        return accountRef != null &&
            creation.accountRef == accountRef &&
            creation.groupIdHex == groupIdHex.normalizedGroupId() &&
            creation.runtimeGeneration == runtimeGeneration
    }

    /** Retires the context once the engine has answered for that conversation. */
    @Synchronized
    fun settle(
        accountRef: String?,
        groupIdHex: String,
        runtimeGeneration: Int,
    ) {
        if (isFresh(accountRef, groupIdHex, runtimeGeneration)) current = null
    }

    /** Drops any context, for a teardown that invalidates every conversation. */
    @Synchronized
    fun clear() {
        current = null
    }
}

/** Group ids arrive from several seams; compare them the one way. */
private fun String.normalizedGroupId(): String = trim().lowercase(Locale.ROOT)

/**
 * Whether a failed advisory recovery read is evidence the conversation should show.
 *
 * A transient worker closure is presentable only beside confirmed recovery evidence; without that
 * evidence it says nothing about the group. Other failures retain the existing fresh-group rule:
 * prior evidence stays visible, while a never-answered group created moments ago stays quiet.
 */
internal fun groupRecoveryReadFailureIsPresentable(
    lastConfirmedStatus: GroupRecoveryStatusFfi?,
    freshlyCreated: Boolean,
    failure: Throwable,
): Boolean =
    if (isTransientRuntimeWorkerError(failure)) {
        lastConfirmedStatus?.hasVisibleRecoveryEvidence() == true
    } else {
        lastConfirmedStatus != null || !freshlyCreated
    }

/** Whether a confirmed status contains recovery information that must remain inspectable. */
private fun GroupRecoveryStatusFfi.hasVisibleRecoveryEvidence(): Boolean =
    automaticRecoveryFailed || pendingReinvites > 0u || failedReinvites > 0u || rejoinInvitations.isNotEmpty()

/** Retries only a typed closed-worker read, with a short bounded backoff and cancellation propagation. */
@Suppress("TooGenericExceptionCaught") // Native failures must be classified at this boundary.
internal suspend fun <T> retryTransientGroupRecoveryRead(read: suspend () -> T): T {
    var lastFailure: Throwable? = null
    for (attempt in 1..GROUP_RECOVERY_READ_RETRY_ATTEMPTS) {
        try {
            return read()
        } catch (failure: Throwable) {
            rethrowIfCancellation(failure)
            if (!isTransientRuntimeWorkerError(failure)) throw failure
            lastFailure = failure
            if (attempt < GROUP_RECOVERY_READ_RETRY_ATTEMPTS) {
                delay(GROUP_RECOVERY_READ_RETRY_BACKOFF_MS * attempt)
            }
        }
    }
    throw lastFailure ?: IllegalStateException("group recovery read retry budget exhausted")
}
