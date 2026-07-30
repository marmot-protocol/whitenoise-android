package dev.ipf.whitenoise.android.state

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class CoalescingDraftWriterTest {
    private val executor = Executors.newSingleThreadExecutor()

    @After
    fun tearDown() {
        executor.shutdownNow()
    }

    @Test
    fun writeReturnsWhileEncryptionIsStillBlockedOffThread() {
        val persistStarted = CountDownLatch(1)
        val releasePersist = CountDownLatch(1)
        val writer =
            CoalescingDraftWriter(emptyMap(), executor) {
                persistStarted.countDown()
                releasePersist.await()
            }

        writer.write("chat", "draft")

        assertTrue(persistStarted.await(5, TimeUnit.SECONDS))
        assertEquals(mapOf("chat" to "draft"), writer.read())
        releasePersist.countDown()
        writer.flush()
    }

    @Test
    fun rapidUpdatesPersistOnlyTheLatestCompleteSnapshotAfterTheActiveWrite() {
        val firstPersistStarted = CountDownLatch(1)
        val releaseFirstPersist = CountDownLatch(1)
        val persisted = mutableListOf<Map<String, String>>()
        val writer =
            CoalescingDraftWriter(emptyMap(), executor) { snapshot ->
                val persistCount =
                    synchronized(persisted) {
                        persisted += snapshot
                        persisted.size
                    }
                if (persistCount == 1) {
                    firstPersistStarted.countDown()
                    releaseFirstPersist.await()
                }
            }

        writer.write("a", "first")
        assertTrue(firstPersistStarted.await(5, TimeUnit.SECONDS))
        writer.write("a", "latest")
        writer.write("b", "second")
        writer.write("b", null)
        releaseFirstPersist.countDown()
        writer.flush()

        assertEquals(
            listOf(
                mapOf("a" to "first"),
                mapOf("a" to "latest"),
            ),
            synchronized(persisted) { persisted.toList() },
        )
    }

    @Test
    fun flushWaitsForTheLatestGenerationWhenAWriteArrivesDuringPersistence() {
        val firstPersistStarted = CountDownLatch(1)
        val releaseFirstPersist = CountDownLatch(1)
        var durable = emptyMap<String, String>()
        val writer =
            CoalescingDraftWriter(emptyMap(), executor) { snapshot ->
                if (snapshot["a"] == "first") {
                    firstPersistStarted.countDown()
                    releaseFirstPersist.await()
                }
                durable = snapshot
            }

        writer.write("a", "first")
        assertTrue(firstPersistStarted.await(5, TimeUnit.SECONDS))
        writer.write("a", "latest")
        releaseFirstPersist.countDown()
        writer.flush()

        assertEquals(mapOf("a" to "latest"), durable)
    }
}
