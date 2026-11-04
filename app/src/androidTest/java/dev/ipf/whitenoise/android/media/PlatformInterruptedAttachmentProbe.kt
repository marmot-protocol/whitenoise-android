package dev.ipf.whitenoise.android.media

import android.app.ActivityManager
import android.app.job.JobScheduler
import android.content.Context
import android.os.Build
import android.os.Process
import android.os.SystemClock
import androidx.activity.ComponentActivity
import androidx.compose.runtime.IntState
import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import androidx.work.WorkInfo
import androidx.work.WorkManager
import dev.ipf.marmotkit.AttachmentTransferStateFfi
import dev.ipf.whitenoise.android.state.AttachmentDownloadPriority
import dev.ipf.whitenoise.android.state.AttachmentDownloadWorker
import dev.ipf.whitenoise.android.state.AttachmentTransferRequest
import dev.ipf.whitenoise.android.state.MediaAutoDownloadNetwork
import dev.ipf.whitenoise.android.state.MediaAutoDownloadType
import dev.ipf.whitenoise.android.state.NATIVE_TRANSFER_TERMINAL_FAILURES
import dev.ipf.whitenoise.android.state.NativeAttachmentProgress
import dev.ipf.whitenoise.android.state.WhiteNoiseAppState
import dev.ipf.whitenoise.android.state.attachmentDownloadWorkName
import dev.ipf.whitenoise.android.state.attachmentIntentStore
import dev.ipf.whitenoise.android.state.nativeProgress
import dev.ipf.whitenoise.android.state.openNativeAttachment
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

/** Stops one real ordinary WorkManager job, preserving native automatic demand and its received checkpoint. */
internal object PlatformInterruptedAttachmentProbe {
    private const val FAILURE_STATE_LIMIT = 12
    private const val NATIVE_RESUME_TIMEOUT_MILLIS = 60_000L

    /** Closed native scheduling facts; references, account identities and error text never enter reports. */
    private data class NativeResumeSnapshot(
        val observationIndex: Long,
        val observedElapsedMillis: Long,
        val phase: AttachmentTransferStateFfi,
        val attempt: ULong,
        val retryAt: ULong?,
    )

    /** Both observers belong to one probe lifetime and supply its success or failure evidence. */
    private class ResumeObservations {
        private val startedAt = SystemClock.elapsedRealtime()
        private val phaseTimings = mutableListOf<Pair<String, Long>>()

        /** Returns the monotonic elapsed interval used by the interruption probe diagnostics. */
        fun elapsedMillis(): Long = SystemClock.elapsedRealtime() - startedAt

        /** Records a phase boundary with its monotonic time for the interruption probe. */
        fun mark(phase: String) {
            phaseTimings.add(phase to elapsedMillis())
        }

        /**
         * Summarizes observed probe phases without promoting diagnostics to a successful execution receipt.
         */
        fun phaseDiagnostics(): JSONArray =
            JSONArray(
                phaseTimings.map { (phase, elapsed) ->
                    JSONObject().put("phase", phase).put("elapsed_ms", elapsed)
                },
            )

        val workStates = CopyOnWriteArrayList<WorkInfo>()
        val nativeStates = CopyOnWriteArrayList<NativeResumeSnapshot>()

        // Keep the earliest facts even after the rolling resume buffer has evicted them.
        val initialNativeStates = CopyOnWriteArrayList<NativeResumeSnapshot>()

        /** Retains bounded initial evidence independently from the rolling resume proof. */
        fun recordNativeSnapshot(snapshot: NativeResumeSnapshot) {
            if (initialNativeStates.size < FAILURE_STATE_LIMIT) {
                initialNativeStates.add(snapshot)
            }
            if (nativeStates.size == FAILURE_STATE_LIMIT) nativeStates.removeAt(0)
            nativeStates.add(snapshot)
        }
    }

