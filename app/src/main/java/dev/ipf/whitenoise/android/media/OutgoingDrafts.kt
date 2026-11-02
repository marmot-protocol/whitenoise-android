package dev.ipf.whitenoise.android.media

import java.util.UUID

/**
 * Which draft each of one conversation's picks belongs to, so a draft's picks share one outgoing worker and no later
 * draft ever reaches it (#3373). A draft runs from its first pick until the user sends it or empties the composer.
 * A pick joins the current draft when it is first decoded or enters the composer, and keeps that draft for good: work
 * still running for a pick that was sent or removed, such as a preparation resumed after the composer was emptied,
 * decodes in its own draft, never in a later one. A draft that has been sent or emptied never gains another pick.
 *
 * A pick is a composer slot id, which a restored draft attachment keeps as its own, or a document's URI. A document
 * picked again after it left the composer joins the draft it enters. A draft that has ended is handed to [retire] once
 * nothing can still decode in it: no pick in the composer belongs to it and none of its sends is still running, the
 * last of which decodes the just-sent thumbnails. A late decode of one of its picks after that starts a fresh
 * generation of the same draft's domain, which only that draft's picks reach.
 */
internal class OutgoingDrafts(
    private val newDraft: () -> String = { UUID.randomUUID().toString() },
    private val retire: (draft: String) -> Unit,
) {
    private val lock = Any()
    private var current = newDraft()

    /** Whether any pick joined [current], so emptying the composer before the first pick changes nothing. */
    private var currentUsed = false

    /** The draft each pick joined, kept after the pick leaves the composer. */
    private val joined = HashMap<String, String>()

    /** The picks now in the composer, as [retain] last recorded them. */
    private var staged: Set<String> = emptySet()

    /** Picks that left the composer, so one that enters it again joins the draft it enters. */
    private val departed = HashSet<String>()

    /** How many sends of each ended draft are still running. */
    private val runningSends = HashMap<String, Int>()

    /** The draft [pick] decodes in: the one it joined, or the current draft, which it joins now. */
    fun draftOf(pick: String): String = synchronized(lock) { joinLocked(pick) }

    /**
     * Starts a send of [picks]. Each keeps the draft it joined, and the current draft ends, so a pick made from now on
     * starts a new one. With [ownDraft], picks that joined no draft yet join a new draft of their own and the current
     * draft is left as it is, as for a send resumed after the process restarted. Returns the drafts the send decodes
     * in, for [endSend] once the send's own decodes are done.
     */
    fun beginSend(
        picks: Collection<String>,
        ownDraft: Boolean = false,
    ): Set<String> =
        synchronized(lock) {
            val own = if (ownDraft) newDraft() else null
            val drafts =
                picks.mapTo(HashSet()) { pick ->
                    if (own != null) joined.getOrPut(pick) { own } else joinLocked(pick)
                }
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
     * Records the [picks] now in the composer. A pick entering it joins the current draft unless it already belongs to
     * one, and a pick entering it again after it left joins the current draft anew. A pick that left releases its
     * draft, and an empty composer ends the current draft, so whatever the user picks next starts a new one.
     */
    fun retain(picks: Set<String>) {
        val retired =
            synchronized(lock) {
                val left = staged - picks
                (picks - staged).forEach { pick ->
                    if (departed.remove(pick)) joined.remove(pick)
                    joinLocked(pick)
                }
                departed += left
                staged = picks
                val ended = left.mapNotNullTo(HashSet()) { joined[it] }
                if (picks.isEmpty() && currentUsed) {
                    ended += current
                    startDraftLocked()
                }
                ended.filter(::idleLocked)
            }
        retired.forEach(retire)
    }

    /** The draft [pick] joined, joining the current draft first if it has none. */
    private fun joinLocked(pick: String): String =
        joined.getOrPut(pick) {
            currentUsed = true
            current
        }

    /** Whether ended [draft] has no running send and no pick in the composer, so nothing decodes in it for now. */
    private fun idleLocked(draft: String): Boolean {
        val stillStaged = staged.any { joined[it] == draft }
        return draft != current && draft !in runningSends && !stillStaged
    }

    /** Starts a new current draft that no pick has joined yet. */
    private fun startDraftLocked() {
        current = newDraft()
        currentUsed = false
    }
}
