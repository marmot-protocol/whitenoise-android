package dev.ipf.whitenoise.android.notifications

import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean

/** A process-local lease for a preparing post and its detached enrichment. */
internal class NotificationCardGeneration(
    val id: String,
    val sequence: Long,
) {
    val dismissed = AtomicBoolean()
    var references = 1
}

/** The preparing posts represented when a group summary was published, scoped to this process. */
internal data class NotificationGroupDismissalFence(
    val session: String,
    val sequence: Long,
)

/**
 * Contains only active Android writes, never unread/protocol state. Dismissal of a summary invalidates
 * its preparing posts without affecting later arrivals; an old process's callback cannot invalidate a new one.
 */
internal object NotificationCardGenerations {
    private val lock = Any()
    private val session = UUID.randomUUID().toString()
    private var sequence = 0L
    private val active = mutableMapOf<String, NotificationCardGeneration>()

    fun register(): NotificationCardGeneration =
        synchronized(lock) {
            NotificationCardGeneration(UUID.randomUUID().toString(), ++sequence).also { active[it.id] = it }
        }

    fun retain(generation: NotificationCardGeneration): Boolean =
        synchronized(lock) {
            if (active[generation.id] !== generation || generation.dismissed.get()) return@synchronized false
            generation.references++
            true
        }

    fun release(generation: NotificationCardGeneration) {
        synchronized(lock) {
            if (active[generation.id] !== generation) return
            generation.references--
            if (generation.references == 0) active.remove(generation.id)
        }
    }

    fun captureFence(): NotificationGroupDismissalFence = synchronized(lock) { NotificationGroupDismissalFence(session, sequence) }

    fun dismiss(generationId: String) {
        synchronized(lock) { active[generationId]?.dismissed?.set(true) }
    }

    fun isDismissed(generationId: String?): Boolean = synchronized(lock) { active[generationId]?.dismissed?.get() == true }

    fun dismissThrough(fence: NotificationGroupDismissalFence) {
        synchronized(lock) {
            if (fence.session != session) return
            active.values.filter { it.sequence <= fence.sequence }.forEach { it.dismissed.set(true) }
        }
    }
}
