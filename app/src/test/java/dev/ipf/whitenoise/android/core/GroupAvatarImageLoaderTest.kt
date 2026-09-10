package dev.ipf.whitenoise.android.core

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.util.Base64
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

@RunWith(RobolectricTestRunner::class)
class GroupAvatarImageLoaderTest {
    /** Each case owns the bounded singleton's cache lifetime. */
    @After
    fun resetLoader() = GroupAvatarImageLoader.clear()

    /** Old queued group downloads never run after recovery, and new waiters use a new request. */
    @Test
    fun recoveryRetiresGroupWaitersWithoutReleasingThePhysicalPermit() =
        runBlocking {
            val entered = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            val queuedCalls = AtomicInteger()
            val bytes =
                Base64.getDecoder().decode(
                    "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mNk+A8AAQUBAScY42YAAAAASUVORK5CYII=",
                )
            val first =
                async {
                    GroupAvatarImageLoader.load("held") {
                        entered.complete(Unit)
                        release.await()
                        bytes
                    }
                }
            try {
                withTimeout(5_000) { entered.await() }
                val retired =
                    async(start = CoroutineStart.UNDISPATCHED) {
                        GroupAvatarImageLoader.load("queued") {
                            queuedCalls.incrementAndGet()
                            bytes
                        }
                    }
                AvatarLoadRecovery.onNetworkRestored()
                assertNull(withTimeout(5_000) { retired.await() })
                assertNull(withTimeout(5_000) { first.await() })
                val current =
                    async(start = CoroutineStart.UNDISPATCHED) {
                        GroupAvatarImageLoader.load("queued") {
                            queuedCalls.incrementAndGet()
                            bytes
                        }
                    }
                assertFalse(current.isCompleted)
                assertEquals(0, queuedCalls.get())
                release.complete(Unit)
                assertNotNull(withTimeout(5_000) { current.await() })
                assertEquals(1, queuedCalls.get())
                assertNull(GroupAvatarImageLoader.peek("held"))
            } finally {
                release.complete(Unit)
            }
        }

    @Test
    fun encryptedAvatarPayloadMustFitThePreparedImageLimit() {
        assertTrue(isGroupAvatarPayloadAccepted(ByteArray(GROUP_AVATAR_MAX_PAYLOAD_BYTES)))
        assertFalse(isGroupAvatarPayloadAccepted(ByteArray(GROUP_AVATAR_MAX_PAYLOAD_BYTES + 1)))
    }

    @Test
    fun clearCancelsQueuedLoadsBeforeTheyFetchOldAccountBytes() =
        runBlocking {
            GroupAvatarImageLoader.clear()
            val firstStarted = CompletableDeferred<Unit>()
            val holdFirst = CompletableDeferred<Unit>()
            val secondStarted = AtomicBoolean(false)
            val first =
                async {
                    GroupAvatarImageLoader.load("first") {
                        firstStarted.complete(Unit)
                        holdFirst.await()
                        byteArrayOf()
                    }
                }
            withTimeout(5_000) { firstStarted.await() }
            val secondCallEntered = CompletableDeferred<Unit>()
            val second =
                async(start = CoroutineStart.UNDISPATCHED) {
                    secondCallEntered.complete(Unit)
                    GroupAvatarImageLoader.load("second") {
                        secondStarted.set(true)
                        byteArrayOf()
                    }
                }
            withTimeout(5_000) { secondCallEntered.await() }
            assertFalse(second.isCompleted)

            GroupAvatarImageLoader.clear()

            assertNull(withTimeout(5_000) { first.await() })
            assertNull(withTimeout(5_000) { second.await() })
            assertFalse(secondStarted.get())
            holdFirst.complete(Unit)
            Unit
        }
}
