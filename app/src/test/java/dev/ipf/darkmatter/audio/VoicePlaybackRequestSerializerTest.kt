package dev.ipf.darkmatter.audio

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.util.concurrent.CopyOnWriteArrayList

class VoicePlaybackRequestSerializerTest {
    @Test
    fun playbackRequestsDoNotOverlapAcrossSuspendingPrepareWork() {
        runBlocking {
            // MediaPlayer itself is not usable in JVM tests, so this covers the
            // serialization seam that VoicePlaybackController.play() holds around prepare().
            val serializer = VoicePlaybackRequestSerializer()
            val firstEntered = CompletableDeferred<Unit>()
            val releaseFirst = CompletableDeferred<Unit>()
            val secondEntered = CompletableDeferred<Unit>()
            val events = CopyOnWriteArrayList<String>()

            val first =
                async(Dispatchers.Default) {
                    serializer.withSerializedPlayback {
                        events += "first-enter"
                        firstEntered.complete(Unit)
                        releaseFirst.await()
                        events += "first-exit"
                    }
                }
            firstEntered.await()

            val second =
                async(Dispatchers.Default) {
                    serializer.withSerializedPlayback {
                        events += "second-enter"
                        secondEntered.complete(Unit)
                    }
                }

            assertNull(withTimeoutOrNull(100) { secondEntered.await() })

            releaseFirst.complete(Unit)
            withTimeout(1_000) { secondEntered.await() }
            first.await()
            second.await()

            assertEquals(listOf("first-enter", "first-exit", "second-enter"), events.toList())
        }
    }
}
