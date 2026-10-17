package dev.ipf.whitenoise.android.state

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import dev.ipf.marmotkit.MarmotKitException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Why MDK refused a group change in a way the user may override for one attempt (#3289). */
internal enum class GroupChangeCaution {
    /** The group may have moved past this device; a change from a stale epoch could split it for everyone. */
    CatchingUp,

    /** The group's last change has not reached every group relay, so members on those relays may not follow. */
    Undelivered,
}

/** The caution MDK's refusal carries, or null when this is not an overridable group-change refusal. */
internal fun Throwable.groupChangeCaution(): GroupChangeCaution? =
    when (this) {
        is MarmotKitException.GroupCatchingUp -> GroupChangeCaution.CatchingUp
        is MarmotKitException.GroupCommitUndelivered -> GroupChangeCaution.Undelivered
        else -> null
    }

/**
 * The user chose to wait instead of overriding a refused group change. It is a cancellation, so callers that already
 * treat cancellation as "nothing to report" stay quiet, and failure presentation ignores it too.
 */
internal class GroupChangeDeclinedException : CancellationException("the group change waits for the group")

/** One question to the user: wait, or let this one group change through despite [caution]. */
internal class GroupChangeConfirmation(
    val caution: GroupChangeCaution,
    val invite: Boolean,
    private val decision: CompletableDeferred<Boolean>,
) {
    /** Records the user's answer; only the first answer counts. */
    fun answer(proceed: Boolean) {
        decision.complete(proceed)
    }
}

/**
 * Asks the user, one question at a time, whether to override MDK's advisory refusals of a group change. The question
 * is shown at app level, so it survives the screen that started the change closing; cancelling the change withdraws
 * its question.
 */
internal class GroupChangeConfirmations {
    private val questions = Mutex()

    /** The question on screen, or null. */
    var pending: GroupChangeConfirmation? by mutableStateOf(null)
        private set

    /** Shows one question and returns whether the user chose to continue. */
    suspend fun ask(
        caution: GroupChangeCaution,
        invite: Boolean,
    ): Boolean =
        questions.withLock {
            val decision = CompletableDeferred<Boolean>()
            val question = GroupChangeConfirmation(caution, invite, decision)
            pending = question
            try {
                decision.await()
            } finally {
                if (pending === question) pending = null
            }
        }
}

/**
 * Runs one group [change] for [groupIdHex]. MDK refuses a change, advisorily, while the group may have moved past this
 * device or the group's last change has not reached every relay. The user is asked whether to wait or continue, and
 * continuing acknowledges that refusal for one attempt and retries. [invite] selects the stronger wording MDK asks for
 * when inviting. See [admitGroupChange] for the retry rules.
 *
 * A caller may hold the group's commit lock. It is released while the user decides, because sends to the group take
 * the same lock, and held again for the acknowledgement and retry. MDK checks the retried change against the group as
 * it is then.
 */
internal suspend fun <T> WhiteNoiseAppState.runGroupChange(
    accountRef: String,
    groupIdHex: String,
    invite: Boolean = false,
    change: suspend () -> T,
): T =
    admitGroupChange(
        ask = { caution ->
            outsideGroupCommitLock(accountRef, groupIdHex) { groupChangeConfirmations.ask(caution, invite) }
        },
        acknowledge = { caution ->
            marmotIo {
                when (caution) {
                    GroupChangeCaution.CatchingUp -> acknowledgeGroupCatchingUp(accountRef, groupIdHex)
                    GroupChangeCaution.Undelivered -> acknowledgeGroupCommitUndelivered(accountRef, groupIdHex)
                }
            }
        },
        change = change,
    )

/**
 * Runs [change], and after each overridable refusal [ask]s the user and, if they continue, [acknowledge]s that caution
 * and retries. A change can need both acknowledgements, and each is asked at most once per run, so the same refusal
 * again is rethrown as an ordinary failure. Waiting ends the change with [GroupChangeDeclinedException]; any other
 * failure is rethrown unchanged.
 */
internal suspend fun <T> admitGroupChange(
    ask: suspend (GroupChangeCaution) -> Boolean,
    acknowledge: suspend (GroupChangeCaution) -> Unit,
    change: suspend () -> T,
): T {
    val asked = mutableSetOf<GroupChangeCaution>()
    while (true) {
        val refusal =
            try {
                return change()
            } catch (refused: MarmotKitException) {
                refused
            }
        val caution = refusal.groupChangeCaution()
        if (caution == null || !asked.add(caution)) throw refusal
        if (!ask(caution)) throw GroupChangeDeclinedException()
        acknowledge(caution)
    }
}
