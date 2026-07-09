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
            "the expensive search still runs against the current source list snapshot",
            "controller.searchMessageBodies(sourceList, trimmedQuery)" in source,
        )
    }

    private fun chatsScreenSource(): File =
        listOf(
            File("src/main/java/dev/ipf/whitenoise/android/ui/chats/ChatsScreen.kt"),
            File("app/src/main/java/dev/ipf/whitenoise/android/ui/chats/ChatsScreen.kt"),
        ).firstOrNull { it.exists() }
            ?: error("Missing ChatsScreen.kt source file")
}
