package dev.ipf.whitenoise.android.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Exercises handle ownership; factories here do not choose scheduler delays.
 * [ConversationDictationDeadlineCoverageTest] verifies those delays through the production controller.
 */
class ConversationDictationRecognitionDeadlinesTest {
    /** Cancels an older processing timer and prevents replacements during the same drain. */
    @Test
    fun drainCancelsAnExistingProcessingTimerAndSuppressesReplacementProcessing() {
        val owner = ConversationDictationRecognitionDeadlines()
        val processing = Timer()
        val drain = Timer()
        owner.armGeneration(
            1L,
            ConversationDictationRecognitionDeadlines.GenerationPhase.Processing,
            processing::handle,
        )
        owner.beginCallerAudioDrain(1L, drain::handle)
        assertTrue(processing.cancelled)
        owner.armGeneration(1L, ConversationDictationRecognitionDeadlines.GenerationPhase.Processing) {
            error("Processing must use the existing drain deadline")
        }
        assertFalse(drain.cancelled)
    }

    /** Keeps the drain bound separate from startup, readiness and generation retirement. */
    @Test
    fun generationReadyAndRetirementPreserveDrainAndStartupBound() {
        val owner = ConversationDictationRecognitionDeadlines()
        val startup = Timer()
        val drain = Timer()
        owner.armGeneration(1L, ConversationDictationRecognitionDeadlines.GenerationPhase.Startup, startup::handle)
        owner.beginCallerAudioDrain(1L, drain::handle)
        owner.armGeneration(1L, ConversationDictationRecognitionDeadlines.GenerationPhase.Processing) {
            error("Suppressed processing must leave the startup deadline intact")
        }
        assertFalse(startup.cancelled)
        val generation = ConversationDictationGenerationOwner(owner::cancelGeneration)
        generation.begin()
        generation.ready(0L)
        assertTrue(startup.cancelled)
        assertFalse(drain.cancelled)
        generation.retire()
        assertFalse(drain.cancelled)
    }

    /** Preserves one drain deadline per attempt and permits a fresh bound after reset. */
    @Test
    fun repeatedDrainDoesNotExtendTheAttemptButRetryGetsAFreshDeadline() {
        val owner = ConversationDictationRecognitionDeadlines()
        val first = Timer()
        var schedules = 0
        owner.beginCallerAudioDrain(1L) {
            schedules += 1
            first.handle()
        }
        owner.beginCallerAudioDrain(1L) { error("The same attempt cannot extend its deadline") }
        assertEquals(1, schedules)
        owner.clearSession()
        assertTrue(first.cancelled)
        owner.beginCallerAudioDrain(1L) {
            schedules += 1
            Timer().handle()
        }
        assertEquals(2, schedules)
    }

    /** Clears the old drain policy before arming a replacement session. */
    @Test
    fun replacementSessionDoesNotInheritThePreviousDrainPolicy() {
        val owner = ConversationDictationRecognitionDeadlines()
        val oldDrain = Timer()
        val processing = Timer()
        owner.beginCallerAudioDrain(1L, oldDrain::handle)
        owner.armSession { Timer().handle() }
        owner.armGeneration(
            2L,
            ConversationDictationRecognitionDeadlines.GenerationPhase.Processing,
            processing::handle,
        )
        assertTrue(oldDrain.cancelled)
        owner.cancelGeneration()
        assertTrue(processing.cancelled)
    }

    /** Releases the drain bound only when its owning session completes. */
    @Test
    fun onlyTheCompletedDrainReleasesItsDeadlineBeforeCompletion() {
        val owner = ConversationDictationRecognitionDeadlines()
        val drain = Timer()
        owner.beginCallerAudioDrain(1L, drain::handle)
        owner.finishCallerAudioDrain(2L)
        assertFalse(drain.cancelled)
        owner.finishCallerAudioDrain(1L)
        assertTrue(drain.cancelled)
        assertFalse(owner.isDraining(1L))
    }

    private class Timer {
        var cancelled = false

        /** Creates a cancellable timer handle whose cancellation remains observable to the test. */
        fun handle() = ConversationDictationTimeoutHandle { cancelled = true }
    }
}
