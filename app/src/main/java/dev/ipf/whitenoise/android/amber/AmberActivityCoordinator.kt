package dev.ipf.whitenoise.android.amber

import android.content.Intent
import android.os.Handler
import android.os.Looper
import androidx.activity.result.ActivityResultLauncher
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * App-scoped bridge between synchronous MDK signer callbacks and Android's
 * foreground activity-result launcher.
 *
 * Older/unknown signers and ambiguous login choices retain the app-private
 * relay's serialized, cancellation-safe path. Recognized Amber versions with
 * grouped local-intent support use explicit-package requests; ordinary signer
 * work may form a bounded same-account group, while `get_public_key` remains
 * exclusive until its account identity is known. Amber's ID-addressed results
 * are dispatched only to matching workers. The two modes never overlap, so
 * signer-controlled extras cannot cross-complete a relay request.
 */
@Suppress("TooManyFunctions") // One process-wide state machine owns prompt admission, launch, and exact-once delivery.
object AmberActivityCoordinator {
    private val mainHandler by lazy { Handler(Looper.getMainLooper()) }
    private val serializedPending = AtomicReference<SerializedPending?>(null)
    private val groupedPending = ConcurrentHashMap<String, GroupedPending>()
    private val groupedSlots = Semaphore(Nip55.MAX_GROUPED_APPROVALS, true)
    private val approvalGate = ApprovalModeGate()

    @Volatile
    private var launcher: ActivityResultLauncher<Intent>? = null

    sealed interface Outcome {
        data class Completed(
            val resultOk: Boolean,
            val data: Intent?,
        ) : Outcome

        data object NoForegroundActivity : Outcome

        data object TimedOut : Outcome
    }

    private sealed interface Delivery {
        data class Result(
            val resultOk: Boolean,
            val data: Intent?,
        ) : Delivery

        data object LauncherGone : Delivery
    }

    private class SerializedPending(
        val queue: ArrayBlockingQueue<Delivery>,
        val requestId: String,
    ) {
        var acceptsLaunch = true
    }

    private class GroupedPending(
        val queue: ArrayBlockingQueue<Delivery>,
        val signerPackage: String,
    ) {
        var acceptsLaunch = true
    }

    private data class GroupKey(
        val signerPackage: String,
        val currentUser: String,
        // Login has no current_user yet. Its request id makes direct login
        // exclusive instead of letting two account creations share one group.
        val loginRequestId: String?,
    )

    fun attach(launcher: ActivityResultLauncher<Intent>) {
        this.launcher = launcher
    }

    fun detach(launcher: ActivityResultLauncher<Intent>) {
        if (this.launcher === launcher) this.launcher = null
    }

    /** Delivered on the main thread by MainActivity's launcher callback. */
    fun deliverResult(
        resultOk: Boolean,
        data: Intent?,
    ) {
        val relayRequestId = data?.getStringExtra(AmberSignerRelay.EXTRA_REQUEST_ID)
        val serialized = serializedPending.get()
        if (serialized != null && shouldAcceptResult(serialized.requestId, relayRequestId)) {
            deliverSerializedResult(serialized, resultOk, data)
            return
        }
        if (!relayRequestId.isNullOrBlank()) {
            android.util.Log.w(
                "AmberSigner",
                "dropped stale relay result: resultId=$relayRequestId ok=$resultOk",
            )
            return
        }
        deliverGroupedResult(resultOk, data)
    }

    internal fun shouldAcceptResult(
        expectedId: String,
        resultId: String?,
    ): Boolean = expectedId == resultId

    /**
     * Show [intent] and block only the calling MDK worker thread. When
     * [allowGrouping] is true, the intent must already target one explicit
     * signer package and is correlated through its opaque NIP-55 request ID.
     */
    fun awaitApproval(
        intent: Intent,
        timeoutMs: Long,
        requestId: String,
        allowGrouping: Boolean = false,
    ): Outcome {
        require(requestId.isNotBlank() && requestId.length <= Nip55.MAX_REQUEST_ID_CHARS) {
            "NIP-55 request id is outside the supported bounds"
        }
        val signerPackage = (intent.component?.packageName ?: intent.`package`).orEmpty()
        return if (allowGrouping && signerPackage.isNotBlank()) {
            awaitGroupedApproval(intent, timeoutMs, requestId, signerPackage)
        } else {
            awaitSerializedApproval(intent, timeoutMs, requestId)
        }
    }

