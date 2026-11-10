package dev.ipf.whitenoise.android.ui

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** Source contracts of the chat-list search wiring that the behavioral tests cannot see from outside. */
class GlobalSearchResultsWiringTest {
    /** Message results come from MDK's bounded account-wide session, never a per-chat fan-out. */
    @Test
    fun messageResultsUseTheAccountWideSessionNotAPerChatFanOut() {
        val source = chatsScreenSource().readText()
        val controllers = sourceFile("state/Controllers.kt").readText()

        assertTrue(
            "the per-chat body scan must be gone",
            listOf("searchMessageBodies", "searchOneChat").none { it in source || it in controllers },
        )
        assertTrue(
            "the screen must page MDK's search through the shared session",
            "rememberGlobalSearchResults(" in source && "searchViewport?.messageResultsExtent" in source,
        )
        assertTrue(
            "one rule decides when message results show",
            "globalSearchShowsMessageResults(" in source,
        )
        assertTrue(
            "results render as their own stable-id rows below the chat-name matches",
            "globalSearchResultItems(messageResultsContent)" in source,
        )
        assertTrue(
            "a result opens its chat at the message through the shared search navigation",
            "openSearchMessage(result.groupIdHex, result.messageIdHex, messageResultChats)" in source,
        )
    }

    /** Chat-name rows keep their canonical identity and the viewport keys on the normalized query. */
    @Test
    fun chatNameRowsAndViewportKeepCanonicalIdentity() {
        val source = chatsScreenSource().readText()

        assertTrue(
            "the viewport dataset must use the locale-invariant normalized query",
            "query = normalizedSearchQuery" in source,
        )
        assertTrue(
            "search interactions and lazy rows must share canonical identity",
            "visibleItems.mapTo(mutableSetOf(), ::visibleRowId)" in source &&
                "key = visibleRowId(item)" in source,
        )
        assertTrue(
            "the category picker must be mounted from the shell-owned search state",
            "GlobalSearchFilterPicker(" in source,
        )
        assertTrue(
            "unsupported typed filters must not suppress message results",
            "requiresTypedMdkContract" !in source,
        )
    }

    /** The typed query sets MDK's wall-clock account search and its three-part cursor. */
    @Test
    fun typedSearchQueryUsesWallClockOrderAndTheGroupCursor() {
        val queries = sourceFile("state/TimelineMessageQueries.kt").readText()

        assertTrue("account search must use wall-clock order", "searchWallClock = true" in queries)
        assertTrue("the cursor must carry its group component", "cursorGroupId = cursor?.third" in queries)
    }

    /** Resolves ChatsScreen.kt from either the module or the repository root. */
    private fun chatsScreenSource(): File = sourceFile("ui/chats/ChatsScreen.kt")

    /** Resolves a main source file from either the module or the repository root. */
    private fun sourceFile(relative: String): File =
        listOf(
            File("src/main/java/dev/ipf/whitenoise/android/$relative"),
            File("app/src/main/java/dev/ipf/whitenoise/android/$relative"),
        ).firstOrNull { it.exists() }
            ?: error("Missing $relative source file")
}
