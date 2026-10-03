package dev.ipf.whitenoise.android.media

import android.app.ActivityManager
import android.app.KeyguardManager
import android.app.job.JobScheduler
import android.content.Context
import android.os.Build
import android.os.PowerManager
import android.os.SystemClock
import androidx.activity.ComponentActivity
import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import androidx.work.WorkInfo
import androidx.work.WorkManager
import dev.ipf.marmotkit.AttachmentTransferStateFfi
import dev.ipf.marmotkit.MediaAttachmentReferenceFfi
import dev.ipf.whitenoise.android.WhiteNoiseApplication
import dev.ipf.whitenoise.android.state.AttachmentDownloadPriority
import dev.ipf.whitenoise.android.state.AttachmentDownloadWorker
import dev.ipf.whitenoise.android.state.AttachmentTransferRequest
import dev.ipf.whitenoise.android.state.AttachmentUserInitiatedDownloadService
import dev.ipf.whitenoise.android.state.AttachmentUserInitiatedDownloads
import dev.ipf.whitenoise.android.state.WhiteNoiseAppState
import dev.ipf.whitenoise.android.state.attachmentDownloadWorkName
import dev.ipf.whitenoise.android.state.attachmentIntentStore
import dev.ipf.whitenoise.android.state.attachmentJobId
import dev.ipf.whitenoise.android.state.downloadAttachmentPlaintextSource
import dev.ipf.whitenoise.android.state.nativeProgress
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.json.JSONObject
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue

/** Runs the shipping Android scheduler against only the generated Lab runtime while a real Activity leaves TOP. */
internal object PlatformBackgroundAttachmentProbe {
    /** The real scheduled transfer must keep its single native body moving during thirty seconds behind Home. */
    @Suppress("LongMethod") // One guarded lifetime owns the real job, application binding and Activity.
    suspend fun run(
        state: WhiteNoiseAppState,
        request: AttachmentTransferRequest,
        reference: MediaAttachmentReferenceFfi,
        port: Int,
        bytes: ByteArray,
        lockScreen: Boolean = false,
    ) = coroutineScope {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        check(context.packageName == "dev.ipf.whitenoise.android.medialatency")
        check(shell("getprop ro.kernel.qemu").trim() == "1")
        val originalKeyguardDisabled =
            if (lockScreen) originalKeyguardSetting() else null
        val restoreApplication = bindGeneratedApplication(context, state)
        val workName = attachmentDownloadWorkName(request)
        val intentStore = attachmentIntentStore(context)
        try {
            if (lockScreen) {
                shell("locksettings set-disabled false")
                check(shell("locksettings get-disabled").trim() == "false")
            }
            HeldAttachmentCancellationProbe.control(port, "/__hold-resumable-acquisition")
            ActivityScenario.launch(ComponentActivity::class.java).use { scenario ->
                withContext(Dispatchers.Main.immediate) {
                    state.setAppInForeground(true)
                    state.bootstrap()
                }
                val read =
                    async {
                        state
                            .downloadAttachmentPlaintextSource(request, reference, persistInteractiveIntent = false)
                            .use { assertArrayEquals(bytes, it.toByteArray()) }
                    }
                withTimeout(10_000L) {
                    state.nativeProgress(request).first {
                        it?.phase == AttachmentTransferStateFfi.DOWNLOADING && it.received >= 1024uL * 1024uL
                    }
                }
                HeldAttachmentCancellationProbe.awaitLedger(port) { events ->
                    events.any { it.getString("kind") == "held" }
                }
                withContext(Dispatchers.Main.immediate) {
                    AttachmentDownloadWorker.enqueue(
                        context,
                        request,
                        AttachmentDownloadPriority.Interactive,
                        userVisible = true,
                    )
                }
                awaitActualScheduler(context, request)
                assertTrue(intentStore.isInteractive(request))
                HeldAttachmentCancellationProbe.control(port, "/__pace-background-acquisition")
                HeldAttachmentCancellationProbe.control(port, "/__background-start")
                val started = SystemClock.elapsedRealtime()
                shell("input keyevent KEYCODE_HOME")
                withTimeout(5_000L) {
                    while (scenario.state != Lifecycle.State.CREATED) delay(10L)
                }
                withContext(Dispatchers.Main.immediate) { state.setAppInForeground(false) }
                if (lockScreen) {
                    shell("input keyevent KEYCODE_SLEEP")
                    withTimeout(5_000L) { while (!isLocked(context)) delay(10L) }
                }
                HeldAttachmentCancellationProbe.control(port, "/__release-acquisition")
                delay(30_000L)
                assertEquals("fixture Activity returned to TOP unexpectedly", Lifecycle.State.CREATED, scenario.state)
                if (lockScreen) assertTrue("device unlocked during the transfer", isLocked(context))
                assertFalse("transfer finished before the sustained background interval", read.isCompleted)
                assertTrue("platform execution stopped behind Home", actualExecutionRunning(context, request))
                val backgroundMillis = SystemClock.elapsedRealtime() - started
                HeldAttachmentCancellationProbe.control(port, "/__background-end")
                val during = HeldAttachmentCancellationProbe.ledger(port)
                val start = during.single { it.getString("kind") == "background_start" }.getLong("seq")
                val end = during.single { it.getString("kind") == "background_end" }.getLong("seq")
                val continuedBytes =
                    during
                        .filter { it.getString("kind") == "body_bytes" && it.getLong("seq") in (start + 1)..<end }
                        .sumOf { it.getLong("value") }
                assertTrue("HTTP body made no progress behind Home", continuedBytes > 0)
                assertTrue(backgroundMillis >= 30_000L)
                withTimeout(30_000L) { read.await() }
                withTimeout(10_000L) { while (intentStore.isInteractive(request)) delay(10L) }
                assertFalse(intentStore.isInteractive(request))
                val events = HeldAttachmentCancellationProbe.ledger(port)
                assertEquals(1, events.count { it.getString("kind") == "get" })
                assertEquals(0, events.count { it.getString("kind") == "disconnect" })
                ControlledAttachmentProbe.report(
                    JSONObject()
                        .put("phase", "platform-background")
                        .put("success", true)
                        .put("background_millis", backgroundMillis)
                        .put("body_bytes_while_backgrounded", continuedBytes)
                        .put("activity_stopped", true)
                        .put("transfer_active_after_30_seconds", true)
                        .put("screen_lock_qualified", lockScreen)
                        .put("actual_execution_class", executionClass())
                        .put("plaintext_exact", true)
                        .put("interactive_intent_retired", true)
                        .put("platform_job_stop_qualified", false),
                )
            }
        } finally {
            withContext(NonCancellable) {
                try {
                    if (lockScreen) {
                        shell("input keyevent KEYCODE_WAKEUP")
                        shell("wm dismiss-keyguard")
                        shell("locksettings set-disabled $originalKeyguardDisabled")
                    }
                    if (Build.VERSION.SDK_INT >= 34) AttachmentUserInitiatedDownloads.cancel(context, request)
                    WorkManager
                        .getInstance(context)
                        .cancelUniqueWork(workName)
                        .result
                        .get()
                    intentStore.setInteractive(request, false)
                    state.stopNotificationListenerForAccountTeardown()
                } finally {
                    restoreApplication()
                }
            }
        }
    }

