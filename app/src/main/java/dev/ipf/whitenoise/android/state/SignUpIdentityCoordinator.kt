package dev.ipf.whitenoise.android.state

import dev.ipf.marmotkit.AccountSummaryFfi
import dev.ipf.whitenoise.android.ui.onboarding.SignUpOwner
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Result of reconciling a policy-qualified identity with current app ownership. */
internal data class SignUpIdentityReconciliation(
    val receiptHandled: Boolean,
    val activated: Boolean,
)

/**
 * Retains a native identity receipt outside replaceable sign-up UI controllers.
 * The receipt is released only after activation or account-list reconciliation
 * has made the created account durable in Android presentation state.
 */
internal class SignUpIdentityCoordinator {
    private val lock = Mutex()

    @Volatile
    private var pending: PendingSignUpIdentity? = null

    /** Creates at most once, preserving the original owner across controller replacement. */
    suspend fun createOrReuse(
        owner: SignUpOwner,
        create: suspend () -> AccountSummaryFfi,
    ): AccountSummaryFfi =
        lock.withLock {
            pending?.account ?: create().also { pending = PendingSignUpIdentity(it, owner) }
        }

    /** True once native creation succeeded and until reconciliation completes. */
    fun hasPendingReceipt(): Boolean = pending != null

    /** Reconciles the retained receipt and clears it only after the caller confirms durable handling. */
    suspend fun reconcile(
        account: AccountSummaryFfi,
        block: suspend (SignUpOwner) -> SignUpIdentityReconciliation,
    ): Boolean =
        lock.withLock {
            val retained = checkNotNull(pending) { "sign-up identity receipt is missing" }
            check(retained.account.label == account.label) { "sign-up identity receipt changed" }
            val result = block(retained.owner)
            if (result.receiptHandled) pending = null
            result.activated
        }

    private data class PendingSignUpIdentity(
        val account: AccountSummaryFfi,
        val owner: SignUpOwner,
    )
}
