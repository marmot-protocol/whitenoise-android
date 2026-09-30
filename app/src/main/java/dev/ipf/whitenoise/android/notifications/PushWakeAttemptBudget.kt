package dev.ipf.whitenoise.android.notifications

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext

/** Serializes durable push-wake attempt admission and settlement on storage IO. */
internal class PushWakeAttemptBudget(
    private val store: PushTokenStore,
    private val storageDispatcher: CoroutineDispatcher,
    private val nowMs: () -> Long,
) {
    /** Reserves one attempt when a durable wake is pending, or admits ordinary catch-up unchanged. */
    suspend fun reserve(): PushWakeAdmission =
        withContext(storageDispatcher) {
            if (!store.pushWakeCatchUpPending()) {
                PushWakeAdmission.Admitted(claim = null)
            } else {
                when (val reservation = store.reservePushWakeAttempt(nowMs())) {
                    is PushTokenStore.PushWakeAttemptReservation.Claimed ->
                        PushWakeAdmission.Admitted(reservation.claim)
                    PushTokenStore.PushWakeAttemptReservation.Rejected -> PushWakeAdmission.Rejected
                    PushTokenStore.PushWakeAttemptReservation.PersistenceFailed -> {
                        reportPersistenceFailure()
                        PushWakeAdmission.Rejected
                    }
                }
            }
        }

    /** Restores a reserved attempt when a lifecycle fence changes before native work starts. */
    suspend fun release(claim: PushWakeAttemptClaim) {
        withContext(storageDispatcher) {
            if (!store.releasePushWakeAttempt(claim)) reportPersistenceFailure()
        }
    }

    /** Settles the claimed attempt without accepting success from a stale recovery identity. */
    suspend fun settle(
        nativeSucceeded: Boolean,
        identityIsCurrent: () -> Boolean,
    ): Boolean =
        withContext(storageDispatcher) {
            val currentSuccess = nativeSucceeded && identityIsCurrent()
            val persisted =
                if (currentSuccess) {
                    store.completePushWakeAttempt()
                } else {
                    store.deferPushWakeRetry(nowMs())
                }
            if (!persisted) reportPersistenceFailure()
            currentSuccess && persisted
        }

    /** Emits only the bounded recovery phase without storage or account detail. */
    private fun reportPersistenceFailure() {
        PushWakeDiagnostics.event(PushWakeEvent.PersistenceFailed)
    }
}

/** Result of atomically checking and reserving the shared durable attempt budget. */
internal sealed interface PushWakeAdmission {
    data object Rejected : PushWakeAdmission

    data class Admitted(
        val claim: PushWakeAttemptClaim?,
    ) : PushWakeAdmission
}
