package dev.ipf.whitenoise.android.state

import dev.ipf.whitenoise.android.appModuleSource
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Keeps [ConversationController] from growing back. New conversation work belongs in the matching
 * `ConversationController<Topic>.kt` extension file in this package, or a new one for a new topic.
 * Lower [MAX_LINES] when a change shrinks the file.
 */
class ConversationControllerSizeRatchetTest {
    @Test
    fun conversationControllerDoesNotGrow() {
        val lines =
            appModuleSource("src/main/java/dev/ipf/whitenoise/android/state/ConversationController.kt")
                .trimEnd()
                .lines()
                .size
        assertTrue(
            "ConversationController.kt has $lines lines (limit $MAX_LINES). Move new work into a " +
                "ConversationController<Topic>.kt extension file instead of growing the class.",
            lines <= MAX_LINES,
        )
    }

    private companion object {
        const val MAX_LINES = 4_969
    }
}
