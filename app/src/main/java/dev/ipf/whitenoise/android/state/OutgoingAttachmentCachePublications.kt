package dev.ipf.whitenoise.android.state

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async

/** Tracks only active host-cache writes so large own sends cannot race their pending encrypted local copy. */
internal class OutgoingAttachmentCachePublications(
    private val scope: CoroutineScope,
) {
    private data class Publication(
        val identity: Any,
        val work: Deferred<Unit>,
    )

    private val lock = Any()
    private val active = mutableMapOf<String, Publication>()

    /** Identical publication tokens share a write; a new cache incarnation supersedes only the lookup owner. */
    fun publish(
        key: String,
        identity: Any,
        write: suspend () -> Unit,
    ) {
        val publication =
            synchronized(lock) {
                active[key]?.takeIf { it.identity == identity && !it.work.isCompleted }?.let { return }
                Publication(identity, scope.async(start = CoroutineStart.LAZY) { write() }).also { active[key] = it }
            }
        publication.work.invokeOnCompletion {
            synchronized(lock) {
                if (active[key] === publication) active.remove(key)
            }
        }
        publication.work.start()
    }

    /** Reports a matching active write so native retention can bypass slow host encryption. */
    fun isPending(key: String): Boolean = synchronized(lock) { active[key]?.work?.isCompleted == false }

    /** Waits for this file's write without surfacing cache IO failures; observer cancellation still propagates. */
    suspend fun await(key: String) {
        synchronized(lock) { active[key]?.work }?.join()
    }
}
