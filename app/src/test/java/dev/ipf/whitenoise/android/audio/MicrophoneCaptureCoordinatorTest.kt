package dev.ipf.whitenoise.android.audio

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MicrophoneCaptureCoordinatorTest {
    @Test
    fun oneOwnerCanReenterButAnotherCannotCaptureUntilRelease() {
        val coordinator = MicrophoneCaptureCoordinator()
        val voiceNote = Any()
        val dictation = Any()

        assertTrue(coordinator.tryAcquire(voiceNote))
        assertTrue(coordinator.tryAcquire(voiceNote))
        assertFalse(coordinator.tryAcquire(dictation))

        coordinator.release(dictation)
        assertFalse(coordinator.tryAcquire(dictation))

        coordinator.release(voiceNote)
        assertTrue(coordinator.tryAcquire(dictation))
    }
}
