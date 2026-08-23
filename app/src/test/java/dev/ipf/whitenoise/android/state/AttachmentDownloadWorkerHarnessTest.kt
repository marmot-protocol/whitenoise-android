package dev.ipf.whitenoise.android.state

import androidx.work.ListenableWorker
import dev.ipf.whitenoise.android.WhiteNoiseApplication
import dev.ipf.whitenoise.android.work.WorkerHarnessFixtures
import dev.ipf.whitenoise.android.work.WorkerTestHarness
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.util.UUID

@RunWith(RobolectricTestRunner::class)
@Config(application = WhiteNoiseApplication::class, sdk = [36])
class AttachmentDownloadWorkerHarnessTest {
    private lateinit var harness: WorkerTestHarness

    @Before
    fun setUp() {
        val application = RuntimeEnvironment.getApplication() as WhiteNoiseApplication
        harness = WorkerTestHarness(application)
        harness.appState.workerTestHooks?.ensureNotificationRuntimeStarted = {}
    }

    @After
    fun tearDown() {
        harness.tearDown()
    }

    @Test
    fun doWork_successPath() =
        runBlocking {
            val request = transferRequest()
            harness.appState.workerTestHooks?.downloadAttachmentForDurableWork = { _, _ -> true }

            val result = harness.runAttachmentWorker(request)

            assertTrue(result is ListenableWorker.Result.Success)
        }

    @Test
    fun doWork_transientFailure_retriesOnce() =
        runBlocking {
            val request = transferRequest()
            val requestId = UUID.randomUUID()
            harness.appState.workerTestHooks?.downloadAttachmentForDurableWork = { _, _ ->
                throw java.io.IOException("relay timeout")
            }

            val first = harness.runAttachmentWorker(request, requestId, runAttemptCount = 0)
            val terminal = harness.runAttachmentWorker(request, requestId, runAttemptCount = 1)

            assertTrue(first is ListenableWorker.Result.Retry)
            assertTrue(terminal is ListenableWorker.Result.Failure)
        }

    @Test
    fun doWork_permanentFailure_doesNotRetry() =
        runBlocking {
            val request = transferRequest()
            harness.appState.workerTestHooks?.downloadAttachmentForDurableWork = { _, _ ->
                throw dev.ipf.marmotkit.MarmotKitException
                    .InvalidMediaReference("bad media")
            }

            val result = harness.runAttachmentWorker(request)

            assertTrue(result is ListenableWorker.Result.Failure)
        }

    @Test
    fun doWork_automaticPausedAccount_returnsSuccessWithoutDownloading() =
        runBlocking {
            val request = transferRequest()
            val downloadCalled =
                java.util.concurrent.atomic
                    .AtomicBoolean(false)
            harness.appState.workerTestHooks?.downloadAttachmentForDurableWork = { _, _ ->
                downloadCalled.set(true)
                true
            }
            val intentStore =
                AttachmentDownloadIntentStore(
                    harness.context.getSharedPreferences("whitenoise", android.content.Context.MODE_PRIVATE),
                )
            intentStore.pauseAutomatic(request.accountRef)

            val result = harness.runAttachmentWorker(request)

            assertTrue(result is ListenableWorker.Result.Success)
            assertTrue(!downloadCalled.get())
        }

    @Test
    fun doWork_invalidInput_fails() =
        runBlocking {
            val result =
                harness.runWorker(
                    AttachmentDownloadWorker::class.java,
                    androidx.work.workDataOf("account_ref" to "missing-fields"),
                )

            assertTrue(result is ListenableWorker.Result.Failure)
        }

    private fun transferRequest(): AttachmentTransferRequest =
        AttachmentTransferRequest(
            accountRef = WorkerHarnessFixtures.ACCOUNT_REF,
            groupIdHex = WorkerHarnessFixtures.GROUP_ID_HEX,
            messageIdHex = WorkerHarnessFixtures.MESSAGE_ID_HEX,
            attachmentIndex = 0,
        )

    private suspend fun WorkerTestHarness.runAttachmentWorker(
        request: AttachmentTransferRequest,
        requestId: UUID = UUID.randomUUID(),
        runAttemptCount: Int = 0,
    ): ListenableWorker.Result {
        val input = AttachmentDownloadWorkData.encode(request)
        return runWorker(AttachmentDownloadWorker::class.java, input, requestId, runAttemptCount)
    }
}
