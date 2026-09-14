package dev.ipf.whitenoise.android.ui

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class ChatListBodySearchKeyTest {
    /** Body search effect keys on stable group ids not live source list identity. */
    @Test
    fun bodySearchEffectKeysOnStableGroupIdsNotLiveSourceListIdentity() {
        val source = chatsScreenSource().readText()

        assertTrue(
            "body-search must derive a stable sorted id snapshot",
            "scopedSourceList.map { canonicalChatListGroupId(it.id) }.distinct().sorted()" in source,
        )
        assertTrue(
            "body-search effect must key by the stable account-aware search key, not sourceList identity",
            "LaunchedEffect(bodySearchRequest)" in source &&
                "LaunchedEffect(trimmedQuery, sourceList)" !in source,
        )
        assertTrue(
            "body-search and viewport datasets must use the locale-invariant normalized query",
            "query = normalizedSearchQuery" in source &&
                Regex("remember\\(\\s*normalizedSearchQuery,\\s*controller\\.boundAccountRef").containsMatchIn(source),
        )
        assertTrue(
            "a repeated A-B-A query must hide the first A result before the replacement effect runs",
            "remember(bodySearchKey) { ChatListBodySearchRequest() }" in source &&
                "?.takeIf { it.request === bodySearchRequest }" in source,
        )
        assertTrue(
            "body-search results must share the projection's canonical group-id identity",
            Regex(
                "canonicalChatListBodyMatches\\(\\s*controller\\.searchMessageBodies\\(" +
                    "scopedSourceList, trimmedQuery, messageSearchConstraints\\)",
            ).containsMatchIn(source),
        )
        assertTrue(
            "ordinary message-body search must keep using the trimmed text query",
            "controller.searchMessageBodies(scopedSourceList, trimmedQuery, messageSearchConstraints)" in source,
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

    /** Prototype filters run client side over the scoped list and constrained body search. */
    @Test
    fun prototypeFiltersRunClientSideOverTheScopedListAndConstrainedBodySearch() {
        val source = chatsScreenSource().readText()

        assertTrue(
            "the category picker must be mounted from the shell-owned search state",
            "GlobalSearchFilterPicker(" in source,
        )
        assertTrue(
            "the body search must receive the sender / date / content constraints",
            "controller.searchMessageBodies(scopedSourceList, trimmedQuery, messageSearchConstraints)" in source,
        )
        assertTrue(
            "the body search key must include the constraints so a filter change restarts it",
            "constraints = messageSearchConstraints," in source,
        )
        assertTrue(
            "no legacy availability gate may remain",
            "interactiveGlobalSearchFilterSectionsAvailable" !in source,
        )
    }

    private fun chatsScreenSource(): File =
        listOf(
            File("src/main/java/dev/ipf/whitenoise/android/ui/chats/ChatsScreen.kt"),
            File("app/src/main/java/dev/ipf/whitenoise/android/ui/chats/ChatsScreen.kt"),
        ).firstOrNull { it.exists() }
            ?: error("Missing ChatsScreen.kt source file")
}