    @Suppress("ReturnCount")
    // Admission and foreground-loss guards release the gate through the enclosing finally.
    private fun awaitSerializedApproval(
        intent: Intent,
        timeoutMs: Long,
        requestId: String,
    ): Outcome {
        val deadline = Deadline(timeoutMs)
        if (!approvalGate.enterSerialized(deadline)) return Outcome.TimedOut
        try {
            if (launcher == null) return Outcome.NoForegroundActivity
            val queue = ArrayBlockingQueue<Delivery>(1)
            val slot = SerializedPending(queue, requestId)
            check(serializedPending.compareAndSet(null, slot)) { "serialized Amber approval already active" }
            try {
                mainHandler.post {
                    synchronized(slot) {
                        if (!slot.acceptsLaunch || deadline.isExpired() || serializedPending.get() !== slot) {
                            return@synchronized
                        }
                        val active = launcher
                        if (active == null) {
                            queue.offer(Delivery.LauncherGone)
                        } else {
                            try {
                                active.launch(AmberSignerRelay.buildLaunchIntent(requestId, intent))
                            } catch (_: Exception) {
                                queue.offer(Delivery.LauncherGone)
                            }
                        }
                    }
                }
                return awaitDelivery(queue, deadline)
            } finally {
                synchronized(slot) {
                    slot.acceptsLaunch = false
                    serializedPending.compareAndSet(slot, null)
                }
                AmberSignerRelay.consumeHandledSignerPackage(requestId)
            }
        } finally {
            approvalGate.leaveSerialized()
        }
    }

    /**
     * Runs one direct signer request inside a bounded same-package/account
     * session. Login requests use their request id as an exclusive discriminator
     * because no trustworthy account key exists until the signer answers.
     */
    @Suppress("ReturnCount") // Each bounded-admission failure is a distinct terminal outcome with scoped cleanup.
    private fun awaitGroupedApproval(
        intent: Intent,
        timeoutMs: Long,
        requestId: String,
        signerPackage: String,
    ): Outcome {
        val deadline = Deadline(timeoutMs)
        val key =
            GroupKey(
                signerPackage = signerPackage,
                currentUser = intent.getStringExtra(Nip55.EXTRA_CURRENT_USER).orEmpty(),
                loginRequestId =
                    requestId.takeIf {
                        intent.getStringExtra(Nip55.EXTRA_TYPE) == SignerOp.GetPublicKey.intentType
                    },
            )
        if (!approvalGate.enterGrouped(key, deadline)) return Outcome.TimedOut
        try {
            if (!deadline.tryAcquire(groupedSlots)) return Outcome.TimedOut
            try {
                if (launcher == null) return Outcome.NoForegroundActivity
                val queue = ArrayBlockingQueue<Delivery>(1)
                val slot = GroupedPending(queue, signerPackage)
                check(groupedPending.putIfAbsent(requestId, slot) == null) { "duplicate grouped Amber request id" }
                try {
                    mainHandler.post {
                        synchronized(slot) {
                            if (!slot.acceptsLaunch || deadline.isExpired() || groupedPending[requestId] !== slot) {
                                return@synchronized
                            }
                            val active = launcher
                            if (active == null) {
                                completeGrouped(requestId, Delivery.LauncherGone)
                            } else {
                                try {
                                    // Amber's single-task signer activity merges a
                                    // bounded burst of these explicit launches.
                                    active.launch(intent)
                                } catch (_: Exception) {
                                    completeGrouped(requestId, Delivery.LauncherGone)
                                }
                            }
                        }
                    }
                    val outcome = awaitDelivery(queue, deadline)
                    return outcome
                } finally {
                    synchronized(slot) {
                        slot.acceptsLaunch = false
                        groupedPending.remove(requestId, slot)
                    }
                }
            } finally {
                groupedSlots.release()
            }
        } finally {
            approvalGate.leaveGrouped()
        }
    }

    private fun deliverSerializedResult(
        active: SerializedPending,
        resultOk: Boolean,
        data: Intent?,
    ) {
        if (data?.getBooleanExtra(AmberSignerRelay.EXTRA_LAUNCH_FAILED, false) == true) {
            active.queue.offer(Delivery.LauncherGone)
        } else {
            active.queue.offer(Delivery.Result(resultOk, data))
        }
    }

