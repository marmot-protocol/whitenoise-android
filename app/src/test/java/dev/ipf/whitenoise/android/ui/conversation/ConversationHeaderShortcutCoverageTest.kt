package dev.ipf.whitenoise.android.ui.conversation

import dev.ipf.whitenoise.android.appModuleSource
import org.junit.Assert.assertTrue
import org.junit.Test

/** Header shortcuts and the header timer reuse the chat details owners instead of copying their commands. */
class ConversationHeaderShortcutCoverageTest {
    private val conversation = appModuleSource("$UI/conversation/ConversationScreen.kt")
    private val details = appModuleSource("$UI/group/GroupDetailsScreen.kt")

    @Test
    fun headerAndDetailsShareMuteAndTimerOwners() {
        for (source in listOf(conversation, details)) {
            assertContains(source, "rememberConversationMuteControl(appState, controller)")
            assertContains(source, "MuteDurationHost(")
            assertContains(source, "DisappearingTimerEditor(")
            assertContains(source, "disappearingTimerEditable(")
        }
        assertContains(details, "onClick = muteControl::toggle")
    }

    @Test
    fun headerAndDetailsShareTheSearchHandler() {
        assertContains(conversation, "onOpenSearch = openConversationSearch")
        assertContains(conversation, "onSearch = openConversationSearch")
    }

    private fun assertContains(
        source: String,
        expected: String,
    ) = assertTrue("Missing `$expected`", source.contains(expected))

    private companion object {
        const val UI = "src/main/java/dev/ipf/whitenoise/android/ui"
    }
}
