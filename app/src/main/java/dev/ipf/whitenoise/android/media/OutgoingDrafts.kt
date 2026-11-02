package dev.ipf.whitenoise.android.media

import java.util.UUID

/**
 * Which draft each of one conversation's picks belongs to, so a draft's picks share one outgoing worker and no later
 * draft ever reaches it (#3373). A draft runs from its first pick until the user sends it or empties the composer.
 * A pick keeps the draft it was sent from for as long as it stays in the composer, and every other pick decodes in the
 * current draft, so a draft that has been sent or emptied never gains another pick.
 *
 * A pick is a composer slot id, which a restored draft attachment keeps as its own, or a document's URI. A draft that
 * has ended is handed to [retire] once nothing can still decode in it: no pick still in the composer was sent from it
 * and none of its sends is still running, the last of which decodes the just-sent thumbnails.
 */
internal class OutgoingDrafts(
    private val newDraft: () -> String = { UUID.randomUUID().toString() },
    private val retire: (draft: String) -> Unit,
) {
    private val lock = Any()
    private var current = newDraft()

    /** Whether any decode was routed to [current], so emptying the composer before the first pick changes nothing. */
    private var currentUsed = false

    /** The draft each pick still in the composer was sent from. */
    private val sentFrom = HashMap<String, String>()

    /** How many sends of each ended draft are still running. */
    private val runningSends = HashMap<String, Int>()

    /** The draft [pick] decodes in: the draft it was sent from while it stays in the composer, else the current one. */
    fun draftOf(pick: String): String =
        synchronized(lock) {
            sentFrom[pick] ?: current.also { currentUsed = true }
        }

    /**
     * Starts a send of [picks]. Each keeps the draft it decodes in now, a pick sent before keeping its earlier draft,
     * and the current draft ends, so a pick made from now on starts a new one. Returns the drafts the send decodes in,
     * for [endSend] once the send's own decodes are done.
     */
    fun beginSend(picks: Collection<String>): Set<String> =
        synchronized(lock) {
            val drafts = picks.mapTo(HashSet()) { pick -> sentFrom.getOrPut(pick) { current } }
            drafts.forEach { draft -> runningSends[draft] = (runningSends[draft] ?: 0) + 1 }
            if (current in drafts) startDraftLocked()
            drafts
        }

    /** Ends a send that [beginSend] started, retiring each of its [drafts] that nothing can still decode in. */
    fun endSend(drafts: Set<String>) {
        val retired =
            synchronized(lock) {
                drafts.forEach { draft ->
                    val left = (runningSends[draft] ?: 0) - 1
                    if (left > 0) runningSends[draft] = left else runningSends.remove(draft)
                }
                drafts.filter(::idleLocked)
            }
        retired.forEach(retire)
    }

    /**
     * Records the [picks] now in the composer. A sent pick that left it releases its draft, and an empty composer ends
     * the current draft, so whatever the user picks next starts a new one.
     */
    fun retain(picks: Set<String>) {
        val retired =
            synchronized(lock) {
                val released = sentFrom.filterKeys { it !in picks }
                released.keys.forEach(sentFrom::remove)
                val ended = released.values.toMutableSet()
                if (picks.isEmpty() && currentUsed) {
                    ended += current
                    startDraftLocked()
                }
                ended.filter(::idleLocked)
            }
        retired.forEach(retire)
    }

    /** Whether ended [draft] has no running send and no pick still in the composer, so nothing decodes in it again. */
    private fun idleLocked(draft: String) = draft != current && draft !in runningSends && draft !in sentFrom.values

    /** Starts a new current draft that no decode has used yet. */
    private fun startDraftLocked() {
        current = newDraft()
        currentUsed = false
    }
}
