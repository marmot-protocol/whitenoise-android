package dev.ipf.whitenoise.android.ui.group

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.io.OutputStream

class ConversationTranscriptSaveTest {
    @get:Rule
    val temporary = TemporaryFolder()

    @Test
    fun cancelledPickerReleasesRequestWithoutExporting() {
        var exports = 0
        val owner =
            TranscriptSaveOwner(current = { true }, export = {
                exports++
                null
            })
        val requests = TranscriptSaveRequests()
        assertNotNull(requests.begin(owner))
        assertNull(requests.claimResult(accepted = false))
        assertFalse(requests.busy)
        assertEquals(0, exports)
        assertNotNull(requests.begin(owner))
    }

    @Test
    fun stalePickerCannotBeReboundToANewOwnerOrConsumedTwice() {
        val old = fileOwner()
        val current = fileOwner()
        val requests = TranscriptSaveRequests()
        val oldRequest = requireNotNull(requests.begin(old))
        old.invalidate()
        assertNull(requests.begin(current))
        assertNull(requests.claimResult(accepted = true))
        val next = requests.begin(current)
        requests.finish(oldRequest)
        assertTrue(requests.busy)
        assertSame(next, requests.claimResult(accepted = true))
        assertNull(requests.claimResult(accepted = true))
        assertNull(requests.begin(current))
        requests.finish(requireNotNull(next))
        assertFalse(requests.busy)
    }

    @Test
    fun changedAccountRuntimeOrControllerRejectsThePendingResult() {
        for (changedField in 0..2) {
            val identity = mutableListOf("account-a", "runtime-1", "controller-a")
            val original = identity.toList()
            val owner = TranscriptSaveOwner(current = { identity == original }, export = { null })
            val requests = TranscriptSaveRequests()
            requests.begin(owner)
            identity[changedField] = "replacement"
            assertNull(requests.claimResult(accepted = true))
            assertFalse(requests.busy)
        }
    }

    @Test
    fun savesExactNativeFileOffMainAndRemovesItsTemporaryDirectory() =
        runBlocking {
            val caller = Thread.currentThread()
            val output = ByteArrayOutputStream()
            val owner =
                TranscriptSaveOwner(current = { true }, export = { directory ->
                    assertNotSame(caller, Thread.currentThread())
                    File(directory, "native.json").apply { writeText("{\"accepted\":true}") }
                })
            saveConversationTranscript(temporary.root, owner, openOutput = {
                assertNotSame(caller, Thread.currentThread())
                output
            }, discardOutput = { error("Successful output must remain") })
            assertEquals("{\"accepted\":true}", output.toString(Charsets.UTF_8.name()))
            assertNoTemporaryFiles()
        }

    @Test
    fun ownerChangeDuringNativeExportNeverOpensTheDestination() =
        runBlocking {
            var opened = false
            lateinit var owner: TranscriptSaveOwner
            owner =
                TranscriptSaveOwner(current = { true }, export = { directory ->
                    File(directory, "native.json").apply { writeText("private") }.also { owner.invalidate() }
                })
            expectCancellation {
                saveConversationTranscript(temporary.root, owner, {
                    opened = true
                    ByteArrayOutputStream()
                }, {})
            }
            assertFalse(opened)
            assertNoTemporaryFiles()
        }

    @Test
    fun cancellationDuringNativePreparationCleansEvenAnUnreturnedFile() =
        runBlocking {
            val prepared = CompletableDeferred<Unit>()
            val owner =
                TranscriptSaveOwner(current = { true }, export = { directory ->
                    File(directory, "native.json").writeText("private")
                    prepared.complete(Unit)
                    awaitCancellation()
                })
            val job =
                launch {
                    saveConversationTranscript(temporary.root, owner, { error("Must not open") }, {})
                }
            prepared.await()
            job.cancelAndJoin()
            assertNoTemporaryFiles()
        }

    @Test
    fun nullNativeExportFailsAndCleansTheDestinationWithoutOpeningIt() =
        runBlocking {
            var discarded = false
            val owner = TranscriptSaveOwner(current = { true }, export = { null })
            expectIoFailure {
                saveConversationTranscript(temporary.root, owner, { error("Must not open") }, { discarded = true })
            }
            assertTrue(discarded)
            assertNoTemporaryFiles()
        }

    @Test
    fun nullOutputStreamFailsAndDeletesTemporaryPlaintext() =
        runBlocking {
            var discarded = false
            expectIoFailure {
                saveConversationTranscript(temporary.root, fileOwner(), { null }, { discarded = true })
            }
            assertTrue(discarded)
            assertNoTemporaryFiles()
        }

    @Test
    fun failedWriteClosesAndDiscardsPartialOutputAndTemporaryPlaintext() =
        runBlocking {
            var closed = false
            var discarded = false
            val output =
                object : OutputStream() {
                    override fun write(value: Int): Unit = throw IOException("Provider write failed")

                    override fun close() {
                        closed = true
                    }
                }
            expectIoFailure {
                saveConversationTranscript(temporary.root, fileOwner(), { output }, { discarded = true })
            }
            assertTrue(closed)
            assertTrue(discarded)
            assertNoTemporaryFiles()
        }

    @Test
    fun ownerChangeDuringWriteDiscardsAlreadyWrittenBytesAndStopsTheNextChunk() =
        runBlocking {
            var written = 0
            var discarded = false
            val owner = fileOwner(content = "x".repeat(DEFAULT_BUFFER_SIZE * 3))
            val output =
                object : OutputStream() {
                    override fun write(value: Int) = error("Bulk writes expected")

                    override fun write(
                        bytes: ByteArray,
                        offset: Int,
                        length: Int,
                    ) {
                        written += length
                        owner.invalidate()
                    }
                }
            expectCancellation {
                saveConversationTranscript(temporary.root, owner, { output }, { discarded = true })
            }
            assertEquals(DEFAULT_BUFFER_SIZE, written)
            assertTrue(discarded)
            assertNoTemporaryFiles()
        }

    @Test
    fun temporaryCleanupFailureAfterSuccessIsObservable() {
        try {
            cleanupTranscriptTemporaryDirectory(temporary.root, originalFailure = null, deleteDirectory = { false })
            error("Expected cleanup failure")
        } catch (failure: IOException) {
            assertTrue(failure.message.orEmpty().contains("temporary transcript"))
        }
    }

    @Test
    fun temporaryCleanupFailurePreservesTheOriginalWriteOrCancellationCause() {
        val causes = listOf(IOException("Write failed"), CancellationException("Owner changed"))
        for (cause in causes) {
            cleanupTranscriptTemporaryDirectory(temporary.root, originalFailure = cause, deleteDirectory = { false })
            assertEquals(1, cause.suppressed.size)
            assertTrue(cause.suppressed.single() is IOException)
        }
    }

    private fun fileOwner(content: String = "native accepted transcript"): TranscriptSaveOwner =
        TranscriptSaveOwner(current = { true }, export = { directory ->
            File(directory, "native.json").apply { writeText(content) }
        })

    private fun assertNoTemporaryFiles() {
        assertTrue(
            temporary.root
                .listFiles()
                .orEmpty()
                .isEmpty(),
        )
    }

    private suspend fun expectIoFailure(block: suspend () -> Unit) {
        try {
            block()
            error("Expected an IO failure")
        } catch (_: IOException) {
            // The production failure is propagated for native copyable feedback.
        }
    }

    private suspend fun expectCancellation(block: suspend () -> Unit) {
        try {
            block()
            error("Expected cancellation")
        } catch (_: CancellationException) {
            // Cancellation remains cancellation and never becomes a success.
        }
    }
}