    @Suppress("ReturnCount") // Mutually exclusive wire shapes stop after their own fail-closed correlation path.
    private fun deliverGroupedResult(
        resultOk: Boolean,
        data: Intent?,
    ) {
        val aggregateJson = data?.getStringExtra(Nip55.EXTRA_RESULTS)
        if (aggregateJson != null) {
            val parsed = parseAmberAggregateResults(aggregateJson)
            if (parsed is AmberAggregateParseOutcome.Parsed) {
                parsed.entries.forEach { entry ->
                    val active = groupedPending[entry.id] ?: return@forEach
                    completeGrouped(
                        entry.id,
                        Delivery.Result(resultOk, entry.toIntent(active.signerPackage)),
                    )
                }
            }
            return
        }

        val requestId = data?.getStringExtra(Nip55.EXTRA_ID)
        if (!requestId.isNullOrBlank()) {
            val active = groupedPending[requestId]
            if (active == null) {
                return
            }
            completeGrouped(
                requestId,
                Delivery.Result(resultOk, trustedDirectResult(requestId, data, active.signerPackage)),
            )
            return
        }

        // A null-data cancellation addresses the visible signer session, not an
        // arbitrary request. The gate guarantees every active grouped request
        // belongs to the same package/account session.
        if (!resultOk) {
            groupedPending.keys.toList().forEach { id ->
                completeGrouped(id, Delivery.Result(resultOk = false, data = null))
            }
        }
    }

    private fun completeGrouped(
        requestId: String,
        delivery: Delivery,
    ) {
        groupedPending.remove(requestId)?.queue?.offer(delivery)
    }

    private fun trustedDirectResult(
        requestId: String,
        signerData: Intent,
        signerPackage: String,
    ): Intent =
        Intent().apply {
            signerData.extras?.let(::putExtras)
            removeExtra(AmberSignerRelay.EXTRA_REQUEST_ID)
            removeExtra(AmberSignerRelay.EXTRA_LAUNCH_FAILED)
            putExtra(Nip55.EXTRA_ID, requestId)
            putExtra(AmberSignerRelay.EXTRA_HANDLED_SIGNER_PACKAGE, signerPackage)
        }

    private fun awaitDelivery(
        queue: ArrayBlockingQueue<Delivery>,
        deadline: Deadline,
    ): Outcome {
        val delivery =
            try {
                queue.poll(deadline.remainingNanos(), TimeUnit.NANOSECONDS)
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
                null
            }
        return when (delivery) {
            is Delivery.Result -> Outcome.Completed(delivery.resultOk, delivery.data)
            Delivery.LauncherGone -> Outcome.NoForegroundActivity
            null -> Outcome.TimedOut
        }
    }

    private class Deadline(
        timeoutMs: Long,
    ) {
        private val expiresAtNanos = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMs.coerceAtLeast(0))

        fun remainingNanos(): Long = (expiresAtNanos - System.nanoTime()).coerceAtLeast(0)

        fun isExpired(): Boolean = remainingNanos() == 0L

        fun tryAcquire(semaphore: Semaphore): Boolean =
            try {
                semaphore.tryAcquire(remainingNanos(), TimeUnit.NANOSECONDS)
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
                false
            }
    }

    /** Fair admission gate: one serialized prompt or one same-account group. */
    private class ApprovalModeGate {
        private val lock = ReentrantLock(true)
        private val changed = lock.newCondition()
        private var serializedActive = false
        private var serializedWaiters = 0
        private var groupedKey: GroupKey? = null
        private var groupedCallers = 0

        fun enterSerialized(deadline: Deadline): Boolean =
            lock.withLock {
                serializedWaiters += 1
                try {
                    while (serializedActive || groupedCallers > 0) {
                        if (!changed.awaitUntil(deadline)) return false
                    }
                    serializedActive = true
                    true
                } finally {
                    serializedWaiters -= 1
                    changed.signalAll()
                }
            }

        fun leaveSerialized() {
            lock.withLock {
                serializedActive = false
                changed.signalAll()
            }
        }

        fun enterGrouped(
            key: GroupKey,
            deadline: Deadline,
        ): Boolean =
            lock.withLock {
                while (cannotEnterGrouped(key)) {
                    if (!changed.awaitUntil(deadline)) return false
                }
                groupedKey = key
                groupedCallers += 1
                true
            }

        private fun cannotEnterGrouped(key: GroupKey): Boolean =
            serializedActive ||
                serializedWaiters > 0 ||
                groupedKey?.let { it != key } == true

        fun leaveGrouped() {
            lock.withLock {
                groupedCallers -= 1
                if (groupedCallers == 0) groupedKey = null
                changed.signalAll()
            }
        }

        private fun java.util.concurrent.locks.Condition.awaitUntil(deadline: Deadline): Boolean {
            val remaining = deadline.remainingNanos()
            if (remaining <= 0) return false
            return try {
                awaitNanos(remaining) > 0
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
                false
            }
        }
    }
}
