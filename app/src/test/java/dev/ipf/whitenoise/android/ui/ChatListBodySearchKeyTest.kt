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
            "val bodySearchGroupIds = remember(sourceList) { sourceList.map { it.id }.sorted() }" in source,
        )
        assertTrue(
            "body-search effect must not key directly on sourceList identity",
            "LaunchedEffect(trimmedQuery, showArchived, bodySearchGroupIds)" in source &&
                "LaunchedEffect(trimmedQuery, sourceList)" !in source,
        )
        assertTrue(
            "ordinary message-body search must keep using the trimmed text query",
            "controller.searchMessageBodies(sourceList, trimmedQuery)" in source,
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
