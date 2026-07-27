package dev.ipf.whitenoise.android.core

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.atomic.AtomicBoolean

class GroupAvatarImageLoaderTest {
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
