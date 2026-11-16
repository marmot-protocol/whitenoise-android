package dev.ipf.whitenoise.android.state

import dev.ipf.whitenoise.android.appModuleSource
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Keeps [WhiteNoiseAppState] and [ChatsController] from growing back. New feature state belongs in a
 * focused controller or a `WhiteNoiseAppState` extension file in this package; new chat-list work
 * belongs in the matching `ChatsController<Topic>.kt` extension file, or a new one for a new topic.
 * Lower a limit when a change shrinks its file.
 */
class AppStateSizeRatchetTest {
    @Test
    fun appStateDoesNotGrow() {
        val lines = appModuleSource("src/main/java/dev/ipf/whitenoise/android/state/AppState.kt").lines().size
        assertTrue(
            "AppState.kt has $lines lines (limit $MAX_LINES). Move new work into a controller or " +
                "an AppState extension file instead of growing the class.",
            lines <= MAX_LINES,
        )
    }

    @Test
    fun chatsControllerDoesNotGrow() {
        val lines =
            appModuleSource("src/main/java/dev/ipf/whitenoise/android/state/ChatsController.kt")
                .trimEnd()
                .lines()
                .size
        assertTrue(
            "ChatsController.kt has $lines lines (limit $CHATS_CONTROLLER_MAX_LINES). Move new work into a " +
                "ChatsController<Topic>.kt extension file instead of growing the class.",
            lines <= CHATS_CONTROLLER_MAX_LINES,
        )
    }

    private companion object {
        const val MAX_LINES = 9_888
        const val CHATS_CONTROLLER_MAX_LINES = 2_152
    }
}
