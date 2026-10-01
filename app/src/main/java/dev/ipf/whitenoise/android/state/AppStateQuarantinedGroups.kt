package dev.ipf.whitenoise.android.state

import dev.ipf.marmotkit.AppGroupHydrationQuarantineReasonFfi
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext

internal data class QuarantinedGroupRow(
    val groupId: String,
    val reason: AppGroupHydrationQuarantineReasonFfi,
)

internal interface QuarantinedGroupsAccess {
    fun isCurrent(): Boolean

    suspend fun load(): List<QuarantinedGroupRow>

    suspend fun retry(groupId: String): Boolean
}

/** Captures the account and native runtime; MDK owns recovery and concurrent native calls. */
internal fun WhiteNoiseAppState.quarantinedGroupsAccess(): QuarantinedGroupsAccess? {
    val account = activeAccountRef ?: return null
    val runtime = captureHostPerformanceRuntimeOwner()?.runtime ?: return null
    val generation = runtimeGeneration
    if (!developerMode ||
        signOutInProgress ||
        wipeInProgress ||
        accounts.none { it.label == account && it.isSignedInSigningAccount() }
    ) {
        return null
    }
    return NativeQuarantinedGroupsAccess(account, runtime, {
        activeAccountRef == account &&
            runtimeGeneration == generation &&
            developerMode &&
            !signOutInProgress &&
            !wipeInProgress
    })
}

internal class NativeQuarantinedGroupsAccess(
    private val account: String,
    private val runtime: AppMarmotRuntime,
    private val owns: () -> Boolean,
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO,
) : QuarantinedGroupsAccess {
    override fun isCurrent(): Boolean = owns()

    override suspend fun load(): List<QuarantinedGroupRow> =
        withContext(dispatcher) {
            requireCurrent()
            runtime.marmot.quarantinedGroups(account).map { QuarantinedGroupRow(it.groupIdHex, it.reason) }
        }

    override suspend fun retry(groupId: String): Boolean =
        withContext(dispatcher) {
            requireCurrent()
            // An admitted native recovery can finish after the screen is retired. Its result
            // belongs to MDK; the controller publishes it only while its captured owner is current.
            withContext(NonCancellable) { runtime.marmot.retryHydrateQuarantinedGroup(account, groupId) }
        }

    private fun requireCurrent() {
        if (!isCurrent()) throw CancellationException("Quarantine screen owner retired")
    }
}
