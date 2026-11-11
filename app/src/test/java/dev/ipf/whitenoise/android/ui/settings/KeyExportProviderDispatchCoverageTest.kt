package dev.ipf.whitenoise.android.ui.settings

import dev.ipf.whitenoise.android.appModuleSource
import dev.ipf.whitenoise.android.functionBody
import dev.ipf.whitenoise.android.kotlinBlockFrom
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.OutputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * Invariant gate for #3717: a key export never opens, writes, flushes or closes the picked document on the main
 * thread, because a slow or remote document provider would freeze the app while it holds secret material.
 */
class KeyExportProviderDispatchCoverageTest {
    /** The returned-URI callback hands the provider only to [writeKeyExport], through the cancellable save operation. */
    @Test
    fun pickerCallbackReachesTheProviderOnlyThroughTheOffMainWriter() {
        val screen = appModuleSource(ACCOUNT_KEYS)
        val launcher =
            screen.kotlinBlockFrom(
                openDelimiter = screen.indexOf("val exportLauncher"),
                description = "export launcher callback",
                searchForOpening = true,
            )
        val save = screen.functionBody("saveExport")

        assertFalse("the picker callback opens the provider itself", "contentResolver" in launcher)
        assertTrue("the picker callback no longer uses saveExport", "saveExport(" in launcher)
        assertTrue("saveExport must run through the save operation", "ui.saveOperation.start(" in save)
        assertTrue("saveExport must write through writeKeyExport", "writeKeyExport(" in save)
        assertEquals(
            "only writeKeyExport may receive the provider stream",
            1,
            Regex("""\.openOutputStream\(""").findAll(screen).count(),
        )
        assertTrue(
            "stop and dispose must cancel a queued save",
            "saveOperation.cancel()" in screen.functionBody("stopSensitiveOperations"),
        )
        assertTrue(
            "the writer must leave the caller's thread for IO",
            "withContext(Dispatchers.IO)" in appModuleSource(WRITER),
        )
    }

    /** Open, write, flush and close all happen on another thread while the caller's thread stays free. */
    @Test
    fun blockingProviderNeverHoldsTheCallingThread() =
        withSentinelThread { sentinel, sentinelThread ->
            val release = CountDownLatch(1)
            val opened = CountDownLatch(1)
            val threads = mutableListOf<Thread>()
            val output =
                object : ByteArrayOutputStream() {
                    override fun write(
                        b: ByteArray,
                        off: Int,
                        len: Int,
                    ) {
                        threads += Thread.currentThread()
                        super.write(b, off, len)
                    }

                    override fun flush() {
                        threads += Thread.currentThread()
                    }

                    override fun close() {
                        threads += Thread.currentThread()
                    }
                }

            runBlocking {
                val saved =
                    async(sentinel) {
                        writeKeyExport(CONTENT) {
                            threads += Thread.currentThread()
                            opened.countDown()
                            check(release.await(TIMEOUT_SECONDS, TimeUnit.SECONDS)) { "Provider was never released" }
                            output
                        }
                    }
                assertTrue(opened.await(TIMEOUT_SECONDS, TimeUnit.SECONDS))
                // The sentinel still runs other work while the provider blocks.
                assertEquals(sentinelThread, withContext(sentinel) { Thread.currentThread() })
                release.countDown()

                assertTrue(saved.await())
            }
            assertEquals(CONTENT, output.toString(Charsets.UTF_8.name()))
            assertTrue("open, write, flush and close must all be recorded", threads.size >= 4)
            threads.forEach { assertNotSame(sentinelThread, it) }
        }

    /** A provider that cannot open or accept the document reports failure instead of throwing to the screen. */
    @Test
    fun providerFailureReturnsFalse() =
        runBlocking {
            assertFalse(writeKeyExport(CONTENT) { null })
            assertFalse(writeKeyExport(CONTENT) { throw IOException("Synthetic provider refusal") })
            assertFalse(
                writeKeyExport(CONTENT) {
                    object : OutputStream() {
                        override fun write(b: Int): Unit = throw IOException("Synthetic provider write failure")
                    }
                },
            )
        }

    /** A save cancelled before it reaches IO, as on stop, sign-out, wipe or dispose, never opens the provider. */
    @Test
    fun cancelledSaveNeverOpensTheProvider() =
        runBlocking {
            val opens = AtomicInteger()
            val saved =
                async {
                    cancel()
                    writeKeyExport(CONTENT) { ByteArrayOutputStream().also { opens.incrementAndGet() } }
                }
            assertTrue(runCatching { saved.await() }.exceptionOrNull() is CancellationException)
            assertEquals(0, opens.get())
        }

    /** Runs [block] with a single named thread that stands in for the main thread. */
    private fun withSentinelThread(block: (CoroutineDispatcher, Thread) -> Unit) {
        lateinit var thread: Thread
        val executor =
            Executors.newSingleThreadExecutor { runnable -> Thread(runnable, "main-sentinel").also { thread = it } }
        try {
            val dispatcher = executor.asCoroutineDispatcher()
            executor.submit {}.get(TIMEOUT_SECONDS, TimeUnit.SECONDS)
            block(dispatcher, thread)
        } finally {
            executor.shutdownNow()
        }
    }

    private companion object {
        const val ACCOUNT_KEYS = "src/main/java/dev/ipf/whitenoise/android/ui/settings/AccountKeysScreen.kt"
        const val WRITER = "src/main/java/dev/ipf/whitenoise/android/ui/settings/KeyExportWrite.kt"
        const val CONTENT = "synthetic-private-key-for-test"
        const val TIMEOUT_SECONDS = 5L
    }
}
