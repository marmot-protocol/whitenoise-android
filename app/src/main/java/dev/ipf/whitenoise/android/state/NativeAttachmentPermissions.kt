package dev.ipf.whitenoise.android.state

import dev.ipf.marmotkit.AttachmentAutomaticPermissionFfi
import dev.ipf.marmotkit.MarmotInterface
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

/** Orders host events and prevents an obsolete evaluation from restoring automatic permission. */
internal class NativeAttachmentPermissions {
    private val revision = AtomicLong()
    private val runtimeOwner = AtomicReference<Any?>()
    private val updates = Mutex()

    /** Invalidates pending evaluations and binds the next revision to one runtime identity. */
    fun invalidate(owner: Any?): Long {
        runtimeOwner.set(owner)
        return revision.incrementAndGet()
    }

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
                    if (isCurrent(expectedRevision, expectedOwner) &&
                        !engine.setAttachmentAutomaticPermission(account, generation, permission)
                    ) {
                        failed += account
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