    /** Automatic demand receives no interactive read, deliberate Retry, force-run or seeded checkpoint. */
    @Suppress("LongMethod") // One lifetime owns the generated platform job and its cleanup.
    suspend fun run(
        state: WhiteNoiseAppState,
        request: AttachmentTransferRequest,
        port: Int,
        bytes: ByteArray,
    ) = coroutineScope {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        check(context.packageName == "dev.ipf.whitenoise.android.medialatency")
        check(shell("getprop ro.kernel.qemu").trim() == "1")
        val restoreApplication = PlatformBackgroundAttachmentProbe.bindGeneratedApplication(context, state)
        val manager = WorkManager.getInstance(context)
        val workName = attachmentDownloadWorkName(request)
        val store = attachmentIntentStore(context)
        val observations = ResumeObservations()
        val states = observations.workStates
        val nativeStates = observations.nativeStates
        val nativeObservationIndex = AtomicLong()
        var nativeObserver: Job? = null
        val observer =
            launch {
                manager.getWorkInfosForUniqueWorkFlow(workName).collect { infos ->
                    infos.singleOrNull()?.let(states::add)
                }
            }
        var step = "start"
        var stoppedAt = 0L
        try {
            HeldAttachmentCancellationProbe.control(port, "/__hold-resumable-acquisition")
            ActivityScenario.launch(ComponentActivity::class.java).use { scenario ->
                enableAutomaticDocumentPolicy(state)
                nativeObserver =
                    launch(start = CoroutineStart.UNDISPATCHED) {
                        state
                            .nativeProgress(request)
                            .filterNotNull()
                            .map {
                                NativeResumeSnapshot(
                                    observationIndex = nativeObservationIndex.incrementAndGet(),
                                    observedElapsedMillis = observations.elapsedMillis(),
                                    phase = it.phase,
                                    attempt = it.attempt,
                                    retryAt = it.retryAt,
                                )
                            }.collect(observations::recordNativeSnapshot)
                    }
                withContext(Dispatchers.Main.immediate) {
                    AttachmentDownloadWorker.enqueue(context, request, AttachmentDownloadPriority.Automatic)
                }
                step = "first-run"
                withTimeout(20_000L) { while (states.none { it.state == WorkInfo.State.RUNNING }) delay(25L) }
                val firstRun = states.first { it.state == WorkInfo.State.RUNNING }
                step = "held-prefix"
                val progress = awaitAutomaticProgress(state, request)
                awaitFirstNativeSnapshot(nativeStates)
                assertEquals((bytes.size + 16).toULong(), progress.total)
                HeldAttachmentCancellationProbe.awaitLedger(port) { events ->
                    events.any { it.getString("kind") == "held" }
                }
                assertFalse(store.isInteractive(request))
                assertNull("partial ciphertext must not be published", state.openNativeAttachment(request))
                assertOrdinaryExecution(context)
                shell("input keyevent KEYCODE_HOME")
                withTimeout(5_000L) {
                    while (scenario.state != Lifecycle.State.CREATED) delay(10L)
                }
                withContext(Dispatchers.Main.immediate) { state.setAppInForeground(false) }
                step = "stop-job"
                val job = findScheduledWork(context, firstRun.id.toString())
                val beforeLogs = workerStopLogs()
                HeldAttachmentCancellationProbe.control(port, "/__platform-stop-marker")
                val namespace = if (job.first == null) "" else "-n ${job.first} "
                val nativeObservationBeforeStop = nativeObservationIndex.get()
                val stopped = shell("cmd jobscheduler timeout -u 0 ${namespace}${context.packageName} ${job.second}")
                check(stopped.contains("Timing out:") || stopped.contains("Stopping job:")) {
                    "Android did not stop the selected job: $stopped"
                }
                stoppedAt = SystemClock.elapsedRealtime()
                step = "work-reenqueued"
                val firstRunIndex = states.indexOfFirst { it == firstRun }
                withTimeout(10_000L) {
                    while (states.drop(firstRunIndex + 1).none {
                            it.id == firstRun.id && it.state == WorkInfo.State.ENQUEUED
                        }
                    ) {
                        delay(25L)
                    }
                }
                step = "stop-diagnostics"
                withTimeout(10_000L) { while (workerStopLogs() == beforeLogs) delay(25L) }
                val stopDiagnostic = assertStopDiagnostics(beforeLogs, request)
                assertFalse(store.isInteractive(request))
                HeldAttachmentCancellationProbe.control(port, "/__interrupt-acquisition-held-resume")
                // Native retry and WorkManager backoff are independent. Release the
                // verified live body before waiting for Android to run this WorkSpec again.
                step = "resume-request"
                observations.mark("resumed-acquisition-wait-start")
                awaitResumedAcquisition(port, nativeStates, nativeObservationBeforeStop)
                observations.mark("resumed-acquisition-wait-end")
                val requestedAt = SystemClock.elapsedRealtime()
                step = "resumed-completion"
                observations.mark("resumed-local-read-start")
                assertNull("resumed partial ciphertext cannot be published", state.openNativeAttachment(request))
                observations.mark("resumed-local-read-end")
                val latest = nativeStates.toList().lastOrNull()
                val observedDownloading = nativeDownloadingAfterStop(latest, nativeObservationBeforeStop)
                check(observedDownloading) { "native resumed body is no longer downloading" }
                check(currentResumedBodyHeld(HeldAttachmentCancellationProbe.ledger(port))) {
                    "resumed acquisition is no longer held"
                }
                observations.mark("resumed-release-start")
                HeldAttachmentCancellationProbe.control(port, "/__release-acquisition")
                observations.mark("resumed-release-end")
                step = "resumed-completion"
                withTimeout(60_000L) {
                    while (states.none { it.id == firstRun.id && it.state == WorkInfo.State.SUCCEEDED }) delay(25L)
                }
                step = "resumed-work-query"
                val completedWork = currentSuccessfulRetry(manager, firstRun)
                states.add(completedWork)
                val workSucceededAt = SystemClock.elapsedRealtime()
                assertFalse(store.isInteractive(request))
                assertEquals(Lifecycle.State.CREATED, scenario.state)
                state.openNativeAttachment(request).use { source ->
                    assertArrayEquals(bytes, requireNotNull(source).toByteArray())
                }
                val events =
                    HeldAttachmentCancellationProbe.awaitLedger(port) { e ->
                        e.any { it.getString("kind") == "complete" }
                    }
                assertResumeLedger(events, bytes.size)
                ControlledAttachmentProbe.report(
                    JSONObject()
                        .put("phase", "automatic-platform-resume")
                        .put("success", true)
                        .put("same_work_resumed", true)
                        .put("actual_platform_stop", true)
                        .put("ordinary_work", true)
                        .put("interactive_intent", false)
                        .put("deliberate_retry_used", false)
                        .put("plaintext_exact", true)
                        .put("partial_plaintext_unavailable", true)
                        .put("diagnostics_private", true)
                        .put("activity_stopped", true)
                        .put("android_api", Build.VERSION.SDK_INT)
                        .put("worker_stop_reason", stopDiagnostic.first)
                        .put("worker_run_attempt", stopDiagnostic.second)
                        .put("resume_work_succeeded_ms", workSucceededAt - stoppedAt)
                        .put("resume_request_after_stop_ms", requestedAt - stoppedAt)
                        .put("native_observation_before_stop", nativeObservationBeforeStop)
                        .put("resume_phase_timings", observations.phaseDiagnostics())
                        .put("native_resume_states", nativeResumeDiagnostics(nativeStates))
                        .put("native_initial_states", nativeResumeDiagnostics(observations.initialNativeStates))
                        .put("android_process_restart_qualified", false),
                )
            }
        } catch (failure: Throwable) {
            reportFailure(step, failure, observations, port, stoppedAt)
            throw failure
        } finally {
            withContext(NonCancellable) {
                observer.cancelAndJoin()
                nativeObserver?.cancelAndJoin()
                try {
                    manager.cancelUniqueWork(workName).result.get()
                    state.stopNotificationListenerForAccountTeardown()
                } finally {
                    restoreApplication()
                }
            }
        }
    }

