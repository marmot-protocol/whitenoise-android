package dev.ipf.whitenoise.android.media.sandbox

import android.app.Application
import android.content.ContentResolver
import android.graphics.Bitmap
import android.net.Uri
import androidx.media3.common.Player
import com.google.common.util.concurrent.SettableFuture
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [36])
class RawAvPausedPreparationTest {
    private val players = mutableListOf<IsolatedAttachmentPlayer>()

    @After
    fun closeOwners() {
        players.forEach { it.release() }
        IsolatedMediaDecoder.installTestTransport(null)
    }

    @Test
    fun playerPrepareThenRepeatedFalseRetainsTheHeldInitialPoster() {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val calls = AtomicInteger()
        val bitmap = Bitmap.createBitmap(2, 2, Bitmap.Config.ARGB_8888)
        IsolatedMediaDecoder.installTestTransport(
            object : ProbeTransport() {
                override fun probeUri(
                    resolver: ContentResolver,
                    uri: Uri,
                    edge: Int,
                ): MediaProbe {
                    calls.incrementAndGet()
                    entered.countDown()
                    check(release.await(3, TimeUnit.SECONDS))
                    return MediaProbe(2, 2, 4000, bitmap)
                }
            },
        )
        val player = player()
        val engine = field(player, "engine") as RawAvPlaybackEngine
        try {
            player.prepare()
            assertTrue(entered.await(2, TimeUnit.SECONDS))
            val generation = engine.intent().activation
            val revision = field(engine, "revision")
            // This is the viewer's real prepare -> playWhenReady=false ordering, not a direct engine shortcut.
            player.playWhenReady = false
            player.playWhenReady = false
            assertEquals(generation, engine.intent().activation)
            assertEquals(revision, field(engine, "revision"))
            release.countDown()
            engine.prepare().get(2, TimeUnit.SECONDS)
            assertTrue(engine.snapshot().prepared)
            assertFalse(engine.snapshot().playWhenReady)
            assertEquals(RawAvPlaybackEngine.Status.Ready, engine.snapshot().status)
            assertSame(bitmap, field(engine, "lastBitmap"))
            assertFalse(bitmap.isRecycled)
            assertEquals(1, calls.get())
            assertNull(field(engine, "pendingSeek"))
        } finally {
            release.countDown()
        }
    }

    @Test
    fun realPlayThenPauseStillRejectsTheEarlierHeldPreparationPoster() {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val bitmap = Bitmap.createBitmap(2, 2, Bitmap.Config.ARGB_8888)
        IsolatedMediaDecoder.installTestTransport(
            object : ProbeTransport() {
                override fun probeUri(
                    resolver: ContentResolver,
                    uri: Uri,
                    edge: Int,
                ): MediaProbe {
                    entered.countDown()
                    check(release.await(3, TimeUnit.SECONDS))
                    return MediaProbe(2, 2, 4000, bitmap)
                }
            },
        )
        val player = player()
        val engine = field(player, "engine") as RawAvPlaybackEngine
        try {
            player.setAudioAttributes(androidx.media3.common.AudioAttributes.DEFAULT, false)
            player.prepare()
            assertTrue(entered.await(2, TimeUnit.SECONDS))
            val generation = engine.intent().activation
            player.play()
            assertTrue(engine.snapshot().playWhenReady)
            player.pause()
            assertFalse(engine.snapshot().playWhenReady)
            assertEquals(generation + 2, engine.intent().activation)
            release.countDown()
            engine.prepare().get(2, TimeUnit.SECONDS)
            awaitProbeIdle(engine)
            assertNull(field(engine, "lastBitmap"))
            assertTrue(bitmap.isRecycled)
        } finally {
            release.countDown()
        }
    }

