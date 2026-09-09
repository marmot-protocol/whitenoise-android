package dev.ipf.whitenoise.android.state

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

class PendingForwardStoreInitializationTest {
    /** First access initializes the encrypted store inside its serialized background boundary. */
    @Test
    fun delegateConstructionAndOperationsStayOffCallerThread() =
        runBlocking {
            val callerThread = Thread.currentThread()
            val operations = mutableListOf<String>()
            val store =
                SerializedPendingForwardRequestStore(delegateProvider = {
                    assertNotEquals(callerThread, Thread.currentThread())
                    operations += "create"
                    object : PendingForwardRequestStore {
                        override fun save(request: PendingForwardRequest): Boolean = error("unused")

                        override fun load(): PendingForwardRequest? {
                            assertNotEquals(callerThread, Thread.currentThread())
                            operations += "load"
                            return null
                        }

                        override fun remove(requestId: String) {
                            assertNotEquals(callerThread, Thread.currentThread())
                            operations += "remove"
                        }

                        override fun clear() {
                            assertNotEquals(callerThread, Thread.currentThread())
                            operations += "clear"
                        }
                    }
                })
            assertEquals(emptyList<String>(), operations)
            assertNull(store.load())
            store.remove("request")
            store.clear()
            assertEquals(listOf("create", "load", "remove", "clear"), operations)
        }

    /** Cancellation of an old owner cannot let its blocking write overwrite the recreated owner's write. */
    @Test
    fun cancelledOlderSaveFinishesBeforeTheRecreatedOwnersSave() =
        runBlocking {
            val entered = CompletableDeferred<Unit>()
            val release = CountDownLatch(1)
            val current = AtomicReference<PendingForwardRequest?>()
            val first = PendingForwardRequest("first", "account", "group", emptyList(), null, emptyList())
            val second = first.copy(requestId = "second")
            val delegate =
                object : PendingForwardRequestStore {
                    override fun save(request: PendingForwardRequest): Boolean {
                        if (request.requestId == "first") {
                            entered.complete(Unit)
                            check(release.await(5, TimeUnit.SECONDS))
                        }
                        current.set(request)
                        return true
                    }

                    override fun load(): PendingForwardRequest? = current.get()

                    override fun remove(requestId: String) = Unit

                    override fun clear() = current.set(null)
                }
            val oldOwner = SerializedPendingForwardRequestStore(delegateProvider = { delegate })
            val newOwner = SerializedPendingForwardRequestStore(delegateProvider = { delegate })
            try {
                withTimeout(5_000) {
                    val older = launch { oldOwner.save(first) }
                    entered.await()
                    val newer = launch(start = CoroutineStart.UNDISPATCHED) { newOwner.save(second) }
                    older.cancel()
                    release.countDown()
                    joinAll(older, newer)
                    assertTrue(older.isCancelled)
                    assertEquals(second, newOwner.load())
                }
            } finally {
                release.countDown()
            }
        }
}
