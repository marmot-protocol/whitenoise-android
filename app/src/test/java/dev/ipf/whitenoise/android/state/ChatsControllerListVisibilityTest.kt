package dev.ipf.whitenoise.android.state

import dev.ipf.whitenoise.android.audio.kotlinFunctionBody
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Regression for issue #1313: returning to the chat list while a debounced
 * recompute is still pending must synchronously publish folded backing state
 * before the one-shot conversation-return head snapshot is exposed.
 */
class ChatsControllerListVisibilityTest {
    @Test
    fun setChatListVisible_recomputesSynchronouslyOnReturn() {
        val body = controllersSource().readText().kotlinFunctionBody("setChatListVisible")

        assertTrue(
            "hidden-to-visible must synchronously recompute before return snap compares heads",
            Regex(
                """if\s*\(\s*visible\s*&&\s*\(\s*pendingRecompute\s*\|\|\s*recomputeScheduled\s*\)\s*\)\s*\{\s*recompute\(\s*\)\s*}""",
            ).containsMatchIn(body),
        )
        assertFalse(
            "return flush must not be gated solely on pendingRecompute",
            Regex("""if\s*\(\s*visible\s*&&\s*pendingRecompute\s*\)\s*recompute\(\s*\)""").containsMatchIn(body),
        )
    }

    private fun controllersSource(): File =
        listOf(
            File("src/main/java/dev/ipf/whitenoise/android/state/Controllers.kt"),
            File("app/src/main/java/dev/ipf/whitenoise/android/state/Controllers.kt"),
        ).firstOrNull { it.exists() }
            ?: error("Missing Controllers.kt source file")
}
