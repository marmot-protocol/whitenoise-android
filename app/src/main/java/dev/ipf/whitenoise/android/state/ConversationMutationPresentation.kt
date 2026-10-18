package dev.ipf.whitenoise.android.state

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/**
 * Main-confined, transient action presentation for one pinned conversation.
 *
 * This owns admission and overlays, not native commands or their durable lifetime. Group
 * applications advance the existing authority receipt so a late command cannot replace newer state.
 * Disposing a timeline never cancels an admitted command or removes an app-owned optimistic send.
 */
internal class ConversationMutationPresentation(
    private val ownsPresentation: () -> Boolean,
) {
    /** Identity, rather than its value, decides which archive overlay may settle. */
    class ArchiveIntent(
        val archived: Boolean,
    )

    private var archiveIntent by mutableStateOf<ArchiveIntent?>(null)
    var inFlight by mutableStateOf(false)
        private set
    var error by mutableStateOf<ErrorPresentation?>(null)
        private set
    var groupAuthorityEpoch = 0L
        private set

    /** Whether the controller still owns assignment to its visible group field. */
    fun ownsCurrentPresentation(): Boolean = ownsPresentation()

    /** Clears the prior action's failure when the facade admits a new command or the user dismisses it. */
    fun clearError() {
        error = null
    }

    /** Retains a cause-accurate action failure independently of subscription retries. */
    fun recordFailure(failure: ErrorPresentation) {
        error = failure
    }

    /** Records an authoritative group application without creating a second generation owner. */
    fun authoritativeGroupApplied() {
        groupAuthorityEpoch += 1L
    }

    /** Shows an admitted archive intent over the latest native group value. */
    fun presentedArchived(authoritative: Boolean): Boolean = archiveIntent?.archived ?: authoritative

    /** Begins the transient overlay before waiting for serialized native I/O. */
    fun beginArchive(archived: Boolean): ArchiveIntent = ArchiveIntent(archived).also { archiveIntent = it }

    /** A late completion clears its own overlay and reveals the newest authoritative value. */
    fun finishArchive(intent: ArchiveIntent) {
        if (archiveIntent === intent) archiveIntent = null
    }

    /** Runs one action immediately, returning [defaultValue] for overlapping calls rather than queueing them. */
    suspend fun <T> runIfIdle(
        defaultValue: T,
        block: suspend () -> T,
    ): T {
        if (inFlight) return defaultValue
        inFlight = true
        return try {
            block()
        } finally {
            inFlight = false
        }
    }
}
