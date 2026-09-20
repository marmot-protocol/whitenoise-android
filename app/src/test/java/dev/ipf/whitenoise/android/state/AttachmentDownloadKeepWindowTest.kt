package dev.ipf.whitenoise.android.state

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.work.Configuration
import androidx.work.ListenableWorker
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.WorkerFactory
import androidx.work.WorkerParameters
import androidx.work.testing.SynchronousExecutor
import androidx.work.testing.WorkManagerTestInitHelper
import dev.ipf.whitenoise.android.WhiteNoiseApplication
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Real unique-work admission, with virtual time and a synthetic completed-body boundary. */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], application = WhiteNoiseApplication::class)
class AttachmentDownloadKeepWindowTest {
    @Test
    fun repeatedAutomaticEnqueuesKeepOneOwnerForTheFormerBackoffWindow() =
        runTest {
            val fixture = fixture { _, _, _ -> false }
            try {
                fixture.enqueue()
                val first = fixture.info().id
                fixture.start()
                runCurrent()
                assertEquals(WorkInfo.State.RUNNING, fixture.info().state)

                repeat(20) { fixture.enqueue() }
                advanceTimeBy(29_999)
                runCurrent()
                fixture.enqueue()
                assertEquals(first, fixture.info().id)
                assertEquals(WorkInfo.State.RUNNING, fixture.info().state)
                assertEquals(1, fixture.downloads)

                advanceTimeBy(1)
                runCurrent()
                assertEquals(WorkInfo.State.FAILED, fixture.info().state)
                assertEquals(1, fixture.downloads)
                assertFalse(fixture.intents.isAutomaticSuppressed(fixture.request))

                // This is a finite scheduling hold, not a durable acquisition ledger.
                fixture.enqueue()
                assertNotEquals(first, fixture.info().id)
                assertEquals(WorkInfo.State.ENQUEUED, fixture.info().state)
            } finally {
                fixture.close()
                runCurrent()
            }
        }

    @Test
    fun cancellingDuringTheHoldStopsWorkAndPreventsAutomaticRearming() =
        runTest {
            val fixture = fixture { _, _, _ -> false }
            try {
                fixture.enqueue()
                fixture.start()
                runCurrent()
                AttachmentDownloadWorker.cancelForRequest(fixture.context, fixture.request)
                runCurrent()
                advanceTimeBy(30_000)
                fixture.enqueue()
                runCurrent()
                assertEquals(WorkInfo.State.CANCELLED, fixture.info().state)
                assertEquals(1, fixture.downloads)
            } finally {
                fixture.close()
                runCurrent()
            }
        }

    @Test
    fun anExplicitRequestJoiningTheHoldRetainsItsDurableDownloadIntent() =
        runTest {
            val priorities = mutableListOf<AttachmentDownloadPriority>()
            val fixture =
                fixture { _, _, priority ->
                    priorities += priority
                    priority == AttachmentDownloadPriority.Interactive
                }
            try {
                fixture.enqueue()
                fixture.start()
                runCurrent()
                fixture.enqueue(AttachmentDownloadPriority.Interactive)
                advanceTimeBy(30_000)
                runCurrent()
                assertEquals(WorkInfo.State.ENQUEUED, fixture.info().state)
                assertEquals(1, fixture.downloads)

                // TestDriver admits the backed-off explicit retry without wall-clock waiting.
                fixture.start()
                runCurrent()
                assertEquals(WorkInfo.State.SUCCEEDED, fixture.info().state)
                assertEquals(
                    listOf(AttachmentDownloadPriority.Automatic, AttachmentDownloadPriority.Interactive),
                    priorities,
                )
                assertFalse(fixture.intents.isInteractive(fixture.request))
            } finally {
                fixture.close()
                runCurrent()
            }
        }

    private fun TestScope.fixture(download: PerformDurableAttachmentDownload): Fixture =
        Fixture().also { fixture ->
            val factory =
                object : WorkerFactory() {
                    override fun createWorker(
                        appContext: Context,
                        workerClassName: String,
                        workerParameters: WorkerParameters,
                    ): ListenableWorker? {
                        if (workerClassName != AttachmentDownloadWorker::class.java.name) return null
                        return AttachmentDownloadWorker(appContext, workerParameters) { app, request, priority ->
                            fixture.downloads += 1
                            download(app, request, priority)
                        }
                    }
                }
            WorkManagerTestInitHelper.initializeTestWorkManager(
                fixture.context,
                Configuration
                    .Builder()
                    .setExecutor(SynchronousExecutor())
                    .setTaskExecutor(SynchronousExecutor())
                    .setWorkerCoroutineContext(StandardTestDispatcher(testScheduler))
                    .setWorkerFactory(factory)
                    .build(),
            )
        }

    private class Fixture {
        val context: Context = ApplicationProvider.getApplicationContext<WhiteNoiseApplication>()
        val request = AttachmentTransferRequest("account-a", "ab".repeat(16), "cd".repeat(32), 0)
        val intents =
            AttachmentDownloadIntentStore(context.getSharedPreferences("whitenoise", Context.MODE_PRIVATE))
        var downloads = 0

        fun enqueue(priority: AttachmentDownloadPriority = AttachmentDownloadPriority.Automatic) {
            AttachmentDownloadWorker.enqueue(context, request, priority)
        }

        fun info(): WorkInfo =
            WorkManager
                .getInstance(context)
                .getWorkInfosForUniqueWork(attachmentDownloadWorkName(request))
                .get()
                .single()

        fun start() {
            checkNotNull(WorkManagerTestInitHelper.getTestDriver(context)).setAllConstraintsMet(info().id)
        }

        fun close() {
            WorkManager
                .getInstance(context)
                .cancelAllWork()
                .result
                .get()
        }
    }
}
