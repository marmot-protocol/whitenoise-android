package dev.ipf.whitenoise.android.state

import dev.ipf.whitenoise.android.core.DiagnosticErrorMetadata
import dev.ipf.whitenoise.android.core.DiagnosticFormatter
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.atomic.AtomicBoolean

internal const val LOCAL_DELETE_READINESS_TIMEOUT_MS = 10_000L

/** One confirmation, including a multi-chat batch, admits at most one readiness recovery. */
internal class LocalGroupDeleteReadinessBudget {
    private val claimed = AtomicBoolean(false)

    fun tryClaim(): Boolean = claimed.compareAndSet(false, true)
}

internal enum class LocalDeletePhase(
    val code: String,
) {
    MediaPreflight("media_preflight"),
    NativeDelete("native_delete"),
    PresenceReconciliation("presence_reconciliation"),
    ClientJournal("client_journal"),
    PostCommitCleanup("post_commit_cleanup"),
}

/** Only bounded, selected values enter support reports; neither identities nor native text do. */
internal class LocalGroupDeleteFailure(
    phase: LocalDeletePhase,
    attempt: Int,
    exhausted: Boolean,
    presence: Boolean?,
    cause: Throwable,
) : RuntimeException("Local deletion needs recovery", cause),
    DiagnosticErrorMetadata {
    override val diagnosticErrorCode: String = DiagnosticFormatter.errorCode(cause)
    override val diagnosticTechnicalDetail: String =
        "phase=${phase.code};attempt=$attempt;exhausted=${if (exhausted) 1 else 0};" +
            "presence=${when (presence) {
                true -> "present"
                false -> "absent"
                null -> "unknown"
            }}"
}

/** One explicit gesture gets one native readiness recovery, never a loop of runtime restarts. */
internal class LocalGroupDeleteAttempt(
    private val isCurrent: () -> Boolean,
    private val recoverTransport: suspend () -> Unit,
    private val pause: (suspend (Long) -> Unit)? = null,
    private val readinessBudget: LocalGroupDeleteReadinessBudget = LocalGroupDeleteReadinessBudget(),
) {
    private var currentPhase = LocalDeletePhase.ClientJournal
    private var attemptNumber = 0
    private var exhausted = false
    private var presence: Boolean? = null

    fun checkCurrent() {
        if (!isCurrent()) throw CancellationException("local deletion owner changed")
    }

    /** Recovery itself cannot authorize deletion. Even a failed recovery is followed by a native read. */
    suspend fun recoverOnce() {
        checkCurrent()
        if (!readinessBudget.tryClaim()) return
        withTimeoutOrNull(LOCAL_DELETE_READINESS_TIMEOUT_MS) {
            runCatchingCancellable { recoverTransport() }
        }
        checkCurrent()
    }

    suspend fun <T> phase(
        next: LocalDeletePhase,
        block: suspend () -> T,
    ): T {
        currentPhase = next
        attemptNumber = 0
        exhausted = false
        checkCurrent()
        return runCatchingCancellable { block() }.getOrElse { failure ->
            if (failure is LocalGroupDeleteFailure) throw failure
            throw LocalGroupDeleteFailure(currentPhase, attemptNumber, exhausted, presence, failure)
        }
    }

    /** Reads/preflight may retry; destructive mutations use the separately reconciled path. */
    suspend fun <T> read(
        next: LocalDeletePhase,
        block: suspend () -> T,
    ): T =
        phase(next) {
            var lastFailure: Throwable? = null
            for (number in 1..IDEMPOTENT_RUNTIME_MUTATION_RETRY_ATTEMPTS) {
                checkCurrent()
                attemptNumber = number
                val result = runCatchingCancellable { block() }
                if (result.isSuccess) {
                    checkCurrent()
                    return@phase result.getOrThrow()
                }
                val failure = requireNotNull(result.exceptionOrNull())
                if (!isRetryableIdempotentMutationError(failure)) throw failure
                lastFailure = failure
                if (number < IDEMPOTENT_RUNTIME_MUTATION_RETRY_ATTEMPTS) {
                    if (isTransientRuntimeWorkerError(failure)) recoverOnce()
                    pauseLocalGroupDeleteRetry(pause)
                }
            }
            exhausted = true
            throw requireNotNull(lastFailure)
        }

    suspend fun present(readPresence: suspend () -> Boolean): Boolean {
        val value = read(LocalDeletePhase.PresenceReconciliation, readPresence)
        presence = value
        return value
    }

    suspend fun delete(
        delete: suspend () -> Unit,
        readPresence: suspend () -> Boolean,
    ) = phase(LocalDeletePhase.NativeDelete) {
        deleteLocalGroupWithRecovery(
            isCurrent = isCurrent,
            delete = delete,
            isGroupPresent = readPresence,
            pause = pause,
            observer =
                LocalDeleteRecoveryObserver(
                    recoverTransport = ::recoverOnce,
                    onProgress = { next, number, knownPresence, ended ->
                        currentPhase = next
                        attemptNumber = number
                        presence = knownPresence
                        exhausted = ended
                    },
                ),
        )
    }
}

/** The platform supplies native reads and its own cleanup journal, not a second protocol cache. */
internal class LocalGroupDeleteOperations(
    val previous: suspend () -> PendingLocalGroupDeleteCleanup?,
    val present: suspend () -> Boolean,
    val prepare: suspend () -> PendingLocalGroupDeleteCleanup,
    val stage: suspend (PendingLocalGroupDeleteCleanup) -> Unit,
    val delete: suspend () -> Unit,
)

/** An explicit retry reconciles the prior intent before replacing it or repeating the native mutation. */
internal suspend fun stageAndDeleteLocalGroup(
    attempt: LocalGroupDeleteAttempt,
    operations: LocalGroupDeleteOperations,
): PendingLocalGroupDeleteCleanup? {
    val previous = attempt.phase(LocalDeletePhase.ClientJournal, operations.previous)
    if (previous != null) attempt.recoverOnce()
    // Already absent with no intent is also deleted; old media keys cannot be reconstructed here.
    if (!attempt.present(operations.present)) return previous
    val prepared = attempt.read(LocalDeletePhase.MediaPreflight, operations.prepare)
    val pending =
        attempt.phase(LocalDeletePhase.ClientJournal) {
            val merged = mergeLocalGroupDeleteCleanup(previous, prepared)
            operations.stage(merged)
            merged
        }
    attempt.delete(operations.delete, operations.present)
    return pending
}

/** A retry must not lose previously captured cache keys when the native media projection changes. */
private fun mergeLocalGroupDeleteCleanup(
    previous: PendingLocalGroupDeleteCleanup?,
    prepared: PendingLocalGroupDeleteCleanup,
): PendingLocalGroupDeleteCleanup {
    if (previous == null) return prepared
    check(previous.account == prepared.account && previous.groupIdHex == prepared.groupIdHex) {
        "local delete cleanup identity mismatch"
    }
    return prepared.copy(
        mediaCacheKeys = (previous.mediaCacheKeys + prepared.mediaCacheKeys).distinct(),
        ciphertextTags = previous.ciphertextTags + prepared.ciphertextTags,
    )
}
