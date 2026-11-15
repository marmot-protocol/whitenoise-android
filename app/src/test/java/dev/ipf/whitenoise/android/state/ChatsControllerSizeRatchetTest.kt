package dev.ipf.whitenoise.android.state

import dev.ipf.whitenoise.android.appModuleSource
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Keeps [ChatsController] from growing back. New chat-list work belongs in the matching
 * `ChatsController<Topic>.kt` extension file in this package, or a new one for a new topic.
 * Lower [MAX_LINES] when a change shrinks the file.
 */
class ChatsControllerSizeRatchetTest {
    @Test
    fun chatsControllerDoesNotGrow() {
        val lines =
            appModuleSource("src/main/java/dev/ipf/whitenoise/android/state/ChatsController.kt")
                .trimEnd()
                .lines()
                .size
        assertTrue(
            "ChatsController.kt has $lines lines (limit $MAX_LINES). Move new work into a " +
                "ChatsController<Topic>.kt extension file instead of growing the class.",
            lines <= MAX_LINES,
        )
    }

    private companion object {
        const val MAX_LINES = 2_150
    }
}
