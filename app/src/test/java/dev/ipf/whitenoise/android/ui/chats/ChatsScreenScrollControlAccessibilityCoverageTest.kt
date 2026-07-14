package dev.ipf.whitenoise.android.ui.chats

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class ChatsScreenScrollControlAccessibilityCoverageTest {
    @Test
    fun scrollToTopControlIsExposedAsButton() {
        val source = chatsScreenSource().readText()
        val scrollControl =
            source.requiredSection(
                start = "val scrollToTopLabel = stringResource(R.string.scroll_to_top)",
                end = "\n                        contentAlignment = Alignment.Center,",
            )

        assertTrue(
            "scroll-to-top control must retain button semantics from the replaced FAB",
            ".clickable(role = Role.Button)" in scrollControl,
        )
    }

    private fun chatsScreenSource(): File =
        listOf(
            File("src/main/java/dev/ipf/whitenoise/android/ui/chats/ChatsScreen.kt"),
            File("app/src/main/java/dev/ipf/whitenoise/android/ui/chats/ChatsScreen.kt"),
        ).firstOrNull { it.exists() }
            ?: error("Missing ChatsScreen.kt source file")

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
