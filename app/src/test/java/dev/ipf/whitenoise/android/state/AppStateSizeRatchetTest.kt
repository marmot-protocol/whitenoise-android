package dev.ipf.whitenoise.android.state

import dev.ipf.whitenoise.android.appModuleSource
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Keeps [WhiteNoiseAppState], [ChatsController] and the dictation controller from growing back. New
 * feature state belongs in a focused controller or a `WhiteNoiseAppState` extension file in this
 * package. New chat-list work belongs in the matching `ChatsController<Topic>.kt` extension file, or
 * a new one for a new topic; the same applies to `ConversationDictationController<Topic>.kt` in the
 * audio package. Lower a limit when a change shrinks its file.
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

    @Test
    fun conversationDictationControllerDoesNotGrow() {
        val lines =
            appModuleSource("src/main/java/dev/ipf/whitenoise/android/audio/ConversationDictationController.kt")
                .trimEnd()
                .lines()
                .size
        assertTrue(
            "ConversationDictationController.kt has $lines lines (limit $DICTATION_CONTROLLER_MAX_LINES). Move new " +
                "work into a ConversationDictationController<Topic>.kt extension file instead of growing the class.",
            lines <= DICTATION_CONTROLLER_MAX_LINES,
        )
    }

    private companion object {
        const val MAX_LINES = 8_798
        const val CHATS_CONTROLLER_MAX_LINES = 2_152
        const val DICTATION_CONTROLLER_MAX_LINES = 1_453
    }
}
