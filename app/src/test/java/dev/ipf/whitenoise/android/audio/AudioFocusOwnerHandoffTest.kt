package dev.ipf.whitenoise.android.audio

import android.media.AudioAttributes
import android.media.AudioManager
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class AudioFocusOwnerHandoffTest {
    private val speechAttributes: AudioAttributes =
        AudioAttributes
            .Builder()
            .setUsage(AudioAttributes.USAGE_MEDIA)
            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
            .build()

    @After
    fun tearDown() {
        AudioFocusOwner.release(AudioFocusOwner.Owner.Voice)
        AudioFocusOwner.releaseTts()
        setField("audioManager", null)
        setField("focusRequest", null)
        setField("currentOwner", null)
        setField("onFocusChange", null)
        setField("onSurrender", null)
        setField("activeCallbacks", 0)
    }

    @Test
    fun ttsAcquisitionSurrendersVoicePlayback() {
        attachAudioManager()
        var voiceSurrendered = false

        assertTrue(
            AudioFocusOwner.acquire(
                owner = AudioFocusOwner.Owner.Voice,
                audioAttributes = speechAttributes,
                focusGain = AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK,
                onFocusLoss = {},
                onOwnerSurrender = { voiceSurrendered = true },
            ),
        )

        assertTrue(
            AudioFocusOwner.acquireForTts(
                onFocusLoss = {},
                onOwnerSurrender = {},
            ),
        )

        assertTrue(voiceSurrendered)
    }

    @Test
    fun voiceAcquisitionSurrendersActiveTtsSession() {
        attachAudioManager()
        var ttsSurrendered = false

        assertTrue(
            AudioFocusOwner.acquireForTts(
                onFocusLoss = {},
                onOwnerSurrender = { ttsSurrendered = true },
            ),
        )

        assertTrue(
            AudioFocusOwner.acquire(
                owner = AudioFocusOwner.Owner.Voice,
                audioAttributes = speechAttributes,
                focusGain = AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK,
                onFocusLoss = {},
                onOwnerSurrender = {},
            ),
        )

        assertTrue(ttsSurrendered)
    }

    @Test
    fun transientFocusLossInvokesOwnerCallback() {
        attachAudioManager()
        var focusLost = false

        assertTrue(
            AudioFocusOwner.acquire(
                owner = AudioFocusOwner.Owner.Voice,
                audioAttributes = speechAttributes,
                focusGain = AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK,
                onFocusLoss = { focusLost = true },
                onOwnerSurrender = {},
            ),
        )

        val listener =
            AudioFocusOwner::class.java
                .getDeclaredField("focusListener")
                .apply { isAccessible = true }
                .get(AudioFocusOwner) as AudioManager.OnAudioFocusChangeListener
        listener.onAudioFocusChange(AudioManager.AUDIOFOCUS_LOSS_TRANSIENT)

        assertTrue(focusLost)
    }

    @Test
    fun focusLossCallbackRunsOutsideStateLock() {
        attachAudioManager()
        var callbackHeldLock = true

        assertTrue(
            AudioFocusOwner.acquire(
                owner = AudioFocusOwner.Owner.Voice,
                audioAttributes = speechAttributes,
                focusGain = AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK,
                onFocusLoss = { callbackHeldLock = Thread.holdsLock(field("focusLock")!!) },
                onOwnerSurrender = {},
            ),
        )

        focusListener().onAudioFocusChange(AudioManager.AUDIOFOCUS_LOSS_TRANSIENT)

        assertFalse(callbackHeldLock)
    }

    @Test
    fun ownerSurrenderCallbackRunsOutsideStateLock() {
        attachAudioManager()
        var callbackHeldLock = true

        assertTrue(
            AudioFocusOwner.acquire(
                owner = AudioFocusOwner.Owner.Voice,
                audioAttributes = speechAttributes,
                focusGain = AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK,
                onFocusLoss = {},
                onOwnerSurrender = { callbackHeldLock = Thread.holdsLock(field("focusLock")!!) },
            ),
        )

        assertTrue(AudioFocusOwner.acquireForTts(onFocusLoss = {}, onOwnerSurrender = {}))

        assertFalse(callbackHeldLock)
    }

    @Test
    fun concurrentHandoffIsRejectedWhileSurrenderCallbackRuns() {
        attachAudioManager()
        val callbackStarted = CountDownLatch(1)
        val allowCallbackToReturn = CountDownLatch(1)
        val handoffResult = AtomicReference<Boolean>()

        assertTrue(
            AudioFocusOwner.acquire(
                owner = AudioFocusOwner.Owner.Voice,
                audioAttributes = speechAttributes,
                focusGain = AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK,
                onFocusLoss = {},
                onOwnerSurrender = {
                    callbackStarted.countDown()
                    allowCallbackToReturn.await(2, TimeUnit.SECONDS)
                },
            ),
        )
        val handoffThread =
            Thread {
                handoffResult.set(AudioFocusOwner.acquireForTts(onFocusLoss = {}, onOwnerSurrender = {}))
            }.apply { start() }
        assertTrue(callbackStarted.await(2, TimeUnit.SECONDS))

        assertFalse(
            AudioFocusOwner.acquire(
                owner = AudioFocusOwner.Owner.Voice,
                audioAttributes = speechAttributes,
                focusGain = AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK,
                onFocusLoss = {},
                onOwnerSurrender = {},
            ),
        )

        allowCallbackToReturn.countDown()
        handoffThread.join(2_000)
        assertFalse(handoffThread.isAlive)
        assertTrue(handoffResult.get())
    }

    @Test
    fun surrenderFailureRollsBackNewOwnerAndFocus() {
        attachAudioManager()
        assertTrue(
            AudioFocusOwner.acquire(
                owner = AudioFocusOwner.Owner.Voice,
                audioAttributes = speechAttributes,
                focusGain = AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK,
                onFocusLoss = {},
                onOwnerSurrender = { error("surrender failed") },
            ),
        )

        var failure: IllegalStateException? = null
        try {
            AudioFocusOwner.acquireForTts(
                onFocusLoss = {},
                onOwnerSurrender = {},
            )
        } catch (error: IllegalStateException) {
            failure = error
        }

        assertEquals("surrender failed", failure?.message)
        assertNull(field("currentOwner"))
        assertNull(field("focusRequest"))
        assertEquals(0, field("activeCallbacks"))
    }

    @Test
    fun reacquisitionIsRejectedWhileFocusLossCallbackRuns() {
        attachAudioManager()
        val callbackStarted = CountDownLatch(1)
        val allowCallbackToReturn = CountDownLatch(1)

        assertTrue(
            AudioFocusOwner.acquire(
                owner = AudioFocusOwner.Owner.Voice,
                audioAttributes = speechAttributes,
                focusGain = AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK,
                onFocusLoss = {
                    callbackStarted.countDown()
                    allowCallbackToReturn.await(2, TimeUnit.SECONDS)
                },
                onOwnerSurrender = {},
            ),
        )
        val lossThread =
            Thread {
                focusListener().onAudioFocusChange(AudioManager.AUDIOFOCUS_LOSS_TRANSIENT)
            }.apply { start() }
        assertTrue(callbackStarted.await(2, TimeUnit.SECONDS))

        assertFalse(
            AudioFocusOwner.acquire(
                owner = AudioFocusOwner.Owner.Voice,
                audioAttributes = speechAttributes,
                focusGain = AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK,
                onFocusLoss = {},
                onOwnerSurrender = {},
            ),
        )

        allowCallbackToReturn.countDown()
        lossThread.join(2_000)
        assertFalse(lossThread.isAlive)
    }

    @Test
    fun releaseClearsBookkeepingWhenAudioManagerIsNull() {
        AudioFocusOwner.attach(RuntimeEnvironment.getApplication())
        setField("audioManager", null)

        assertTrue(
            AudioFocusOwner.acquireForTts(
                onFocusLoss = {},
                onOwnerSurrender = {},
            ),
        )

        AudioFocusOwner.releaseTts()

        assertNull(field("currentOwner"))
        assertNull(field("onFocusChange"))
        assertNull(field("onSurrender"))
        assertNull(field("focusRequest"))
    }

    @Test
    fun failedFocusRequestDoesNotRetainHeldFocus() {
        attachAudioManager()
        val audioManager = RuntimeEnvironment.getApplication().getSystemService(AudioManager::class.java)
        val shadowAudioManager = shadowOf(audioManager)
        shadowAudioManager.setNextFocusRequestResponse(AudioManager.AUDIOFOCUS_REQUEST_FAILED)

        assertFalse(
            AudioFocusOwner.acquireForTts(
                onFocusLoss = {},
                onOwnerSurrender = {},
            ),
        )
        assertNull(field("focusRequest"))
        assertNull(field("currentOwner"))
    }

    @Test
    fun deniedHandoffPreservesIncumbentRequestAndCallbacks() {
        attachAudioManager()
        val audioManager = RuntimeEnvironment.getApplication().getSystemService(AudioManager::class.java)
        val shadowAudioManager = shadowOf(audioManager)
        var voiceFocusLost = false
        var voiceSurrendered = false

        assertTrue(
            AudioFocusOwner.acquire(
                owner = AudioFocusOwner.Owner.Voice,
                audioAttributes = speechAttributes,
                focusGain = AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK,
                onFocusLoss = { voiceFocusLost = true },
                onOwnerSurrender = { voiceSurrendered = true },
            ),
        )
        val incumbentRequest = field("focusRequest")
        val incumbentListener = focusListener()
        shadowAudioManager.setNextFocusRequestResponse(AudioManager.AUDIOFOCUS_REQUEST_FAILED)

        assertFalse(
            AudioFocusOwner.acquireForTts(
                onFocusLoss = {},
                onOwnerSurrender = {},
            ),
        )

        assertSame(incumbentRequest, field("focusRequest"))
        assertSame(incumbentListener, focusListener())
        assertEquals(AudioFocusOwner.Owner.Voice, field("currentOwner"))
        assertFalse(voiceSurrendered)
        incumbentListener.onAudioFocusChange(AudioManager.AUDIOFOCUS_LOSS_TRANSIENT)
        assertTrue(voiceFocusLost)
    }

    @Test
    fun staleCallbackFromAbandonedRequestCannotReachNewOwner() {
        attachAudioManager()
        var voiceFocusLost = false
        var ttsFocusLost = false

        assertTrue(
            AudioFocusOwner.acquire(
                owner = AudioFocusOwner.Owner.Voice,
                audioAttributes = speechAttributes,
                focusGain = AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK,
                onFocusLoss = { voiceFocusLost = true },
                onOwnerSurrender = {},
            ),
        )
        val staleListener = focusListener()

        assertTrue(
            AudioFocusOwner.acquireForTts(
                onFocusLoss = { ttsFocusLost = true },
                onOwnerSurrender = {},
            ),
        )
        val currentListener = focusListener()
        assertNotSame(staleListener, currentListener)

        staleListener.onAudioFocusChange(AudioManager.AUDIOFOCUS_LOSS_TRANSIENT)
        assertFalse(voiceFocusLost)
        assertFalse(ttsFocusLost)

        currentListener.onAudioFocusChange(AudioManager.AUDIOFOCUS_LOSS_TRANSIENT)
        assertTrue(ttsFocusLost)
    }

    private fun attachAudioManager() {
        AudioFocusOwner.attach(RuntimeEnvironment.getApplication())
    }

    private fun field(name: String): Any? {
        val field = AudioFocusOwner::class.java.getDeclaredField(name)
        field.isAccessible = true
        return field.get(AudioFocusOwner)
    }

    private fun focusListener(): AudioManager.OnAudioFocusChangeListener = field("focusListener") as AudioManager.OnAudioFocusChangeListener

    private fun setField(
        name: String,
        value: Any?,
    ) {
        val field = AudioFocusOwner::class.java.getDeclaredField(name)
        field.isAccessible = true
        field.set(AudioFocusOwner, value)
    }
}
