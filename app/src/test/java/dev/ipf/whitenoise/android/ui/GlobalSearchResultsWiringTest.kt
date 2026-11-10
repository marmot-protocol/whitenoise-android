package dev.ipf.whitenoise.android.ui

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** Structural guard for global message search; its behavior is covered by the GlobalSearchResults* tests. */
class GlobalSearchResultsWiringTest {
    /** The per-chat body scan is gone, so search cost no longer grows with the number of chats. */
    @Test
    fun perChatBodyScanIsGone() {
        val sources = listOf("ui/chats/ChatsScreen.kt", "state/Controllers.kt").map { sourceFile(it).readText() }

        assertTrue(
            "message search must not fan out one timeline scan per chat",
            listOf("searchMessageBodies", "searchOneChat").none { name -> sources.any { name in it } },
        )
    }

    /** Resolves a main source file from either the module or the repository root. */
    private fun sourceFile(relative: String): File =
        listOf(
            File("src/main/java/dev/ipf/whitenoise/android/$relative"),
            File("app/src/main/java/dev/ipf/whitenoise/android/$relative"),
        ).firstOrNull { it.exists() }
            ?: error("Missing $relative source file")
}
