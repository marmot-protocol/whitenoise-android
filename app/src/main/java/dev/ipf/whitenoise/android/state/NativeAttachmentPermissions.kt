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

    /** Whether a retry still belongs to the latest host permission inputs. */
    fun isCurrent(expectedRevision: Long): Boolean = revision.get() == expectedRevision

    /**
     * Revokes before evaluating. Each native generation is consumed at most once;
     * a stale response is never retried with a freshly minted generation.
     */
    @Suppress("TooGenericExceptionCaught") // Isolate failures so one account cannot strand every other account revoked.
    suspend fun update(
        expectedRevision: Long,
        engine: MarmotInterface,
        accounts: List<String>,
        evaluate: suspend (String) -> AttachmentAutomaticPermissionFfi,
    ): Set<String> =
        updates.withLock {
            val failed = mutableSetOf<String>()
            for (account in accounts.distinct()) {
                if (revision.get() != expectedRevision) break
                try {
                    val generation = engine.beginAttachmentPermissionUpdate(account)
                    val permission = evaluate(account)
                    if (revision.get() == expectedRevision &&
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
