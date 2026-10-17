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
 * The user chose to wait instead of overriding [refusal]. It is a cancellation, so a change that has done nothing yet
 * stays quiet, and failure presentation ignores it too. A flow whose earlier step already committed reports [refusal]
 * instead, so its partial state is never hidden.
 */
internal class GroupChangeDeclinedException(
    val refusal: MarmotKitException,
) : CancellationException("the group change waits for the group")

/**
 * The change a question is about: [accountRef]'s group [groupIdHex] in runtime [runtimeGeneration], named [title]
 * when the chat list knows it. A question belongs to that account and runtime only.
 */
internal data class GroupChangeTarget(
    val accountRef: String,
    val groupIdHex: String,
    val runtimeGeneration: Int,
    val title: String?,
)

/** One question to the user: wait, or let this one group change to [target] through despite [caution]. */
internal class GroupChangeConfirmation(
    val caution: GroupChangeCaution,
    val invite: Boolean,
    val target: GroupChangeTarget,
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

    /** Shows one question about [target] and returns whether the user chose to continue. */
    suspend fun ask(
        caution: GroupChangeCaution,
        invite: Boolean,
        target: GroupChangeTarget,
    ): Boolean =
        questions.withLock {
            val decision = CompletableDeferred<Boolean>()
            val question = GroupChangeConfirmation(caution, invite, target, decision)
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
 * The change runs holding the group's commit lock, taken here unless the caller already holds it, so [change] must
 * not take it again. The lock is released while the user decides, because sends to the group take it too, and held
 * again before the acknowledgement, so no other change can spend that one-attempt acknowledgement before the retry.
 * MDK checks the retried change against the group as it is then.
 *
 * The question belongs to [accountRef] in the current runtime. Continue counts only while both are still current, so
 * an answer given after switching accounts never changes the previous account's group.
 */
internal suspend fun <T> WhiteNoiseAppState.runGroupChange(
    accountRef: String,
    groupIdHex: String,
    invite: Boolean = false,
    change: suspend () -> T,
): T =
    withGroupCommitLockHeld(accountRef, groupIdHex) {
        admitGroupChange(
            ask = { caution ->
                val title = chatTitle(accountRef, groupIdHex)
                val target = GroupChangeTarget(accountRef, groupIdHex, runtimeGeneration, title)
                val proceed =
                    outsideGroupCommitLock(accountRef, groupIdHex) {
                        groupChangeConfirmations.ask(caution, invite, target)
                    }
                proceed && ownsGroupChange(target)
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
    }

/** True while [target]'s account is still the active one in the runtime the question was asked in. */
internal fun WhiteNoiseAppState.ownsGroupChange(target: GroupChangeTarget): Boolean =
    activeAccountRef == target.accountRef && runtimeGeneration == target.runtimeGeneration

/**
 * Runs [change], and after each overridable refusal [ask]s the user and, if they continue, [acknowledge]s that caution
 * and retries. A change can need both acknowledgements, and each is asked at most once per run, so the same refusal
 * again is rethrown as an ordinary failure. Waiting ends the change with a [GroupChangeDeclinedException] carrying
 * the refusal; any other failure is rethrown unchanged.
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
        if (!ask(caution)) throw GroupChangeDeclinedException(refusal)
        acknowledge(caution)
    }
}
