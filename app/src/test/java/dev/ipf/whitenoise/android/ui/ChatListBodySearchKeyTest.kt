package dev.ipf.whitenoise.android.ui

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class ChatListBodySearchKeyTest {
    @Test
    fun bodySearchEffectKeysOnStableGroupIdsNotLiveSourceListIdentity() {
        val source = chatsScreenSource().readText()
        val stableEffectKey =
            "LaunchedEffect(trimmedQuery, showArchived, bodySearchGroupIds, " +
                "searchProjection.requiresTypedMdkContract)"
        val typedGuardIndex = source.indexOf("if (searchProjection.requiresTypedMdkContract)")
        val clearMatchesIndex = source.indexOf("bodyMatches = emptyMap()", startIndex = typedGuardIndex)
        val legacySearchIndex = source.indexOf("controller.searchMessageBodies(sourceList, trimmedQuery)")

        assertTrue(
            "body-search must derive a stable sorted id snapshot",
            "val bodySearchGroupIds = remember(sourceList) { sourceList.map { it.id }.sorted() }" in source,
        )
        assertTrue(
            "body-search effect must not key directly on sourceList identity",
            stableEffectKey in source &&
                "LaunchedEffect(trimmedQuery, sourceList)" !in source,
        )
        assertTrue(
            "the expensive search still runs against the current source list snapshot",
            "controller.searchMessageBodies(sourceList, trimmedQuery)" in source,
        )
        assertTrue(
            "typed filters must clear matches before the legacy body-search call",
            typedGuardIndex >= 0 &&
                clearMatchesIndex > typedGuardIndex &&
                clearMatchesIndex < legacySearchIndex,
        )
    }

    private fun chatsScreenSource(): File =
        listOf(
            File("src/main/java/dev/ipf/whitenoise/android/ui/chats/ChatsScreen.kt"),
            File("app/src/main/java/dev/ipf/whitenoise/android/ui/chats/ChatsScreen.kt"),
        ).firstOrNull { it.exists() }
            ?: error("Missing ChatsScreen.kt source file")
}
