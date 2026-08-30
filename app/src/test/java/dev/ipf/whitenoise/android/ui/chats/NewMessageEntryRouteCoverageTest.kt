package dev.ipf.whitenoise.android.ui.chats

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class NewMessageEntryRouteCoverageTest {
    @Test
    fun emptyAndNonEmptyChatEntryPointsUseTheSameNewMessageHostTransition() {
        val source = sourceFile().readText()

        assertTrue("the shared transition must open NewChatFlowHost", "if (showNewChatFlow)" in source)
        assertEquals(1, occurrences(source, "onClick = openNewMessageFlow"))
        assertEquals(1, occurrences(source, "EmptyChats(onCreate = openNewMessageFlow)"))
    }

    private fun sourceFile(): File =
        generateSequence(File(requireNotNull(System.getProperty("user.dir")))) { it.parentFile }
            .map { File(it, "app/src/main/java/dev/ipf/whitenoise/android/ui/chats/ChatsScreen.kt") }
            .first(File::isFile)

    private fun occurrences(
        source: String,
        needle: String,
    ): Int = source.windowed(needle.length).count { it == needle }
}
