package dev.ipf.whitenoise.android.ui.chats.newchat

import dev.ipf.whitenoise.android.diagnostics.DmCreationAttempt
import dev.ipf.whitenoise.android.diagnostics.DmCreationFailure
import dev.ipf.whitenoise.android.diagnostics.DmCreationOutcome
import dev.ipf.whitenoise.android.diagnostics.DmCreationPhase
import dev.ipf.whitenoise.android.state.ChatCreateOpenTiming
import dev.ipf.whitenoise.android.state.MarmotTraceSection
import dev.ipf.whitenoise.android.state.WhiteNoiseAppState
import dev.ipf.whitenoise.android.state.runCatchingCancellable
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.joinAll

/** Identifies one short-lived recipient preparation within its screen owner. */
internal data class NewMessageRecipientPreparationKey(
    val accountRef: String,
    val runtimeGeneration: Int,
    val query: String,
    val targetReference: String,
    val retryKey: Int,
    val chatRevision: Long = 0L,
)

/**
 * In-flight native work for one resolved New Message recipient. Results live only as long as the
 * screen and are never an Android-owned protocol cache.
 */
internal class NewMessageRecipientPreparation internal constructor(
    val key: NewMessageRecipientPreparationKey,
    private val prewarm: Deferred<Result<Unit>>,
    private val lookup: Deferred<Result<NewMessageDirectChatResolution>>,
    private val diagnosticAttempt: DmCreationAttempt? = null,
) {
    private var replacementRecorded = false

    /** An uncertain lookup fails closed so tapping cannot create a duplicate DM. */
    suspend fun directChatResolution(): NewMessageDirectChatResolution =
        lookup.await().getOrElse { NewMessageDirectChatResolution(item = null, createRequired = false) }

    /** Waits for both independent native boundaries without altering their typed results. */
    suspend fun awaitCompletion() {
        joinAll(prewarm, lookup)
    }

    /**
     * Replaced work includes children cancelled by the old Compose effect before the next effect starts.
     * Normally completed children stay non-cancelled when disposed, so navigation does not invent replacement.
     */
    fun cancel(replaced: Boolean = false) {
        val prewarmUnfinished = prewarm.isActive || prewarm.isCancelled
        val lookupUnfinished = lookup.isActive || lookup.isCancelled
        val unfinished = prewarmUnfinished || lookupUnfinished
        if (replaced && unfinished && !replacementRecorded) {
            replacementRecorded = true
            diagnosticAttempt?.record(
                DmCreationPhase.OWNER,
                DmCreationOutcome.REPLACED,
                DmCreationFailure.OWNER_REPLACED,
            )
        }
        prewarm.cancel()
        lookup.cancel()
    }
}

/** Only a definitive preparation can replace an authoritative tap-time lookup. */
internal suspend fun preparedLookupOrFresh(
    preparation: NewMessageRecipientPreparation?,
    revisionMatches: () -> Boolean = { true },
    fresh: suspend () -> NewMessageDirectChatResolution,
): NewMessageDirectChatResolution {
    val resolved =
        try {
            preparation?.directChatResolution()
        } catch (_: CancellationException) {
            currentCoroutineContext().ensureActive()
            null
        }
    return resolved?.takeIf { revisionMatches() && (it.item != null || it.createRequired) } ?: fresh()
}

/** Keeps exactly one query/account-scoped preparation and cancels replaced work. */
internal class NewMessageRecipientPreparationCoordinator {
    private var current: NewMessageRecipientPreparation? = null

    /** Reuses an identical recipient key, replacing only unfinished work when the key changes. */
    fun prepare(
        scope: CoroutineScope,
        key: NewMessageRecipientPreparationKey,
        prewarm: suspend () -> Unit,
        lookup: suspend () -> NewMessageDirectChatResolution,
        markStage: (String) -> Unit = {},
        diagnosticAttempt: DmCreationAttempt? = null,
    ): NewMessageRecipientPreparation {
        current?.takeIf { it.key == key }?.let { return it }
        current?.cancel(replaced = true)
        val prewarmResult =
            scope.async {
                diagnosticAttempt?.record(DmCreationPhase.PREWARM, DmCreationOutcome.START)
                markStage(ChatCreateOpenTiming.STAGE_KEY_PACKAGE_PREWARM_START)
                diagnosticBoundary(diagnosticAttempt, DmCreationPhase.PREWARM) { prewarm() }.also {
                    if (it.isSuccess) {
                        diagnosticAttempt?.record(DmCreationPhase.PREWARM, DmCreationOutcome.SUCCESS)
                    } else {
                        diagnosticAttempt?.failed(DmCreationPhase.PREWARM, requireNotNull(it.exceptionOrNull()))
                    }
                    markStage(
                        if (it.isSuccess) {
                            ChatCreateOpenTiming.STAGE_KEY_PACKAGE_PREWARM_RETURN
                        } else {
                            ChatCreateOpenTiming.STAGE_KEY_PACKAGE_PREWARM_FAILED
                        },
                    )
                }
            }
        val lookupResult =
            scope.async {
                diagnosticAttempt?.record(DmCreationPhase.EXISTING_LOOKUP, DmCreationOutcome.START)
                markStage(ChatCreateOpenTiming.STAGE_EXISTING_DM_LOOKUP_START)
                diagnosticBoundary(diagnosticAttempt, DmCreationPhase.EXISTING_LOOKUP) { lookup() }.also {
                    if (it.isSuccess) {
                        val resolution = it.getOrThrow()
                        diagnosticAttempt?.lookupFinished(resolution.item != null || resolution.createRequired)
                    } else {
                        diagnosticAttempt?.failed(DmCreationPhase.EXISTING_LOOKUP, requireNotNull(it.exceptionOrNull()))
                    }
                    markStage(
                        if (it.isSuccess) {
                            ChatCreateOpenTiming.STAGE_EXISTING_DM_LOOKUP_RETURN
                        } else {
                            ChatCreateOpenTiming.STAGE_EXISTING_DM_LOOKUP_FAILED
                        },
                    )
                }
            }
        return NewMessageRecipientPreparation(key, prewarmResult, lookupResult, diagnosticAttempt).also { current = it }
    }

    /** Returns only the preparation owned by this exact account, query, retry and chat revision. */
    fun current(key: NewMessageRecipientPreparationKey): NewMessageRecipientPreparation? {
        val matching = current?.takeIf { it.key == key }
        return matching
    }

    /** Cancels unfinished screen work; disposing a completed preparation produces no replacement record. */
    fun clear() {
        current?.cancel(replaced = true)
        current = null
    }
}

/** Prewarms without reserving a package; MarmotKit revalidates it during the eventual create. */
internal suspend fun WhiteNoiseAppState.prewarmNewMessageRecipient(
    accountRef: String,
    targetReference: String,
) {
    marmotIo(MarmotTraceSection.PREWARM_KEY_PACKAGES) {
        prewarmGroupMemberKeyPackages(accountRef, listOf(targetReference))
    }
}

/** Captures cancellation before the cancellable-result helper rethrows it unchanged. */
private suspend fun <T> diagnosticBoundary(
    attempt: DmCreationAttempt?,
    phase: DmCreationPhase,
    block: suspend () -> T,
): Result<T> =
    try {
        runCatchingCancellable { block() }
    } catch (cancelled: CancellationException) {
        attempt?.failed(phase, cancelled)
        throw cancelled
    }
