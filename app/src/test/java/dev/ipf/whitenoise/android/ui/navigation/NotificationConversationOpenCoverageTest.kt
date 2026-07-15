package dev.ipf.whitenoise.android.ui.navigation

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class NotificationConversationOpenCoverageTest {
    @Test
    fun notificationOpenReadsThroughNewestMessageWithoutFocusingIt() {
        val source = mainShellSource().readText()
        val notificationOpenBlock =
            source.requiredSection(
                start = "is NotificationNavStep.OpenConversation -> {",
                end = "\n            NotificationNavStep.MissingAccount -> {",
            )

        assertTrue(
            "notification opens must clear search-message focus so ConversationScreen keeps its first-unread anchor",
            Regex("""selectedChatFocusMessageId\s*=\s*null""").containsMatchIn(notificationOpenBlock),
        )
        assertFalse(
            "the notification message id is a read-through cursor, not a search-message focus target",
            Regex("""selectedChatFocusMessageId\s*=\s*step\.[A-Za-z]+MessageIdHex""")
                .containsMatchIn(notificationOpenBlock),
        )
        assertTrue(
            "notification opens must still persist the read-through cursor before composition",
            Regex("""step\.readThroughMessageIdHex\?\.let[\s\S]*markNotificationMessageRead""")
                .containsMatchIn(notificationOpenBlock),
        )
    }

    private fun mainShellSource(): File =
        listOf(
            File("src/main/java/dev/ipf/whitenoise/android/ui/navigation/MainShell.kt"),
            File("app/src/main/java/dev/ipf/whitenoise/android/ui/navigation/MainShell.kt"),
        ).firstOrNull { it.exists() }
            ?: error("Missing MainShell.kt source file")

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
