package dev.ipf.whitenoise.android.ui.conversation.composer

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class ComposerPillEmojiToggleCoverageTest {
    @Test
    fun closedEmojiPickerUsesOutlinedSmileyAndOpenUsesKeyboard() {
        val source = composerPillSource().readText()
        val emojiToggleIcon =
            Regex(
                """if\s*\(\s*emojiPickerOpen\s*\)\s*Icons\.Default\.Keyboard\s*else\s*Icons\.(\w+)\.EmojiEmotions""",
            ).find(source)
                ?: error("ComposerPill must toggle between Keyboard and EmojiEmotions icons")

        assertTrue(
            "closed emoji picker should use the outlined smiley glyph, not the filled one",
            emojiToggleIcon.groupValues[1] == "Outlined",
        )
    }

    private fun composerPillSource(): File =
        listOf(
            File("src/main/java/dev/ipf/whitenoise/android/ui/conversation/composer/ComposerPills.kt"),
            File("app/src/main/java/dev/ipf/whitenoise/android/ui/conversation/composer/ComposerPills.kt"),
        ).firstOrNull { it.exists() }
            ?: error("Missing ComposerPills.kt source file")
}
