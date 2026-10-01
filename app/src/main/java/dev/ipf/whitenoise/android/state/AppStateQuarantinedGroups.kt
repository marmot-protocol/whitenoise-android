package dev.ipf.whitenoise.android.state

import dev.ipf.marmotkit.AppGroupHydrationQuarantineReasonFfi
import dev.ipf.marmotkit.MarmotInterface
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.Closeable
import java.util.concurrent.atomic.AtomicBoolean

/** Coarse presentation guidance, never a copy of hydration policy. */
internal enum class QuarantinedGroupReason {
    StoredState,
    MissingState,
    MemberValidation,
    GroupRecord,
    PendingCommit,
    Unknown,
}

internal data class QuarantinedGroupRow(
    val groupId: String,
    val reason: QuarantinedGroupReason,
)

internal fun quarantineReason(reason: AppGroupHydrationQuarantineReasonFfi?): QuarantinedGroupReason =
    when (reason) {
        AppGroupHydrationQuarantineReasonFfi.OPEN_MLS_LOAD_FAILED -> QuarantinedGroupReason.StoredState
        AppGroupHydrationQuarantineReasonFfi.OPEN_MLS_GROUP_MISSING -> QuarantinedGroupReason.MissingState
        AppGroupHydrationQuarantineReasonFfi.MEMBER_VALIDATION_FAILED -> QuarantinedGroupReason.MemberValidation
        AppGroupHydrationQuarantineReasonFfi.GROUP_RECORD_LOAD_FAILED -> QuarantinedGroupReason.GroupRecord
        AppGroupHydrationQuarantineReasonFfi.PENDING_COMMIT_RECOVERY_FAILED -> QuarantinedGroupReason.PendingCommit
        null -> QuarantinedGroupReason.Unknown
    }

internal interface QuarantinedGroupsAccess : Closeable {
    fun isCurrent(): Boolean

    suspend fun load(): List<QuarantinedGroupRow>

    suspend fun retry(groupId: String): Boolean
}

/** Captures the runtime rather than reacquiring whichever one is current after IO dispatch. */
internal fun WhiteNoiseAppState.quarantinedGroupsAccess(): QuarantinedGroupsAccess? {
    val account = activeAccountRef
    val epoch = captureAccountSwitchEpoch()
    val owner = captureHostPerformanceRuntimeOwner()
    if (account == null || epoch == null || owner == null) return null
    val current = {
        developerMode &&
            !signOutInProgress &&
            !wipeInProgress &&
            accounts.any { it.label == account && it.isSignedInSigningAccount() } &&
            activeAccountRef == account &&
            captureAccountSwitchEpoch() == epoch &&
            ownsHostPerformanceRuntimeOwner(owner)
    }
    return if (current()) NativeQuarantinedGroupsAccess(account, owner.runtime, current) else null
}

internal class NativeQuarantinedGroupsAccess(
    private val account: String,
    private val runtime: AppMarmotRuntime,
    private val owns: () -> Boolean,
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO,
) : QuarantinedGroupsAccess {
    private val closed = AtomicBoolean()

    override fun isCurrent(): Boolean = !closed.get() && owns()

    override suspend fun load(): List<QuarantinedGroupRow> =
        call(recovery = false) { native ->
            native.quarantinedGroups(account).map { QuarantinedGroupRow(it.groupIdHex, quarantineReason(it.reason)) }
        }

    override suspend fun retry(groupId: String): Boolean =
        call(recovery = true) { native ->
            native.retryHydrateQuarantinedGroup(account, groupId)
        }

    private fun requireCurrent() {
        if (!isCurrent()) throw CancellationException("Quarantine screen owner retired")
    }

    private suspend fun <T> call(
        recovery: Boolean,
        action: suspend (MarmotInterface) -> T,
    ): T {
        currentCoroutineContext().ensureActive()
        requireCurrent()
        val operation = QuarantinedGroupsOperationLeases.acquire(runtime, account)
        try {
            val result =
                withContext(dispatcher) {
                    operation.mutex.withLock {
                        currentCoroutineContext().ensureActive()
                        requireCurrent()
                        // Once recovery is admitted, retain the native response through disposal.
                        // Cancellation suppresses publication; it cannot undo an accepted recovery.
                        if (recovery) {
                            withContext(NonCancellable) { action(runtime.marmot) }
                        } else {
                            action(runtime.marmot)
                        }
                    }
                }
            currentCoroutineContext().ensureActive()
            requireCurrent()
            return result
        } finally {
            operation.close()
        }
    }

    override fun close() {
        closed.set(true)
    }
}
