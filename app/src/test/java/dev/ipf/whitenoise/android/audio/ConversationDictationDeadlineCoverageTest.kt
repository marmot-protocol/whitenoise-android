package dev.ipf.whitenoise.android.audio

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** Keeps every generation timer behind the same drain-aware production policy. */
class ConversationDictationDeadlineCoverageTest {
    /** Keeps controller and generation timer routing behind the shared deadline policy. */
    @Test
    fun controllerRoutesGenerationAndDrainTimersThroughTheirSharedOwner() {
        val controller = source("ConversationDictationController.kt")
        val generation = source("ConversationDictationGenerationOwner.kt")
        assertTrue(controller.contains("recognitionDeadlines.armGeneration(sessionId, phase)"))
        assertTrue(controller.contains("recognitionDeadlines.beginCallerAudioDrain(sessionId)"))
        assertTrue(controller.contains("recognitionDeadlines.finishCallerAudioDrain(sessionId)"))
        assertTrue(controller.contains("ConversationDictationGenerationOwner(recognitionDeadlines::cancelGeneration)"))
        assertFalse(controller.contains("sessionTimeoutHandle"))
        assertFalse(generation.contains("ConversationDictationTimeoutHandle"))
    }

    /** Loads the production audio source from either supported test working directory. */
    private fun source(name: String): String =
        listOf(
            File("src/main/java/dev/ipf/whitenoise/android/audio/$name"),
            File("app/src/main/java/dev/ipf/whitenoise/android/audio/$name"),
        ).firstOrNull { it.exists() }
            ?.readText()
            ?: error("Missing source file: $name")
}
