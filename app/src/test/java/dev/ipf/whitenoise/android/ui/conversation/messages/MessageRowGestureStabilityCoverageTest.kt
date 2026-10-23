package dev.ipf.whitenoise.android.ui.conversation.messages

import dev.ipf.whitenoise.android.functionBody
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * A message row's pointer modifiers must keep a stable structure while a hold is active.
 * Compose diffs a modifier chain by element type, so conditionally omitting a sibling
 * pointerInput can pair or detach the live long-press node and cancel the held range (#3407).
 */
class MessageRowGestureStabilityCoverageTest {
    /** Each row gesture helper always installs its pointer node and gates availability inside it. */
    @Test
    fun rowGestureHelpersNeverOmitTheirPointerNode() {
        val helpers =
            listOf(
                "ui/common/AnchoredDragSelection.kt" to "longPressOrVerticalDrag",
                "ui/common/DirectionalSwipe.kt" to "directionalSwipe",
                "ui/conversation/messages/MessageBubbleDoubleTapSeek.kt" to "observeMessageTextDoubleTap",
            )
        helpers.forEach { (path, function) ->
            val declaration = declaration(source(path), "Modifier.$function")
            assertFalse(
                "$function must not return an unmodified chain when disabled",
                declaration.lines().any { it.trim() == "this" } ||
                    Regex("""\breturn(@\w+)?\s+this\b|\bthis\s+else\b|\belse\s+this\b""")
                        .containsMatchIn(declaration),
            )
            assertTrue("$function must always install pointerInput", declaration.contains("pointerInput("))
        }
    }

    /** The bubble passes availability to the hold detector instead of swapping it for a bare Modifier. */
    @Test
    fun messageBubbleKeepsTheHoldDetectorMounted() {
        val bubble = source("ui/conversation/messages/MessageBubble.kt")
        assertFalse(
            "the long-press detector must not be an optional modifier",
            Regex("""(else|\bif\s*\([^)]*\))\s*\{?\s*Modifier\.longPressOrVerticalDrag\(""").containsMatchIn(bubble),
        )
        assertTrue(
            Regex("""Modifier\.longPressOrVerticalDrag\(\s*enabled\s*=""").containsMatchIn(bubble),
        )
    }

    /** Returns a function from its signature through its first balanced block, covering expression bodies. */
    private fun declaration(
        source: String,
        function: String,
    ): String {
        val start = source.indexOf("fun $function(")
        val body = source.functionBody(function)
        return source.substring(start, source.indexOf(body, start) + body.length)
    }

    /** Reads a production source from either the app module or the repository root. */
    private fun source(path: String): String =
        listOf(
            File("src/main/java/dev/ipf/whitenoise/android/$path"),
            File("app/src/main/java/dev/ipf/whitenoise/android/$path"),
        ).first { it.isFile }.readText()
}
