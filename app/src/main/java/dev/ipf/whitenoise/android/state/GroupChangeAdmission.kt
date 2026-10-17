package dev.ipf.whitenoise.android.state

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import dev.ipf.marmotkit.MarmotKitException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext

/** Why MDK refused a group change in a way the user may override for one attempt (#3289). */
internal enum class GroupChangeCaution {
    /** The group may have moved past this device; a change from a stale epoch could split it for everyone. */
    CatchingUp,

    /** The group's last change has not reached every group relay, so members on those relays may not follow. */
    Undelivered,
}

/** Marks the retry that follows recorded acknowledgements, see [isAdmittedGroupChange]. */
private object AdmittedGroupChange : AbstractCoroutineContextElement(AdmittedGroupChangeKey)

/** The context key of [AdmittedGroupChange]. */
private object AdmittedGroupChangeKey : CoroutineContext.Key<AdmittedGroupChange>

/**
 * True inside the retry of a change whose acknowledgements MDK has just recorded. That retry must reach its native
 * command: MDK keeps an acknowledgement until an attempt spends it, so a change that bailed out on its own ownership
 * guard here would leave the user's consent for some later, different change. Whether the change still applies was
 * settled before acknowledging.
 */
internal suspend fun isAdmittedGroupChange(): Boolean = currentCoroutineContext()[AdmittedGroupChangeKey] != null

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
    ): Boolean = askAll(listOf(caution), invite, target)

    /**
     * Shows each of [cautions] about [target] in turn, with no other change's question between them, and returns
     * whether the user continued past all of them. The first Wait ends the session.
     */
    suspend fun askAll(
        cautions: List<GroupChangeCaution>,
        invite: Boolean,
        target: GroupChangeTarget,
    ): Boolean = questions.withLock { cautions.all { show(it, invite, target) } }

    /** Puts one question on screen until it is answered or its change is cancelled. */
    private suspend fun show(
        caution: GroupChangeCaution,
        invite: Boolean,
        target: GroupChangeTarget,
    ): Boolean {
        val decision = CompletableDeferred<Boolean>()
        val question = GroupChangeConfirmation(caution, invite, target, decision)
        pending = question
        try {
            return decision.await()
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
 * The question belongs to [accountRef] in the current runtime. When that account is not the active one, or when
 * [askUser] is false for a change the user never asked for, nothing is shown and the refusal is reported as an
 * ordinary failure. The question is withdrawn as soon as its account, runtime or [stillWanted] no longer holds, and
 * all three are checked again just before acknowledging, so an answer given after switching accounts, or after the
 * change stopped applying, never acknowledges anything.
 */
internal suspend fun <T> WhiteNoiseAppState.runGroupChange(
    accountRef: String,
    groupIdHex: String,
    invite: Boolean = false,
    askUser: Boolean = true,
    stillWanted: () -> Boolean = { true },
    change: suspend () -> T,
): T {
    val generation = runtimeGeneration
    val wanted = { activeAccountRef == accountRef && runtimeGeneration == generation && stillWanted() }
    return withGroupCommitLockHeld(accountRef, groupIdHex) {
        admitGroupChange(
            ask = { cautions ->
                val target = GroupChangeTarget(accountRef, groupIdHex, generation, chatTitle(accountRef, groupIdHex))
                when {
                    !askUser || !ownsGroupChange(target) -> GroupChangeAnswer.Unavailable
                    !wanted() -> GroupChangeAnswer.Wait
                    !outsideGroupCommitLock(accountRef, groupIdHex) {
                        askWhileWanted(wanted) { groupChangeConfirmations.askAll(cautions, invite, target) }
                    } -> GroupChangeAnswer.Wait
                    !wanted() -> GroupChangeAnswer.Wait
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
            // MDK checks catching up first, so an owed commit's refusal hides behind it until the retry. A read that
            // fails counts as owed: asking once too often costs a question, asking too rarely leaves consent behind.
            hiddenBehind = { caution ->
                val owed =
                    caution == GroupChangeCaution.CatchingUp &&
                        runCatchingCancellable { marmotIo { groupCommitDelivery(accountRef, groupIdHex) } }
                            .fold(onSuccess = { it != null }, onFailure = { true })
                if (owed) listOf(GroupChangeCaution.Undelivered) else emptyList()
            },
            stillWanted = wanted,
            change = change,
        )
    }
}

/**
 * Runs [question] while [wanted] holds, rechecking it every [GROUP_CHANGE_RECHECK_MILLIS], and withdraws the question
 * as a Wait as soon as it no longer does, so nobody answers for a change that stopped applying.
 */
internal suspend fun askWhileWanted(
    wanted: () -> Boolean,
    question: suspend () -> Boolean,
): Boolean =
    coroutineScope {
        val answer = async { question() }
        while (!answer.isCompleted && wanted()) {
            withTimeoutOrNull(GROUP_CHANGE_RECHECK_MILLIS) { answer.join() }
        }
        if (answer.isCompleted) {
            answer.await()
        } else {
            answer.cancelAndJoin()
            false
        }
    }

private const val GROUP_CHANGE_RECHECK_MILLIS = 250L

/** True while [target]'s account is still the active one in the runtime the question was asked in. */
internal fun WhiteNoiseAppState.ownsGroupChange(target: GroupChangeTarget): Boolean =
    activeAccountRef == target.accountRef && runtimeGeneration == target.runtimeGeneration

/** What became of one group-change question. */
internal enum class GroupChangeAnswer {
    /** The user accepted the risk for this one attempt. */
    Continue,

    /** The user chose to wait, or the change stopped applying; nothing is acknowledged. */
    Wait,

    /** The question cannot be asked, for another account or a change the user never asked for: an ordinary failure. */
    Unavailable,
}

/**
 * Runs [change], and after each overridable refusal [ask]s the user and, if they continue, [acknowledge]s that caution
 * and retries. A change can need both acknowledgements, and each is asked at most once per run, so the same refusal
 * again is rethrown as an ordinary failure. Waiting ends the change with a [GroupChangeDeclinedException] carrying
 * the refusal; a question that could not be asked rethrows the refusal; any other failure is rethrown unchanged.
 *
 * MDK keeps an acknowledgement until an attempt spends it, even when the other caution then refuses that attempt. So
 * every caution the retry will meet, including those [hiddenBehind] the reported one, is asked about in one session
 * before any is acknowledged, and read again once the user has answered, because answering takes time. The change
 * must still be [stillWanted] just before acknowledging. Once the user continues, the acknowledgements and their
 * retry run together even if the caller is cancelled meanwhile, so none is left for some later change.
 *
 * What the app cannot see is out of reach: MDK has no read-only catching-up check, so a group that starts catching up
 * while the user answers an undelivered question refuses the retry with the undelivered acknowledgement already kept.
 */
internal suspend fun <T> admitGroupChange(
    ask: suspend (List<GroupChangeCaution>) -> GroupChangeAnswer,
    acknowledge: suspend (GroupChangeCaution) -> Unit,
    hiddenBehind: suspend (GroupChangeCaution) -> List<GroupChangeCaution> = { emptyList() },
    stillWanted: () -> Boolean = { true },
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
                    withContext(NonCancellable + AdmittedGroupChange) {
                        admitted.forEach { acknowledge(it) }
                        change()
                    }
                }
            } catch (refused: MarmotKitException) {
                refused
            }
        val caution = refusal.groupChangeCaution()
        if (caution == null || !asked.add(caution)) throw refusal
        consented = consentToEvery(listOf(caution), refusal, asked, ask, hiddenBehind)
        if (!stillWanted()) throw GroupChangeDeclinedException(refusal)
    }
}

/**
 * Asks about [reported] and every caution hidden behind it, then reads again for any that appeared while the user was
 * answering, until nothing new turns up. Returns every caution the user continued past; ends the change otherwise.
 */
private suspend fun consentToEvery(
    reported: List<GroupChangeCaution>,
    refusal: MarmotKitException,
    asked: MutableSet<GroupChangeCaution>,
    ask: suspend (List<GroupChangeCaution>) -> GroupChangeAnswer,
    hiddenBehind: suspend (GroupChangeCaution) -> List<GroupChangeCaution>,
): List<GroupChangeCaution> {
    var consented = emptyList<GroupChangeCaution>()
    var needed = reported + reported.flatMap { hiddenBehind(it) }.filter { asked.add(it) }
    while (needed.isNotEmpty()) {
        ask(needed).requireContinue(refusal)
        consented = consented + needed
        needed = consented.flatMap { hiddenBehind(it) }.filter { asked.add(it) }
    }
    return consented
}

/** Lets the change go on after Continue, or ends it: quietly for Wait, as [refusal] when nobody could be asked. */
private fun GroupChangeAnswer.requireContinue(refusal: MarmotKitException) {
    when (this) {
        GroupChangeAnswer.Continue -> Unit
        GroupChangeAnswer.Wait -> throw GroupChangeDeclinedException(refusal)
        GroupChangeAnswer.Unavailable -> throw refusal
    }
}
