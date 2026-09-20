package dev.ipf.whitenoise.android.state

import dev.ipf.marmotkit.AttachmentAutomaticPermissionFfi
import dev.ipf.marmotkit.MarmotInterface
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.atomic.AtomicLong

/** Orders host events and prevents an obsolete evaluation from restoring automatic permission. */
internal class NativeAttachmentPermissions {
    private val revision = AtomicLong()
    private val updates = Mutex()

    /** Invalidates pending evaluations synchronously, before their coroutine can resume. */
    fun invalidate(): Long = revision.incrementAndGet()

    /**
     * Revokes before evaluating. Each native generation is consumed at most once;
     * a stale response is never retried with a freshly minted generation.
     */
    @Suppress("TooGenericExceptionCaught") // Finish revoking other accounts before surfacing a per-account failure.
    suspend fun update(
        expectedRevision: Long,
        engine: MarmotInterface,
        accounts: List<String>,
        evaluate: suspend (String) -> AttachmentAutomaticPermissionFfi,
    ) = updates.withLock {
        val generations = mutableMapOf<String, String>()
        var failure: Exception? = null
        for (account in accounts.distinct()) {
            if (revision.get() != expectedRevision) break
            try {
                generations[account] = engine.beginAttachmentPermissionUpdate(account)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                failure = failure ?: error
            }
        }
        // A single unavailable account must not leave other accounts on stale Wi-Fi approval.
        // Revoke all reachable accounts before failing closed or evaluating any grants.
        failure?.let { throw it }
        for ((account, generation) in generations) {
            if (revision.get() != expectedRevision) break
            val permission = evaluate(account)
            if (revision.get() == expectedRevision) {
                engine.setAttachmentAutomaticPermission(account, generation, permission)
            }
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
