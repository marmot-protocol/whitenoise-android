package dev.ipf.whitenoise.android.state

import dev.ipf.marmotkit.AccountSummaryFfi
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Retains one successful native creation until its policy is qualified and Android activation accepts it. */
internal class IdentityPolicyQualification {
    private val lock = Mutex()
    private var receipt: AccountSummaryFfi? = null

    /** Retries qualification for the same receipt; a policy failure can never trigger a second creation. */
    suspend fun createQualifyAndAccept(
        create: suspend () -> AccountSummaryFfi,
        qualify: suspend (AccountSummaryFfi) -> Unit,
        accept: (AccountSummaryFfi) -> Unit,
    ): AccountSummaryFfi =
        lock.withLock {
            val account = receipt ?: create().also { receipt = it }
            qualify(account)
            accept(account)
            receipt = null
            account
        }
}
