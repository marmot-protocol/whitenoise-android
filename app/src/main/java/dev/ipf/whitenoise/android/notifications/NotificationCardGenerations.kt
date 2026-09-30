package dev.ipf.whitenoise.android.notifications

import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean

/** A process-local lease for a preparing post and its detached enrichment. */
internal class NotificationCardGeneration(
    val id: String,
) {
    val dismissed = AtomicBoolean()
    var references = 1
}

/**
 * Contains only active Android writes, never unread/protocol state. A delete callback invalidates
 * only the displayed generation; new, not-yet-displayed messages always have a different generation.
 */
internal object NotificationCardGenerations {
    private val lock = Any()
    private val active = mutableMapOf<String, NotificationCardGeneration>()

    fun register(generationId: String = UUID.randomUUID().toString()): NotificationCardGeneration =
        synchronized(lock) {
            val existing = active[generationId]
            if (existing != null) {
                existing.references++
                existing
            } else {
                NotificationCardGeneration(generationId).also { active[it.id] = it }
            }
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

    fun dismiss(generationId: String) {
        synchronized(lock) { active[generationId]?.dismissed?.set(true) }
    }

    fun isDismissed(generationId: String?): Boolean =
        synchronized(lock) { active[generationId]?.dismissed?.get() == true }
}