    /** Establishes a first-run observation before capturing the boundary used to reject stale resume evidence. */
    private suspend fun awaitFirstNativeSnapshot(nativeStates: List<NativeResumeSnapshot>) {
        withTimeout(5_000L) { while (nativeStates.isEmpty()) delay(25L) }
    }

    /**
     * Android RUNNING does not start MDK's retry clock. Observe the independent resumed body under a bounded
     * functional deadline, allowing native backoff/maintenance while rejecting terminal decisions immediately.
     * A monotonically indexed native emission after the stop boundary is also required, even if its scheduling
     * fields match the first run. The bounded diagnostic buffer's size is not an observation position.
     * The enclosing probe's 120-second deadline remains in force; this is not a latency qualification.
     */
    private suspend fun awaitResumedAcquisition(
        port: Int,
        nativeStates: List<NativeResumeSnapshot>,
        nativeObservationBeforeStop: Long,
    ) = withTimeout(NATIVE_RESUME_TIMEOUT_MILLIS) {
        while (true) {
            val latest = nativeStates.toList().lastOrNull()
            check(latest?.phase !in NATIVE_TRANSFER_TERMINAL_FAILURES) {
                "automatic native recovery ended as ${latest?.phase}"
            }
            val events = HeldAttachmentCancellationProbe.ledger(port)
            val observedDownloading = nativeDownloadingAfterStop(latest, nativeObservationBeforeStop)
            if (observedDownloading && currentResumedBodyHeld(events)) {
                return@withTimeout
            }
            delay(25L)
        }
    }

