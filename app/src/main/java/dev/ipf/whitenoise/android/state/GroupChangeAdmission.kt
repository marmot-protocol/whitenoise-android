package dev.ipf.whitenoise.android.state

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import dev.ipf.marmotkit.MarmotKitException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

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
 * The question belongs to [accountRef] in the current runtime. When that account is not the active one the question
 * cannot be shown, so the refusal is reported as an ordinary failure. Continue counts only while the account and
 * runtime are still current and [stillWanted] still holds, so an answer given after switching accounts, or after the
 * change stopped applying, never acknowledges anything.
 */
internal suspend fun <T> WhiteNoiseAppState.runGroupChange(
    accountRef: String,
    groupIdHex: String,
    invite: Boolean = false,
    stillWanted: () -> Boolean = { true },
    change: suspend () -> T,
): T =
    withGroupCommitLockHeld(accountRef, groupIdHex) {
        admitGroupChange(
            ask = { caution ->
                val title = chatTitle(accountRef, groupIdHex)
                val target = GroupChangeTarget(accountRef, groupIdHex, runtimeGeneration, title)
                when {
                    !ownsGroupChange(target) -> GroupChangeAnswer.Unavailable
                    !outsideGroupCommitLock(accountRef, groupIdHex) {
                        groupChangeConfirmations.ask(caution, invite, target)
                    } -> GroupChangeAnswer.Wait
                    !ownsGroupChange(target) || !stillWanted() -> GroupChangeAnswer.Wait
                    else -> GroupChangeAnswer.Continue
                }
            },
            acknowledge = { caution ->
                marmotIo {
                    when (caution) {
                        GroupChangeCaution.CatchingUp -> acknowledgeGroupCatchingUp(accountRef, groupIdHex)
                        GroupChangeCaution.Undelivered -> acknowledgeGroupCommitUndelivered(accountRef, groupIdHex)
                    }
                }
            },
            // MDK checks catching up first, so an owed commit's refusal hides behind it until the retry.
            hiddenBehind = { caution ->
                val owed =
                    caution == GroupChangeCaution.CatchingUp &&
                        runCatchingCancellable { marmotIo { groupCommitDelivery(accountRef, groupIdHex) } }
                            .getOrNull() != null
                if (owed) listOf(GroupChangeCaution.Undelivered) else emptyList()
            },
            change = change,
        )
    }

/** True while [target]'s account is still the active one in the runtime the question was asked in. */
internal fun WhiteNoiseAppState.ownsGroupChange(target: GroupChangeTarget): Boolean =
    activeAccountRef == target.accountRef && runtimeGeneration == target.runtimeGeneration

/** What became of one group-change question. */
internal enum class GroupChangeAnswer {
    /** The user accepted the risk for this one attempt. */
    Continue,

    /** The user chose to wait, or the change stopped applying; nothing is acknowledged. */
    Wait,

    /** The question could not be asked for this account, so the refusal is an ordinary failure. */
    Unavailable,
}

/**
 * Runs [change], and after each overridable refusal [ask]s the user and, if they continue, [acknowledge]s that caution
 * and retries. A change can need both acknowledgements, and each is asked at most once per run, so the same refusal
 * again is rethrown as an ordinary failure. Waiting ends the change with a [GroupChangeDeclinedException] carrying
 * the refusal; a question that could not be asked rethrows the refusal; any other failure is rethrown unchanged.
 *
 * MDK keeps an acknowledgement until an attempt spends it, even when the other caution then refuses that attempt. So
 * every caution the attempt will meet, including those [hiddenBehind] the reported one, is asked about before any is
 * acknowledged: a Wait at any question leaves nothing acknowledged. Once the user continues, the acknowledgements and
 * their retry run together even if the caller is cancelled meanwhile, so none is left for some later change.
 */
internal suspend fun <T> admitGroupChange(
    ask: suspend (GroupChangeCaution) -> GroupChangeAnswer,
    acknowledge: suspend (GroupChangeCaution) -> Unit,
    hiddenBehind: suspend (GroupChangeCaution) -> List<GroupChangeCaution> = { emptyList() },
    change: suspend () -> T,
): T {
    val asked = mutableSetOf<GroupChangeCaution>()
    var consented = emptyList<GroupChangeCaution>()
    while (true) {
        val refusal =
            try {
                val admitted = consented
                return if (admitted.isEmpty()) {
                    change()
                } else {
                    withContext(NonCancellable) {
                        admitted.forEach { acknowledge(it) }
                        change()
                    }
                }
            } catch (refused: MarmotKitException) {
                refused
            }
        val caution = refusal.groupChangeCaution()
        if (caution == null || !asked.add(caution)) throw refusal
        val needed = listOf(caution) + hiddenBehind(caution).filter { asked.add(it) }
        consented = needed.map { each -> ask(each).consentTo(each, refusal) }
    }
}

/** The caution this answer lets through, or the end of the change: quietly for Wait, as [refusal] when unavailable. */
private fun GroupChangeAnswer.consentTo(
    caution: GroupChangeCaution,
    refusal: MarmotKitException,
): GroupChangeCaution =
    when (this) {
        GroupChangeAnswer.Continue -> caution
        GroupChangeAnswer.Wait -> throw GroupChangeDeclinedException(refusal)
        GroupChangeAnswer.Unavailable -> throw refusal
    }
