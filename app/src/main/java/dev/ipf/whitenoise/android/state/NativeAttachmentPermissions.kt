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
    private val applied = MutableStateFlow(AppliedPermissions(revision = 0L, accounts = emptyMap()))

    /** The revision whose refresh is still applying, or 0 when none is. */
    private val inFlight = AtomicLong()

    /** The permission the native runtime holds for each account under one host revision. */
    private data class AppliedPermissions(
        val revision: Long,
        val accounts: Map<String, AttachmentAutomaticPermissionFfi>,
    )

    /**
     * Invalidates pending evaluations and binds the next revision to one runtime identity. No account counts as
     * applied again until an update under the new revision applies it.
     */
    fun invalidate(owner: Any?): Long {
        runtimeOwner.set(owner)
        val next = revision.incrementAndGet()
        applied.update { current -> if (next > current.revision) AppliedPermissions(next, emptyMap()) else current }
        return next
    }

    /**
     * Suspends until the native runtime holds a permission for [account], under the latest host revision, that
     * [allows] accepts, and returns that revision. A worker that starts in a fresh process, or just after a
     * network change revoked the old approval, would otherwise ask before the approval exists and be refused as
     * policy-blocked. Host inputs can also change before their refresh runs, leaving the previous revision's
     * denial applied, so automatic work waits for an approval that allows its own type.
     */
    suspend fun awaitApplied(
        account: String,
        allows: (AttachmentAutomaticPermissionFfi) -> Boolean = { true },
    ): Long = applied.first { it.accounts[account]?.let(allows) == true }.revision

    /** The host revision whose approval the native runtime holds for [account] now, or null while none is applied. */
    fun appliedRevision(account: String): Long? = applied.value.takeIf { account in it.accounts }?.revision

    /** How many callers are waiting in [awaitApplied], so a test can act exactly while work is held. */
    internal fun waitersForTest(): StateFlow<Int> = applied.subscriptionCount

    /** Whether a retry still belongs to both the latest host inputs and native runtime. */

    /** Marks [revision]'s refresh as applying, so a settle timeout does not supersede it with a new one. */
    fun beginRefresh(revision: Long) = inFlight.set(revision)

    /** Clears [revision]'s in-flight mark once its refresh has finished, applied or not. */
    fun endRefresh(revision: Long) {
        inFlight.compareAndSet(revision, 0L)
    }

    /**
     * Whether a refresh under the latest revision for [owner] is still applying. A settle timeout then waits for it
     * instead of starting a new generation, which would revoke and supersede the one about to land.
     */
    fun refreshInFlight(owner: Any?): Boolean {
        val pending = inFlight.get()
        return pending != 0L && pending == revision.get() && runtimeOwner.get() === owner
    }

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
                            markApplied(expectedRevision, account, permission)
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

    /** Records [account]'s applied [permission] only while [appliedRevision] is still the latest host revision. */
    private fun markApplied(
        appliedRevision: Long,
        account: String,
        permission: AttachmentAutomaticPermissionFfi,
    ) {
        applied.update { current ->
            if (current.revision == appliedRevision) {
                current.copy(accounts = current.accounts + (account to permission))
            } else {
                current
            }
        }
    }
}

/** Whether this applied permission lets MDK fetch [type] automatically. */
internal fun AttachmentAutomaticPermissionFfi.allows(type: MediaAutoDownloadType): Boolean =
    when (type) {
        MediaAutoDownloadType.Image -> images
        MediaAutoDownloadType.Video -> videos
        MediaAutoDownloadType.Audio -> audio
        MediaAutoDownloadType.Document -> files
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