    /** Capture only the emulator's boolean setting so the test can restore its original keyguard configuration. */
    private fun originalKeyguardSetting(): String {
        val original = shell("locksettings get-disabled").trim()
        check(original in setOf("true", "false"))
        return original
    }

    /** Screen-off alone is insufficient: the generated emulator must also be behind Android keyguard. */
    private fun isLocked(context: Context): Boolean =
        !context.getSystemService(PowerManager::class.java).isInteractive &&
            context.getSystemService(KeyguardManager::class.java).isKeyguardLocked

    /** Inspect the actual service and registered platform job rather than a synthetic worker builder. */
    private suspend fun awaitActualScheduler(
        context: Context,
        request: AttachmentTransferRequest,
    ) {
        withTimeout(15_000L) {
            while (!actualExecutionRunning(context, request)) delay(25L)
        }
    }

    /** Older Android must have a foreground WorkManager service; API34+ must run the user-initiated JobService. */
    @Suppress("DEPRECATION") // Android exposes only this own-process service inspection to instrumentation.
    private fun actualExecutionRunning(
        context: Context,
        request: AttachmentTransferRequest,
    ): Boolean {
        val manager = context.getSystemService(ActivityManager::class.java)
        val services = manager.getRunningServices(Int.MAX_VALUE)
        if (Build.VERSION.SDK_INT >= 34) {
            val scheduler = context.getSystemService(JobScheduler::class.java).forNamespace("attachment_download_v1")
            val job = scheduler.getPendingJob(attachmentJobId(request))
            return job?.isUserInitiated == true &&
                services.any {
                    it.service.className == AttachmentUserInitiatedDownloadService::class.java.name
                }
        }
        val work = WorkManager.getInstance(context).getWorkInfosForUniqueWork(attachmentDownloadWorkName(request)).get()
        return work.any { it.state == WorkInfo.State.RUNNING } &&
            services.any {
                it.foreground && it.service.className == "androidx.work.impl.foreground.SystemForegroundService"
            }
    }

    /** Replace only the Lab application's lazy state; platform jobs, native calls and HTTP remain real. */
    private suspend fun bindGeneratedApplication(
        context: Context,
        state: WhiteNoiseAppState,
    ): suspend () -> Unit =
        withContext(Dispatchers.Main.immediate) {
            val application = context.applicationContext as WhiteNoiseApplication
            val field =
                WhiteNoiseApplication::class.java.getDeclaredField("appStateDelegate").apply { isAccessible = true }
            val original = field.get(application)
            field.set(application, lazy { state })
            check(application.appState === state)
            val restore: suspend () -> Unit = {
                withContext(Dispatchers.Main.immediate) { field.set(application, original) }
            }
            restore
        }

    /** Commands contain only fixed fixture controls and are guarded by Lab identity plus an emulator check. */
    private fun shell(command: String): String =
        InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand(command).use { descriptor ->
            android.os.ParcelFileDescriptor
                .AutoCloseInputStream(descriptor)
                .bufferedReader()
                .use { it.readText() }
        }

    /** Report scheduler classification separately from transport retry and platform interruption. */
    private fun executionClass(): String = if (Build.VERSION.SDK_INT >= 34) "user-initiated-job" else "foreground-work"
}
