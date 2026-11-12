package dev.ipf.whitenoise.android.ui.chats

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class NewMessageEntryRouteCoverageTest {
    /** Empty and non empty chat entry points use the same new message host transition. */
    @Test
    fun emptyAndNonEmptyChatEntryPointsUseTheSameNewMessageHostTransition() {
        val source = sourceFile().readText()

        assertTrue("the shared transition must open NewChatFlowHost", "if (showNewChatFlow)" in source)
        assertEquals(1, Regex("""ChatsNewMessageFab\(\s*appState,\s*openNewMessageFlow,""").findAll(source).count())
        assertEquals(1, Regex("""EmptyChats\(onCreate = openNewMessageFlow(?:,|\))""").findAll(source).count())
    }

    private fun sourceFile(): File =
        generateSequence(File(requireNotNull(System.getProperty("user.dir")))) { it.parentFile }
            .map { File(it, "app/src/main/java/dev/ipf/whitenoise/android/ui/chats/ChatsScreen.kt") }
            .first(File::isFile)
}
