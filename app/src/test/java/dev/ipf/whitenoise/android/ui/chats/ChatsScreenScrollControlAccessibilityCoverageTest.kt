package dev.ipf.whitenoise.android.ui.chats

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class ChatsScreenScrollControlAccessibilityCoverageTest {
    /**
     * The scroll-to-top control keeps the button semantics of the FAB it replaced: it is the shared floating
     * disc, whose touch target is a button by default.
     */
    @Test
    fun scrollToTopControlIsExposedAsButton() {
        val scrollControl =
            appSource("ui/chats/ChatListJumpToTop.kt").requiredSection(
                start = "internal fun ChatListJumpToTopButton(",
                end = "internal val JUMP_TO_TOP_END_INSET",
            )
        val disc = appSource("ui/navigation/FloatingControlDisc.kt")
        val shapedTouch = appSource("ui/common/ShapedTouch.kt")

        assertTrue("scroll-to-top must be the shared floating disc", "FloatingControlDisc(" in scrollControl)
        assertTrue(
            "the disc must not override its target's role",
            ".shapedTouchTarget(press, onClick = onClick)" in disc,
        )
        assertTrue(
            "scroll-to-top control must retain button semantics from the replaced FAB",
            "role: Role? = Role.Button" in shapedTouch,
        )
    }

    /** Reads a main source below `ui/`'s package root from the module or repository working directory. */
    private fun appSource(path: String): String =
        listOf(
            File("src/main/java/dev/ipf/whitenoise/android/$path"),
            File("app/src/main/java/dev/ipf/whitenoise/android/$path"),
        ).firstOrNull { it.exists() }
            ?.readText()
            ?: error("Missing $path source file")

    private fun String.requiredSection(
        start: String,
        end: String,
    ): String {
        val startIndex = indexOf(start)
        require(startIndex >= 0) { "Missing section start: $start" }
        val endIndex = indexOf(end, startIndex + start.length)
        require(endIndex >= 0) { "Missing section end: $end" }
        return substring(startIndex, endIndex)
    }
}
