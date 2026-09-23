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
    internal const val GROUPED_SESSION_BOOTSTRAP_MS = 750L
    internal const val MAX_GROUPED_SCREEN_STARTS = 5
    private const val GROUPED_SCREEN_WINDOW_MS = 30_000L
    private const val MAX_TRACKED_GROUP_KEYS = 64

    private val mainHandler by lazy { Handler(Looper.getMainLooper()) }
    private val serializedPending = AtomicReference<SerializedPending?>(null)
    private val groupedPending = ConcurrentHashMap<String, GroupedPending>()
    private val groupedSlots = Semaphore(Nip55.MAX_GROUPED_APPROVALS, true)
    private val approvalGate = ApprovalModeGate()
    private val groupedLaunchLock = ReentrantLock()
    private var groupedLaunchSession: GroupedLaunchSession? = null
    private var nextGroupedLaunchToken = 0L

    @Volatile
    private var launcher: ActivityResultLauncher<Intent>? = null

    sealed interface Outcome {
        data class Completed(
            val resultOk: Boolean,
            val data: Intent?,
        ) : Outcome

        data object NoForegroundActivity : Outcome

        data object AdmissionUnavailable : Outcome

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
        val intent: Intent,
        val operationType: String?,
        val deadline: Deadline,
    ) {
        var acceptsLaunch = true

        @Volatile
        var sessionToken = 0L

        @Volatile
        var sessionLaunchCount = 0

        @Volatile
        var launchAttempted = false
    }

    private data class GroupKey(
        val signerPackage: String,
        val currentUser: String,
        // Login has no current_user yet. Its request id makes direct login
        // exclusive instead of letting two account creations share one group.
        val loginRequestId: String?,
    )

    private class GroupedLaunchSession(
        val token: Long,
        val key: GroupKey,
    ) {
        val activeRequestIds = linkedSetOf<String>()
        val waitingRequestIds = linkedSetOf<String>()
        var bootstrapRequestId: String? = null
        var bootstrapLaunched = false
        var bootstrapGraceScheduled = false
        var mergeReady = false
        var launchCount = 0
        var screenStartRecorded = false
    }

    private sealed interface GroupedLaunchAttempt {
        data class Succeeded(
            val slot: GroupedPending,
        ) : GroupedLaunchAttempt

        data object Failed : GroupedLaunchAttempt

        data object Stale : GroupedLaunchAttempt
    }

    private data class GroupedLaunchUpdate(
        val launchCount: Int,
        val screenStartKey: GroupKey?,
        val scheduleMergeWindow: Boolean,
    )

    fun attach(launcher: ActivityResultLauncher<Intent>) {
        this.launcher = launcher
    }

    fun detach(launcher: ActivityResultLauncher<Intent>) {
        if (this.launcher === launcher) this.launcher = null
    }

    internal fun groupedPendingCountForTest(): Int = groupedPending.size

    /** Clears process-global test state; callers must ensure no approval worker remains active. */
    internal fun resetForTest() {
        serializedPending.set(null)
        groupedPending.clear()
        groupedLaunchLock.withLock {
            groupedLaunchSession = null
            nextGroupedLaunchToken += 1
        }
        approvalGate.resetForTest()
        AmberApprovalDiagnostics.resetForTest()
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
    private fun awaitGroupedApproval(
        intent: Intent,
        timeoutMs: Long,
        requestId: String,
        signerPackage: String,
    ): Outcome {
        val deadline = Deadline(timeoutMs)
        val operationType = intent.getStringExtra(Nip55.EXTRA_TYPE)
        val key =
            GroupKey(
                signerPackage = signerPackage,
                currentUser = intent.getStringExtra(Nip55.EXTRA_CURRENT_USER).orEmpty(),
                loginRequestId =
                    requestId.takeIf {
                        intent.getStringExtra(Nip55.EXTRA_TYPE) == SignerOp.GetPublicKey.intentType
                    },
            )
        when (approvalGate.enterGrouped(key, deadline)) {
            GroupedAdmission.ADMITTED -> Unit
            GroupedAdmission.BUSY_TIMED_OUT,
            GroupedAdmission.RATE_BUDGET_TIMED_OUT,
            -> {
                AmberApprovalDiagnostics.record(
                    operationType,
                    AmberGroupedSessionState.ADMISSION_WAIT,
                    launchCount = 0,
                    AmberApprovalTerminal.UNAVAILABLE,
                )
                return Outcome.AdmissionUnavailable
            }
        }
        return try {
            awaitAdmittedGroupedApproval(intent, requestId, signerPackage, operationType, key, deadline)
        } finally {
            approvalGate.leaveGrouped()
        }
    }

    private fun awaitAdmittedGroupedApproval(
        intent: Intent,
        requestId: String,
        signerPackage: String,
        operationType: String?,
        key: GroupKey,
        deadline: Deadline,
    ): Outcome {
        if (!deadline.tryAcquire(groupedSlots)) {
            recordGroupedUnavailable(operationType, AmberGroupedSessionState.ADMISSION_WAIT)
            return Outcome.AdmissionUnavailable
        }
        return try {
            awaitGroupedSlot(intent, requestId, signerPackage, operationType, key, deadline)
        } finally {
            groupedSlots.release()
        }
    }

    private fun awaitGroupedSlot(
        intent: Intent,
        requestId: String,
        signerPackage: String,
        operationType: String?,
        key: GroupKey,
        deadline: Deadline,
    ): Outcome {
        if (launcher == null) {
            recordGroupedUnavailable(operationType, AmberGroupedSessionState.BOOTSTRAP)
            return Outcome.NoForegroundActivity
        }
        val queue = ArrayBlockingQueue<Delivery>(1)
        val slot = GroupedPending(queue, signerPackage, intent, operationType, deadline)
        check(groupedPending.putIfAbsent(requestId, slot) == null) { "duplicate grouped Amber request id" }
        return try {
            registerGroupedLaunch(key, requestId, slot)
            val outcome = normalizeGroupedOutcome(slot, awaitDelivery(queue, deadline))
            AmberApprovalDiagnostics.record(
                operationType,
                groupedSessionState(slot),
                slot.sessionLaunchCount,
                outcome.toDiagnosticTerminal(),
            )
            outcome
        } finally {
            synchronized(slot) {
                slot.acceptsLaunch = false
                removeGrouped(requestId, slot)
            }
        }
    }

    private fun normalizeGroupedOutcome(
        slot: GroupedPending,
        delivered: Outcome,
    ): Outcome =
        synchronized(slot) {
            slot.acceptsLaunch = false
            if (delivered == Outcome.TimedOut && !slot.launchAttempted) {
                Outcome.AdmissionUnavailable
            } else {
                delivered
            }
        }

    private fun recordGroupedUnavailable(
        operationType: String?,
        state: AmberGroupedSessionState,
    ) {
        AmberApprovalDiagnostics.record(
            operationType,
            state,
            launchCount = 0,
            AmberApprovalTerminal.UNAVAILABLE,
        )
    }

    /** Launch one cold signer screen, then merge the rest only after Amber has had time to establish its task. */
    private fun registerGroupedLaunch(
        key: GroupKey,
        requestId: String,
        slot: GroupedPending,
    ) {
        var launchAsBootstrap = false
        var launchIntoReadySession = false
        val state =
            groupedLaunchLock.withLock {
                val session =
                    groupedLaunchSession?.also { active ->
                        check(active.key == key) { "grouped Amber launch session crossed account boundaries" }
                    } ?: GroupedLaunchSession(++nextGroupedLaunchToken, key).also { created ->
                        groupedLaunchSession = created
                    }
                session.activeRequestIds += requestId
                slot.sessionToken = session.token
                when {
                    session.mergeReady -> {
                        launchIntoReadySession = true
                        AmberGroupedSessionState.MERGE_READY
                    }
                    session.bootstrapRequestId == null -> {
                        session.bootstrapRequestId = requestId
                        launchAsBootstrap = true
                        AmberGroupedSessionState.BOOTSTRAP
                    }
                    else -> {
                        session.waitingRequestIds += requestId
                        AmberGroupedSessionState.WAITING_FOR_MERGE
                    }
                }
            }
        AmberApprovalDiagnostics.record(slot.operationType, state, launchCount = 0, AmberApprovalTerminal.PENDING)
        when {
            launchAsBootstrap -> postGroupedLaunch(slot.sessionToken, requestId, isBootstrap = true)
            launchIntoReadySession -> postGroupedLaunch(slot.sessionToken, requestId, isBootstrap = false)
        }
    }

    private fun postGroupedLaunch(
        sessionToken: Long,
        requestId: String,
        isBootstrap: Boolean,
    ) {
        mainHandler.post { launchGroupedRequest(sessionToken, requestId, isBootstrap) }
    }

    private fun launchGroupedRequest(
        sessionToken: Long,
        requestId: String,
        isBootstrap: Boolean,
    ) {
        when (val attempt = attemptGroupedLaunch(sessionToken, requestId)) {
            is GroupedLaunchAttempt.Succeeded ->
                finishGroupedLaunch(sessionToken, requestId, isBootstrap, attempt.slot)
            GroupedLaunchAttempt.Failed -> failGroupedLaunchSession(sessionToken)
            GroupedLaunchAttempt.Stale -> Unit
        }
    }

    private fun attemptGroupedLaunch(
        sessionToken: Long,
        requestId: String,
    ): GroupedLaunchAttempt {
        val slot = groupedPending[requestId] ?: return GroupedLaunchAttempt.Stale
        return synchronized(slot) {
            if (!isCurrentGroupedLaunch(slot, sessionToken, requestId)) {
                GroupedLaunchAttempt.Stale
            } else {
                val active = launcher
                if (active == null) {
                    GroupedLaunchAttempt.Failed
                } else {
                    slot.launchAttempted = true
                    try {
                        active.launch(slot.intent)
                        GroupedLaunchAttempt.Succeeded(slot)
                    } catch (_: Exception) {
                        GroupedLaunchAttempt.Failed
                    }
                }
            }
        }
    }

    private fun isCurrentGroupedLaunch(
        slot: GroupedPending,
        sessionToken: Long,
        requestId: String,
    ): Boolean {
        if (!slot.acceptsLaunch || slot.deadline.isExpired()) return false
        return slot.sessionToken == sessionToken && groupedPending[requestId] === slot
    }

    private fun finishGroupedLaunch(
        sessionToken: Long,
        requestId: String,
        isBootstrap: Boolean,
        slot: GroupedPending,
    ) {
        val update = recordGroupedLaunch(sessionToken, requestId, isBootstrap, slot) ?: return
        update.screenStartKey?.let(approvalGate::recordGroupedScreenStart)
        AmberApprovalDiagnostics.record(
            slot.operationType,
            if (isBootstrap) AmberGroupedSessionState.BOOTSTRAP else AmberGroupedSessionState.MERGE_READY,
            update.launchCount,
            AmberApprovalTerminal.PENDING,
        )
        if (update.scheduleMergeWindow) {
            mainHandler.postDelayed(
                { openGroupedMergeWindow(sessionToken) },
                GROUPED_SESSION_BOOTSTRAP_MS,
            )
        }
    }

    private fun recordGroupedLaunch(
        sessionToken: Long,
        requestId: String,
        isBootstrap: Boolean,
        slot: GroupedPending,
    ): GroupedLaunchUpdate? =
        groupedLaunchLock.withLock {
            val session = groupedLaunchSession?.takeIf { it.token == sessionToken }
            if (session == null || requestId !in session.activeRequestIds) {
                null
            } else {
                var scheduleMergeWindow = false
                var screenStartKey: GroupKey? = null
                session.launchCount += 1
                slot.sessionLaunchCount = session.launchCount
                if (isBootstrap) {
                    session.bootstrapLaunched = true
                    if (!session.screenStartRecorded) {
                        session.screenStartRecorded = true
                        screenStartKey = session.key
                    }
                    if (!session.bootstrapGraceScheduled) {
                        session.bootstrapGraceScheduled = true
                        scheduleMergeWindow = true
                    }
                }
                GroupedLaunchUpdate(session.launchCount, screenStartKey, scheduleMergeWindow)
            }
        }

    private fun openGroupedMergeWindow(sessionToken: Long) {
        val waiting =
            groupedLaunchLock.withLock {
                val session = groupedLaunchSession?.takeIf { it.token == sessionToken } ?: return
                session.mergeReady = true
                session.waitingRequestIds.toList().also { session.waitingRequestIds.clear() }
            }
        waiting.forEach { requestId -> launchGroupedRequest(sessionToken, requestId, isBootstrap = false) }
    }

    private fun failGroupedLaunchSession(sessionToken: Long) {
        val requestIds =
            groupedLaunchLock.withLock {
                val session = groupedLaunchSession?.takeIf { it.token == sessionToken } ?: return
                groupedLaunchSession = null
                session.activeRequestIds.toList()
            }
        requestIds.forEach { requestId -> completeGrouped(requestId, Delivery.LauncherGone) }
    }

    private fun removeGrouped(
        requestId: String,
        expected: GroupedPending,
    ) {
        if (groupedPending.remove(requestId, expected)) forgetGroupedLaunch(requestId, expected)
    }

    private fun forgetGroupedLaunch(
        requestId: String,
        slot: GroupedPending,
    ) {
        var promotedBootstrap: String? = null
        groupedLaunchLock.withLock {
            val session = groupedLaunchSession?.takeIf { it.token == slot.sessionToken } ?: return
            session.activeRequestIds -= requestId
            session.waitingRequestIds -= requestId
            if (session.bootstrapRequestId == requestId) session.bootstrapRequestId = null
            if (session.activeRequestIds.isEmpty()) {
                groupedLaunchSession = null
                return
            }
            if (!session.bootstrapLaunched && !session.mergeReady && session.bootstrapRequestId == null) {
                promotedBootstrap = session.waitingRequestIds.firstOrNull()
                promotedBootstrap?.let {
                    session.waitingRequestIds -= it
                    session.bootstrapRequestId = it
                }
            }
        }
        promotedBootstrap?.let { postGroupedLaunch(slot.sessionToken, it, isBootstrap = true) }
    }

    private fun groupedSessionState(slot: GroupedPending): AmberGroupedSessionState =
        groupedLaunchLock.withLock {
            val session = groupedLaunchSession?.takeIf { it.token == slot.sessionToken }
            when {
                session == null -> AmberGroupedSessionState.WAITING_FOR_MERGE
                session.mergeReady -> AmberGroupedSessionState.MERGE_READY
                session.bootstrapRequestId == null -> AmberGroupedSessionState.WAITING_FOR_MERGE
                else -> AmberGroupedSessionState.BOOTSTRAP
            }
        }

    private fun Outcome.toDiagnosticTerminal(): AmberApprovalTerminal =
        when (this) {
            is Outcome.Completed ->
                if (!resultOk || readRejectedIntentExtra(data)) {
                    AmberApprovalTerminal.REJECTED
                } else {
                    AmberApprovalTerminal.COMPLETED
                }
            Outcome.NoForegroundActivity -> AmberApprovalTerminal.UNAVAILABLE
            Outcome.AdmissionUnavailable -> AmberApprovalTerminal.UNAVAILABLE
            Outcome.TimedOut -> AmberApprovalTerminal.TIMED_OUT
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

    /** Correlates addressed results and treats legacy unaddressed rejection as cancellation of the visible session. */
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

        // Amber through 6.6.0 also returns RESULT_OK with rejected=true and no
        // ID. Like null-data cancellation, this rejects the visible session;
        // it cannot approve anything. The gate restricts that session to one
        // package/account, and unknown explicit IDs already returned above.
        if (!resultOk || readRejectedIntentExtra(data)) {
            groupedPending.keys.toList().forEach { id ->
                completeGrouped(id, Delivery.Result(resultOk = false, data = null))
            }
        }
    }

    private fun completeGrouped(
        requestId: String,
        delivery: Delivery,
    ) {
        val slot = groupedPending.remove(requestId) ?: return
        forgetGroupedLaunch(requestId, slot)
        slot.queue.offer(delivery)
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

    private enum class GroupedAdmission {
        ADMITTED,
        BUSY_TIMED_OUT,
        RATE_BUDGET_TIMED_OUT,
    }

    /** Fair admission gate: one serialized prompt or one same-account group. */
    private class ApprovalModeGate {
        private val lock = ReentrantLock(true)
        private val changed = lock.newCondition()
        private var serializedActive = false
        private var serializedWaiters = 0
        private var groupedKey: GroupKey? = null
        private var groupedCallers = 0
        private val groupedScreenStarts = linkedMapOf<GroupKey, ArrayDeque<Long>>()

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
        ): GroupedAdmission =
            lock.withLock {
                var admitted = false
                while (!admitted) {
                    while (cannotEnterGrouped(key)) {
                        if (!changed.awaitUntil(deadline)) return@withLock GroupedAdmission.BUSY_TIMED_OUT
                    }
                    if (groupedCallers > 0 && groupedKey == key) {
                        groupedCallers += 1
                        admitted = true
                    } else if (groupedScreenBudgetWaitNanos(key) == 0L) {
                        groupedKey = key
                        groupedCallers = 1
                        admitted = true
                    } else {
                        val budgetWaitNanos = groupedScreenBudgetWaitNanos(key)
                        if (!changed.awaitFor(deadline, budgetWaitNanos)) {
                            return@withLock GroupedAdmission.RATE_BUDGET_TIMED_OUT
                        }
                    }
                }
                GroupedAdmission.ADMITTED
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

        private fun groupedScreenBudgetWaitNanos(key: GroupKey): Long {
            val now = System.nanoTime()
            purgeExpiredScreenStarts(now)
            val starts = groupedScreenStarts[key]
            val windowNanos = TimeUnit.MILLISECONDS.toNanos(GROUPED_SCREEN_WINDOW_MS)
            return when {
                starts == null && groupedScreenStarts.size < MAX_TRACKED_GROUP_KEYS -> 0L
                starts == null -> {
                    val oldestTrackedStart = groupedScreenStarts.values.minOf { it.first() }
                    (oldestTrackedStart + windowNanos - now).coerceAtLeast(1L)
                }
                starts.size < MAX_GROUPED_SCREEN_STARTS -> 0L
                else -> (starts.first() + windowNanos - now).coerceAtLeast(1L)
            }
        }

        fun recordGroupedScreenStart(key: GroupKey) {
            lock.withLock {
                val now = System.nanoTime()
                purgeExpiredScreenStarts(now)
                check(key in groupedScreenStarts || groupedScreenStarts.size < MAX_TRACKED_GROUP_KEYS)
                groupedScreenStarts.getOrPut(key) { ArrayDeque() }.addLast(now)
                changed.signalAll()
            }
        }

        private fun purgeExpiredScreenStarts(now: Long) {
            val windowNanos = TimeUnit.MILLISECONDS.toNanos(GROUPED_SCREEN_WINDOW_MS)
            val iterator = groupedScreenStarts.iterator()
            while (iterator.hasNext()) {
                val starts = iterator.next().value
                while (starts.isNotEmpty() && now - starts.first() >= windowNanos) starts.removeFirst()
                if (starts.isEmpty()) iterator.remove()
            }
        }

        fun resetForTest() {
            lock.withLock {
                serializedActive = false
                serializedWaiters = 0
                groupedKey = null
                groupedCallers = 0
                groupedScreenStarts.clear()
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

        private fun java.util.concurrent.locks.Condition.awaitFor(
            deadline: Deadline,
            requestedNanos: Long,
        ): Boolean {
            val remaining = deadline.remainingNanos()
            if (remaining <= 0) return false
            return try {
                awaitNanos(minOf(remaining, requestedNanos))
                !deadline.isExpired()
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
                false
            }
        }
    }
}
