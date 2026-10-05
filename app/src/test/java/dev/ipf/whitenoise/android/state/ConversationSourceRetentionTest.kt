package dev.ipf.whitenoise.android.state

import dev.ipf.whitenoise.android.share.PrivateShareFiles
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.job
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withContext
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.nio.file.Files

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class ConversationSourceRetentionTest {
    /** Navigating through the retention window cannot release a queued send's private source. */
    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun queuedUploadSurvivesConversationOverflowThenReleasesItsSourceAfterSettlement() =
        runTest {
            Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
            val root = Files.createTempDirectory("queued-share-retention").toFile()
            val state = mediaSendReconciliationAppState()
            try {
                val files = PrivateShareFiles(root, "test.private-share")
                val (uri, file) = files.newFile()
                file.writeBytes(byteArrayOf(1))
                files.finish(uri, "shared.txt", "text/plain", 1)
                files.leases.holdSend("queued", listOf(uri))
                val retained = RetainedMediaUpload(emptyList(), null)
                retained.retainSource { files.leases.releaseSend("queued") }
                val cache = state.retainedMediaUploads("account", "queued-chat")
                cache.put("queued", retained)
                val activeKeys = state.activeUploadKeys("account", "queued-chat")
                activeKeys.add("queued")
                val job = state.trackInFlightMediaUpload("account", "queued-chat", "queued")

                repeat(40) { state.retainedMediaUploads("account", "other-$it") }

                assertSame(retained, cache.get("queued"))
                assertNotNull(files.metadata(uri))
                activeKeys.remove("queued")
                state.untrackInFlightMediaUpload("account", "queued-chat", "queued", job)
                state.retainedMediaUploads("account", "settled-chat")
                assertNull(cache.get("queued"))
                assertNull(files.resolve(uri))
            } finally {
                withContext(NonCancellable) { state.mutationsScope.coroutineContext.job.cancelAndJoin() }
                root.deleteRecursively()
                Dispatchers.resetMain()
            }
        }
}
