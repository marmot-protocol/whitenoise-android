package dev.ipf.whitenoise.android.state

import android.content.Context
import android.content.ContextWrapper
import androidx.test.core.app.ApplicationProvider
import androidx.work.ListenableWorker
import androidx.work.ListenableWorker.Result
import androidx.work.WorkerFactory
import androidx.work.WorkerParameters
import androidx.work.testing.TestListenableWorkerBuilder
import androidx.work.testing.WorkManagerTestInitHelper
import dev.ipf.marmotkit.MarmotKitException
import dev.ipf.whitenoise.android.WhiteNoiseApplication
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

private typealias DownloadOverride = PerformDurableAttachmentDownload

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], application = WhiteNoiseApplication::class)
class AttachmentDownloadWorkerClassTest {
    private lateinit var application: WhiteNoiseApplication
    private lateinit var appContext: Context

    @Before
    fun setUp() {
        application = ApplicationProvider.getApplicationContext()
        appContext = application.applicationContext
        WorkManagerTestInitHelper.initializeTestWorkManager(application)
    }

    @Test
    fun doWorkFailsWhenInputDataIsInvalid() =
        runTest {
            val worker =
                TestListenableWorkerBuilder
                    .from<AttachmentDownloadWorker>(appContext, AttachmentDownloadWorker::class.java)
                    .build()

            assertTrue(worker.doWork() is Result.Failure)
        }

    @Test
    fun doWorkFailsWhenApplicationIsNotWhiteNoiseApplication() =
        runTest {
            val wrapped = NonWhiteNoiseApplicationContext(appContext)
            val worker =
                TestListenableWorkerBuilder
                    .from<AttachmentDownloadWorker>(wrapped, AttachmentDownloadWorker::class.java)
                    .setInputData(AttachmentDownloadWorkData.encode(testRequest()))
                    .build()

            assertTrue(worker.doWork() is Result.Failure)
        }

    @Test
    fun doWorkSucceedsWhenAutomaticDownloadsArePausedForTheAccount() =
        runTest {
            val request = testRequest()
            val preferences = appContext.getSharedPreferences("whitenoise", Context.MODE_PRIVATE)
            AttachmentDownloadIntentStore(preferences).apply {
                markOpenIntent(request)
                pauseAutomatic(request.accountRef)
            }

            val worker =
                TestListenableWorkerBuilder
                    .from<AttachmentDownloadWorker>(appContext, AttachmentDownloadWorker::class.java)
                    .setInputData(AttachmentDownloadWorkData.encode(request))
                    .build()

            assertEquals(Result.success(), worker.doWork())
        }

    @Test
    fun doWorkSucceedsWhenDurableDownloadCompletes() =
        runTest {
            val worker =
                buildWorkerWithDownloadOverride(downloadOverride = { _, _, _ -> true })

            assertEquals(Result.success(), worker.doWork())
        }

    @Test
    fun doWorkRetriesTransientDownloadFailuresOnce() =
        runTest {
            val worker =
                buildWorkerWithDownloadOverride(
                    downloadOverride = { _, _, _ -> false },
                    runAttemptCount = 0,
                )

            assertEquals(Result.retry(), worker.doWork())
        }

    @Test
    fun doWorkFailsTerminalDownloadErrorsWithoutRetry() =
        runTest {
            val worker =
                buildWorkerWithDownloadOverride(
                    downloadOverride = { _, _, _ ->
                        throw MarmotKitException.InvalidMediaReference("media decryption failed")
                    },
                )

            assertEquals(Result.failure(), worker.doWork())
        }

    private fun buildWorkerWithDownloadOverride(
        downloadOverride: DownloadOverride,
        runAttemptCount: Int? = null,
    ): AttachmentDownloadWorker {
        val builder =
            TestListenableWorkerBuilder
                .from<AttachmentDownloadWorker>(appContext, AttachmentDownloadWorker::class.java)
                .setWorkerFactory(downloadWorkerFactory(downloadOverride))
                .setInputData(AttachmentDownloadWorkData.encode(testRequest()))
        if (runAttemptCount != null) {
            builder.setRunAttemptCount(runAttemptCount)
        }
        return builder.build()
    }

    private fun downloadWorkerFactory(downloadOverride: DownloadOverride): WorkerFactory =
        object : WorkerFactory() {
            override fun createWorker(
                appContext: Context,
                workerClassName: String,
                workerParameters: WorkerParameters,
            ): ListenableWorker? {
                if (workerClassName != AttachmentDownloadWorker::class.java.name) return null
                return AttachmentDownloadWorker(appContext, workerParameters, downloadOverride)
            }
        }

    private fun testRequest(): AttachmentTransferRequest =
        AttachmentTransferRequest(
            accountRef = "account-a",
            groupIdHex = "ab".repeat(32),
            messageIdHex = "cd".repeat(32),
            attachmentIndex = 0,
        )

    private class NonWhiteNoiseApplicationContext(
        base: Context,
    ) : ContextWrapper(base) {
        override fun getApplicationContext(): Context = this
    }
}