    @Test
    fun repeatedFalseAcknowledgesTheCurrentHeldPausedSeekInsteadOfCancellingIt() {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val calls = AtomicInteger()
        val initial = Bitmap.createBitmap(2, 2, Bitmap.Config.ARGB_8888)
        val sought = Bitmap.createBitmap(2, 2, Bitmap.Config.ARGB_8888)
        IsolatedMediaDecoder.installTestTransport(
            object : ProbeTransport() {
                override fun probeUri(
                    resolver: ContentResolver,
                    uri: Uri,
                    edge: Int,
                ) = MediaProbe(2, 2, 4000, initial)

                override fun probeUriAt(
                    resolver: ContentResolver,
                    uri: Uri,
                    positionMs: Long,
                    edge: Int,
                ): MediaProbe {
                    calls.incrementAndGet()
                    assertEquals(1500L, positionMs)
                    entered.countDown()
                    check(release.await(3, TimeUnit.SECONDS))
                    return MediaProbe(2, 2, 4000, sought)
                }
            },
        )
        val player = player()
        val engine = field(player, "engine") as RawAvPlaybackEngine
        try {
            player.prepare()
            engine.prepare().get(2, TimeUnit.SECONDS)
            val completion = engine.seekToAsync(1500L)
            assertTrue(entered.await(2, TimeUnit.SECONDS))
            val generation = engine.intent().activation
            val revision = field(engine, "revision")
            player.playWhenReady = false
            player.playWhenReady = false
            assertFalse(completion.isCancelled)
            assertFalse(completion.isDone)
            assertEquals(generation, engine.intent().activation)
            assertEquals(revision, field(engine, "revision"))
            release.countDown()
            completion.get(2, TimeUnit.SECONDS)
            assertSame(sought, field(engine, "lastBitmap"))
            assertTrue(initial.isRecycled)
            assertFalse(sought.isRecycled)
            assertEquals(1500L, engine.positionMs())
            assertEquals(1, calls.get())
            assertNull(field(engine, "pendingSeek"))
        } finally {
            release.countDown()
        }
    }

    @Test
    fun realPauseStillRevokesPlayingGenerationAndRepeatedPauseClearsTransientResume() {
        val player = player()
        val engine = field(player, "engine") as RawAvPlaybackEngine
        val pending = SettableFuture.create<Void>()
        setField(engine, "playIntent", true)
        setField(engine, "pausedUs", 2250000L)
        setField(engine, "pendingSeek", pending)
        val generation = engine.intent().activation
        player.playWhenReady = false
        assertEquals(generation + 1, engine.intent().activation)
        assertTrue(pending.isCancelled)
        assertEquals(2250L, engine.positionMs())
        assertFalse(engine.snapshot().playWhenReady)
        assertEquals(Player.PLAY_WHEN_READY_CHANGE_REASON_USER_REQUEST, engine.snapshot().playWhenReadyChangeReason)
        // A user pause must revoke an earlier focus-loss resume even when play intent is already false.
        setField(engine, "resumeOnGain", true)
        val pausedGeneration = engine.intent().activation
        player.playWhenReady = false
        assertFalse(field(engine, "resumeOnGain") as Boolean)
        assertEquals(pausedGeneration, engine.intent().activation)
    }

    private fun player(): IsolatedAttachmentPlayer =
        IsolatedAttachmentPlayer(RuntimeEnvironment.getApplication(), Uri.parse("file:///fixture.mp4")).also {
            players += it
        }

    private open class ProbeTransport : MediaDecodeTransport {
        override fun decode(
            bytes: ByteArray,
            edge: Int,
            oriented: Boolean,
            operation: Int,
        ): Bitmap? = null

        override fun inspect(bytes: ByteArray): ImageInspection? = null
    }

    private fun awaitProbeIdle(engine: RawAvPlaybackEngine) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2)
        while (System.nanoTime() < deadline) {
            if (synchronized(checkNotNull(field(engine, "lock"))) { field(engine, "prepareJob") == null }) return
            Thread.sleep(5)
        }
        throw AssertionError("Held probe did not release its generation")
    }

    private fun field(
        target: Any,
        name: String,
    ): Any? =
        target.javaClass.getDeclaredField(name).let {
            it.isAccessible = true
            it.get(target)
        }

    private fun setField(
        target: Any,
        name: String,
        value: Any,
    ) {
        target.javaClass.getDeclaredField(name).let {
            it.isAccessible = true
            it.set(target, value)
        }
    }
}
