package dev.ipf.whitenoise.android.state

import dev.ipf.marmotkit.AttachmentAutomaticPermissionFfi
import dev.ipf.marmotkit.MarmotInterface
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

/** Orders host events and prevents an obsolete evaluation from restoring automatic permission. */
internal class NativeAttachmentPermissions {
    private val revision = AtomicLong()
    private val runtimeOwner = AtomicReference<Any?>()
    private val updates = Mutex()
    private val applied = MutableStateFlow(AppliedPermissions(revision = 0L, accounts = emptySet()))

    /** Accounts whose permission the native runtime holds for one host revision. */
    private data class AppliedPermissions(
        val revision: Long,
        val accounts: Set<String>,
    )

    /** Invalidates pending evaluations and binds the next revision to one runtime identity. */
    fun invalidate(owner: Any?): Long {
        runtimeOwner.set(owner)
        val next = revision.incrementAndGet()
        applied.update { current -> if (next > current.revision) AppliedPermissions(next, emptySet()) else current }
        return next
    }

    /**
     * Suspends until the native runtime holds [account]'s permission for the latest host revision. A worker
     * that starts in a fresh process, or just after a network change revoked the old approval, would
     * otherwise ask before the approval exists and be refused as policy-blocked.
     */
    suspend fun awaitApplied(account: String) {
        applied.first { account in it.accounts }
    }

    /** How many callers are waiting in [awaitApplied], so a test can act exactly while work is held. */
    internal fun waitersForTest(): StateFlow<Int> = applied.subscriptionCount

    /** Whether a retry still belongs to both the latest host inputs and native runtime. */
    fun isCurrent(
        expectedRevision: Long,
        expectedOwner: Any,
    ): Boolean = revision.get() == expectedRevision && runtimeOwner.get() === expectedOwner

    /**
     * Revokes before evaluating. Each native generation is consumed at most once;
     * a stale response is never retried with a freshly minted generation.
     */
    @Suppress("TooGenericExceptionCaught") // Isolate failures so one account cannot strand every other account revoked.
    suspend fun update(
        expectedRevision: Long,
        expectedOwner: Any,
        engine: MarmotInterface,
        accounts: List<String>,
        evaluate: suspend (String) -> AttachmentAutomaticPermissionFfi,
    ): Set<String> =
        updates.withLock {
            val failed = mutableSetOf<String>()
            for (account in accounts.distinct()) {
                if (!isCurrent(expectedRevision, expectedOwner)) break
                try {
                    val generation = engine.beginAttachmentPermissionUpdate(account)
                    val permission = evaluate(account)
                    if (isCurrent(expectedRevision, expectedOwner)) {
                        if (engine.setAttachmentAutomaticPermission(account, generation, permission)) {
                            markApplied(expectedRevision, account)
                        } else {
                            failed += account
                        }
                    }
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (
                    @Suppress("SwallowedException") error: Exception,
                ) {
                    failed += account
                }
            }
            failed
        }

    /** Records [account] as applied only while [appliedRevision] is still the latest host revision. */
    private fun markApplied(
        appliedRevision: Long,
        account: String,
    ) {
        applied.update { current ->
            if (current.revision == appliedRevision) current.copy(accounts = current.accounts + account) else current
        }
    }
}

/** Denies unvalidated connections and user-paused accounts before applying the restrictive network matrix. */
internal fun MediaAutoDownloadMatrix.nativePermission(
    networkTypes: Set<MediaAutoDownloadNetwork>,
    validated: Boolean,
    paused: Boolean,
): AttachmentAutomaticPermissionFfi {
    fun allowed(type: MediaAutoDownloadType): Boolean = validated && !paused && shouldAutoDownload(type, networkTypes)
    return AttachmentAutomaticPermissionFfi(
        images = allowed(MediaAutoDownloadType.Image),
        videos = allowed(MediaAutoDownloadType.Video),
        audio = allowed(MediaAutoDownloadType.Audio),
        files = allowed(MediaAutoDownloadType.Document),
    )
}