    /** Both admission checks require the same post-stop active native body phase. */
    private fun nativeDownloadingAfterStop(
        latest: NativeResumeSnapshot?,
        beforeStop: Long,
    ): Boolean =
        latest != null &&
            latest.observationIndex > beforeStop &&
            latest.phase == AttachmentTransferStateFfi.DOWNLOADING

    /** Prove a new ordinary run from this exact one-time WorkSpec's persisted successful result. */
    private suspend fun currentSuccessfulRetry(
        manager: WorkManager,
        firstRun: WorkInfo,
    ): WorkInfo {
        val started = SystemClock.elapsedRealtime()
        var queried: WorkInfo? = null
        try {
            queried =
                withTimeout(1_500L) {
                    withContext(Dispatchers.IO) {
                        manager.getWorkInfoById(firstRun.id).get(1, TimeUnit.SECONDS)
                    }
                }
        } finally {
            ControlledAttachmentProbe.report(
                JSONObject()
                    .put("phase", "automatic-resume-work-query")
                    .put("state", queried?.state?.name ?: "unavailable")
                    .put("same_work_id", queried?.id == firstRun.id)
                    .put("run_attempt", queried?.runAttemptCount ?: JSONObject.NULL)
                    .put("initial_run_attempt", firstRun.runAttemptCount)
                    .put("elapsed_ms", SystemClock.elapsedRealtime() - started),
            )
        }
        val current = checkNotNull(queried) { "resumed WorkSpec missing" }
        check(current.id == firstRun.id && current.state == WorkInfo.State.SUCCEEDED) {
            "same WorkSpec has not succeeded"
        }
        // One-time success preserves the count incremented atomically when WorkManager starts the new run.
        check(current.runAttemptCount > firstRun.runAttemptCount) { "WorkSpec did not enter a new run" }
        return current
    }

    /** Require the second acquisition's held body to remain live; a historical hold cannot admit release. */
    private fun currentResumedBodyHeld(events: List<JSONObject>): Boolean {
        val acquisitions = events.filter { it.getString("kind") == "get" }
        check(acquisitions.size <= 2) { "unexpected extra acquisition before release" }
        val resumedRequest = acquisitions.getOrNull(1)?.getLong("seq") ?: return false
        val ended =
            events.any {
                it.getString("kind") in setOf("disconnect", "complete") &&
                    it.getLong("request") == resumedRequest
            }
        check(!ended) { "resumed acquisition ended before fixture release" }
        return events.any {
            it.getString("kind") == "held" &&
                it.getLong("request") == resumedRequest &&
                it.getLong("value") == 3L * 1024 * 1024
        }
    }

