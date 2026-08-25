package dev.ipf.whitenoise.android.ui

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class ChatListBodySearchKeyTest {
    @Test
    fun bodySearchEffectKeysOnStableGroupIdsNotLiveSourceListIdentity() {
        val source = chatsScreenSource().readText()

        assertTrue(
            "body-search must derive a stable sorted id snapshot",
            "sourceList.map { canonicalChatListGroupId(it.id) }.distinct().sorted()" in source,
        )
        assertTrue(
            "body-search effect must key by the stable account-aware search key, not sourceList identity",
            "LaunchedEffect(bodySearchRequest)" in source &&
                "LaunchedEffect(trimmedQuery, sourceList)" !in source,
        )
        assertTrue(
            "body-search and viewport datasets must use the locale-invariant normalized query",
            "query = normalizedSearchQuery" in source &&
                "remember(normalizedSearchQuery, controller.boundAccountRef" in source,
        )
        assertTrue(
            "a repeated A-B-A query must hide the first A result before the replacement effect runs",
            "remember(bodySearchKey) { ChatListBodySearchRequest() }" in source &&
                "?.takeIf { it.request === bodySearchRequest }" in source,
        )
        assertTrue(
            "body-search results must share the projection's canonical group-id identity",
            "canonicalChatListBodyMatches(controller.searchMessageBodies(sourceList, trimmedQuery))" in source,
        )
        assertTrue(
            "ordinary message-body search must keep using the trimmed text query",
            "controller.searchMessageBodies(sourceList, trimmedQuery)" in source,
        )
        assertTrue(
            "search interactions and lazy rows must share canonical identity",
            "visibleItems.mapTo(mutableSetOf(), ::visibleRowId)" in source &&
                "key = visibleRowId(item)" in source,
        )
        assertTrue(
            "unsupported typed filters must not suppress ordinary body matches",
            "requiresTypedMdkContract" !in source,
        )
    }

    @Test
    fun typedFiltersStayOutOfProductionUntilMdkSearchSupportsThem() {
        val source = chatsScreenSource().readText()

        assertTrue(
            "typed filter controls must stay gated while the MDK contract is unavailable",
            "val interactiveGlobalSearchFilterSectionsAvailable = false" in source,
        )
        assertTrue(
            "the typed filter sheet must not be wired into the production chat list yet",
            "GlobalSearchTypedFilterSheet(" !in source,
        )
    }

    private fun chatsScreenSource(): File =
        listOf(
            File("src/main/java/dev/ipf/whitenoise/android/ui/chats/ChatsScreen.kt"),
            File("app/src/main/java/dev/ipf/whitenoise/android/ui/chats/ChatsScreen.kt"),
        ).firstOrNull { it.exists() }
            ?: error("Missing ChatsScreen.kt source file")
}
