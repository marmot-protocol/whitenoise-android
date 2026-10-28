package dev.ipf.whitenoise.android.core

import androidx.compose.ui.graphics.asAndroidBitmap
import dev.ipf.whitenoise.android.media.MediaPipeline
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

    /** Group trim drops both pixel/source references without invalidating an already displayed bitmap. */
    @Test
    fun groupTrimReacquiresOnceAndKeepsSharedPixelsValid() =
        runBlocking {
            var calls = 0
            val load: suspend () -> ByteArray = {
                calls++
                twoFrameGif()
            }
            val displayed = requireNotNull(GroupAvatarImageLoader.load("trim-group", load))
            GroupAvatarImageLoader.trimMemory(dev.ipf.whitenoise.android.media.PresentationMemoryTrim.EMPTY)
            assertNull(GroupAvatarImageLoader.peek("trim-group"))
            assertNull(GroupAvatarImageLoader.peekAnimatedSource("trim-group"))
            assertFalse(displayed.asAndroidBitmap().isRecycled)
            assertNotNull(GroupAvatarImageLoader.load("trim-group", load))
            assertNotNull(GroupAvatarImageLoader.load("trim-group") { error("warm result must be reused") })
            assertEquals(2, calls)
        }

    /** Old queued group downloads never run after recovery, and new waiters use a new request. */
    @Test
    fun recoveryRetiresGroupWaitersWithoutReleasingThePhysicalPermit() =
        runBlocking {
            val entered = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            val queuedCalls = AtomicInteger()
            val queuedEntered = CompletableDeferred<Unit>()
            val bytes =
                Base64.getDecoder().decode(
                    "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mNk+A8AAQUBAScY42YAAAAASUVORK5CYII=",
                )
            // Initialize Robolectric's bitmap decoder before testing IO permit scheduling.
            requireNotNull(MediaPipeline.decodeSampledBitmap(bytes, 512)).recycle()
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
                            queuedEntered.complete(Unit)
                            bytes
                        }
                    }
                assertFalse(current.isCompleted)
                assertEquals(0, queuedCalls.get())
                release.complete(Unit)
                withTimeout(5_000) { queuedEntered.await() }
                assertNotNull(withTimeout(5_000) { current.await() })
                assertEquals(1, queuedCalls.get())
                assertNull(GroupAvatarImageLoader.peek("held"))
            } finally {
                release.complete(Unit)
            }
        }

    /** Re-entering a thumbnail consumes retained source bytes, never another MDK download. */
    @Test
    fun encryptedAnimationSharesTheFirstFrameCacheLifetime() =
        runBlocking {
            val gif = twoFrameGif()
            var reads = 0
            assertNotNull(
                GroupAvatarImageLoader.load("encrypted-gif") {
                    reads++
                    gif
                },
            )
            assertNotNull(GroupAvatarImageLoader.peekAnimatedSource("encrypted-gif"))
            repeat(3) {
                assertNotNull(GroupAvatarImageLoader.load("encrypted-gif") { error("warm thumbnail refetch") })
                assertNotNull(GroupAvatarImageLoader.peekAnimatedSource("encrypted-gif"))
            }
            assertEquals(1, reads)
            GroupAvatarImageLoader.prepareForRecovery()
            assertNotNull(GroupAvatarImageLoader.peekAnimatedSource("encrypted-gif"))
            GroupAvatarImageLoader.clear()
            assertNull(GroupAvatarImageLoader.peekAnimatedSource("encrypted-gif"))
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