    /** Remaining delay is relative to serialization time; only closed scheduling facts leave the fixture. */
    private fun nativeResumeDiagnostics(states: List<NativeResumeSnapshot>): JSONArray {
        val nowSeconds = System.currentTimeMillis() / 1_000L
        return JSONArray(
            states.toList().takeLast(FAILURE_STATE_LIMIT).map { snapshot ->
                JSONObject()
                    .put("observation_index", snapshot.observationIndex)
                    .put("observed_elapsed_ms", snapshot.observedElapsedMillis)
                    .put("state", snapshot.phase.name)
                    .put("attempt", snapshot.attempt.toLong())
                    .put(
                        "retry_delay_ms",
                        snapshot.retryAt?.let {
                            (it.toLong() - nowSeconds).coerceAtLeast(0L) * 1_000L
                        } ?: JSONObject.NULL,
                    )
            },
        )
    }

    /** Fail on a terminal native decision rather than disguising refused admission as a progress timeout. */
    private suspend fun awaitAutomaticProgress(
        state: WhiteNoiseAppState,
        request: AttachmentTransferRequest,
    ): NativeAttachmentProgress {
        val progress =
            withTimeout(20_000L) {
                state.nativeProgress(request).first {
                    it?.phase in NATIVE_TRANSFER_TERMINAL_FAILURES ||
                        (it?.phase == AttachmentTransferStateFfi.DOWNLOADING && it.received >= 1024uL * 1024uL)
                }
            }
        check(progress?.phase == AttachmentTransferStateFfi.DOWNLOADING) {
            "automatic native admission ended as ${progress?.phase}"
        }
        return requireNotNull(progress)
    }

    /** Enable only the generated account's document matrix and wait for real validated Android connectivity. */
    private suspend fun enableAutomaticDocumentPolicy(state: WhiteNoiseAppState) {
        withContext(Dispatchers.IO) {
            // The injected test constructor skips process callbacks; register the unchanged real listener explicitly.
            WhiteNoiseAppState::class.java
                .getDeclaredMethod("registerActiveNetworkListener")
                .apply {
                    isAccessible = true
                }.invoke(state)
        }
        withContext(Dispatchers.Main.immediate) {
            state.setAppInForeground(true)
            state.bootstrap()
            MediaAutoDownloadNetwork.entries.forEach { network ->
                state.setMediaAutoDownload(MediaAutoDownloadType.Document, network, enabled = true)
            }
        }
        withTimeout(15_000L) {
            while (!state.hasValidatedInternet() || !state.shouldAutoDownloadMedia(MediaAutoDownloadType.Document)) {
                delay(25L)
            }
        }
        // Host connectivity and Compose policy precede asynchronous native synchronization.
        // Observe a completed real generation; do not grant permission or reset demand in the fixture.
        val revision =
            WhiteNoiseAppState::class.java
                .getDeclaredField("attachmentDownloadPolicyRevision\$delegate")
                .apply { isAccessible = true }
                .get(state) as IntState
        val before = revision.intValue
        withContext(Dispatchers.Main.immediate) { state.refreshNativeAttachmentPermissions() }
        withTimeout(15_000L) { while (revision.intValue == before) delay(25L) }
        ControlledAttachmentProbe.report(
            JSONObject().put("phase", "fixture-stage").put("stage", "automatic-policy-ready"),
        )
    }

