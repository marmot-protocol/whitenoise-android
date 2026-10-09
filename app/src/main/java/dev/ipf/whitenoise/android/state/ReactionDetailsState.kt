package dev.ipf.whitenoise.android.state

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/** Screen-scoped complete reaction snapshot; a chip's bounded preview never enters this state. */
internal class ReactionDetailsState {
    var participants by mutableStateOf<List<ReactionParticipant>?>(null)
        private set
    var loading by mutableStateOf(true)
        private set
    var failed by mutableStateOf(false)
        private set

    /** A successfully settled snapshot, including an authoritative empty result. */
    val ready: Boolean
        get() = !loading && !failed && participants != null
    private var generation = 0L

    /** Replaces the snapshot only if this read still owns the open sheet's latest request. */
    suspend fun refresh(read: suspend () -> List<ReactionParticipant>) {
        val request = ++generation
        loading = true
        failed = false
        try {
            val result = read()
            currentCoroutineContext().ensureActive()
            if (request == generation) participants = result
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            if (request == generation) failed = true
        } finally {
            if (request == generation) loading = false
        }
    }
}

/** Applies pending own changes to a complete MDK snapshot while preserving every other reactor. */
internal fun reactionDetailsParticipants(
    confirmed: List<ReactionParticipant>,
    mine: String?,
    changes: Collection<OptimisticReactionChange>,
    now: ULong,
): List<ReactionParticipant> {
    val participants = confirmed.toMutableList()
    if (mine != null) {
        changes.forEach { change ->
            participants.removeAll { it.sender.equals(mine, ignoreCase = true) && it.emoji == change.emoji }
            if (change.add) participants += ReactionParticipant(mine, change.emoji, now)
        }
    }
    return participants.sortedWith(
        compareBy<ReactionParticipant> { !it.sender.equals(mine, ignoreCase = true) }
            .thenBy { it.reactedAt }
            .thenBy { it.sender.lowercase() }
            .thenBy { it.emoji },
    )
}