    /**
     * Records which step failed and what the platform and server had shown by then, as closed facts only: step name,
     * exception class, work states, native phases/attempts/retry delays, ledger counts and time since the stop.
     * Nothing identifies the generated attachment; a failed run says where it stopped instead of only that it did.
     */
    private suspend fun reportFailure(
        step: String,
        failure: Throwable,
        observations: ResumeObservations,
        port: Int,
        stoppedAt: Long,
    ) {
        val counts =
            withContext(NonCancellable) {
                runCatching { HeldAttachmentCancellationProbe.ledger(port) }
                    .getOrDefault(emptyList())
                    .groupingBy { it.optString("kind") }
                    .eachCount()
            }
        val sinceStop = if (stoppedAt == 0L) JSONObject.NULL else SystemClock.elapsedRealtime() - stoppedAt
        ControlledAttachmentProbe.report(
            JSONObject()
                .put("phase", "automatic-platform-resume-failure")
                .put("step", step)
                .put("exception", failure.javaClass.simpleName)
                .put(
                    "work_states",
                    JSONArray(observations.workStates.takeLast(FAILURE_STATE_LIMIT).map { it.state.name }),
                ).put("resume_phase_timings", observations.phaseDiagnostics())
                .put("native_resume_states", nativeResumeDiagnostics(observations.nativeStates))
                .put("native_initial_states", nativeResumeDiagnostics(observations.initialNativeStates))
                .put("ledger_kinds", JSONObject(counts.filterKeys { it.isNotEmpty() }))
                .put("ms_since_stop", sinceStop),
        )
    }

    /** A real worker stop must emit coarse diagnostics without the generated attachment's identity. */
    private fun assertStopDiagnostics(
        beforeLogs: String,
        request: AttachmentTransferRequest,
    ): Pair<String, Int> {
        val diagnostic =
            workerStopLogs()
                .removePrefix(beforeLogs)
                .lines()
                .filter { it.contains("attachment_work_stopped") }
        assertTrue("missing actual stop reason/attempt", diagnostic.any { it.contains("run_attempt=") })
        val identities = listOf(request.accountRef, request.groupIdHex, request.messageIdHex)
        assertTrue(diagnostic.none { line -> identities.any(line::contains) })
        val marker = Regex("attachment_work_stopped reason=(unavailable|[-0-9]+) run_attempt=([0-9]+)")
        val parsed = diagnostic.mapNotNull(marker::find).single()
        return parsed.groupValues[1] to parsed.groupValues[2].toInt()
    }

    /** Independently committed Range and byte totals must prove compatible checkpoint reuse. */
    private fun assertResumeLedger(
        events: List<JSONObject>,
        size: Int,
    ) {
        assertEquals(2, events.count { it.getString("kind") == "get" })
        val prefix = 2L * 1024 * 1024
        assertEquals(prefix, events.single { it.getString("kind") == "range_requested_offset" }.getLong("value"))
        assertEquals(1, events.single { it.getString("kind") == "if_range_match" }.getInt("value"))
        val transferred = events.filter { it.getString("kind") == "body_bytes" }.sumOf { it.getLong("value") }
        assertEquals(size + 16L, transferred)
    }

    /** Match the actual generated WorkSpec in the own-app scheduler; do not guess an ID or stop other jobs. */
    internal fun findScheduledWork(
        context: Context,
        workId: String,
    ): Pair<String?, Int> {
        val scheduler = context.getSystemService(JobScheduler::class.java)
        val namespaces =
            if (Build.VERSION.SDK_INT >= 34) {
                scheduler.pendingJobsInAllNamespaces
            } else {
                mapOf(null to scheduler.allPendingJobs)
            }
        return namespaces
            .flatMap { (namespace, jobs) -> jobs.map { namespace to it } }
            .single { (_, job) -> job.extras.getString("EXTRA_WORK_SPEC_ID") == workId }
            .let { (namespace, job) -> namespace to job.id }
    }

    /** Automatic work must not accidentally borrow the foreground or user-initiated execution path. */
    @Suppress("DEPRECATION")
    private fun assertOrdinaryExecution(context: Context) {
        val services = context.getSystemService(ActivityManager::class.java).getRunningServices(Int.MAX_VALUE)
        assertFalse(services.any { it.service.className == "androidx.work.impl.foreground.SystemForegroundService" })
        assertFalse(services.any { it.service.className.endsWith("AttachmentUserInitiatedDownloadService") })
    }

    /** Capture only the owned instrumentation process's coarse worker stop marker. */
    private fun workerStopLogs(): String = shell("logcat -d --pid ${Process.myPid()} -s DMAttachmentWorker:W")

    /** The shared fixed-command shell helper is guarded by this Lab-only emulator entry point. */
    private fun shell(command: String): String = PlatformBackgroundAttachmentProbe.shell(command)
}
