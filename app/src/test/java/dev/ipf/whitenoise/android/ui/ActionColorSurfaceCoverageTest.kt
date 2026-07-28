package dev.ipf.whitenoise.android.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class ActionColorSurfaceCoverageTest {
    @Test
    fun namedActionAndUnreadSurfacesConsumeTheAccountActionToken() {
        val chats = source("chats/ChatsScreen.kt")
        val composer = source("conversation/composer/ComposerBar.kt")
        val chatRow = source("chats/ChatRow.kt")
        val topBar = source("chats/ChatListTopBar.kt")
        val accountSwitcher = source("account/AccountSwitcher.kt")
        val accountSelector = source("account/AccountSelectorSheet.kt")

        assertTrue("New message must resolve the active account token", "accountActionColors(appState)" in chats)
        assertTrue("New message must use the resolved container", "containerColor = actionColors.container" in chats)
        assertTrue("New message must use the resolved foreground", "contentColor = actionColors.content" in chats)

        assertEquals(
            "Both text Send and locked-voice Send must use the resolved container",
            2,
            Regex("""containerColor = actionColors\.container""").findAll(composer).count(),
        )
        assertEquals(
            "Both text Send and locked-voice Send must use the resolved foreground",
            2,
            Regex("""contentColor = actionColors\.content""").findAll(composer).count(),
        )

        assertTrue(
            "Chat unread counts must use the active account token",
            "actionColors = accountActionColors(appState)" in chatRow,
        )
        assertTrue(
            "The active account dot must use its represented account",
            "accountActionColors(appState, active?.label)" in topBar,
        )
        assertTrue(
            "Other-account dots must use each represented account",
            "accountActionColors(appState, account.label)" in accountSwitcher,
        )
        assertTrue(
            "Account-selector counts must use each represented account",
            "actionColorsForAccount = { accountRef -> accountActionColors(appState, accountRef) }" in accountSelector,
        )
    }

    @Test
    fun colorPickerDraftStateIsScopedToTheActiveAccount() {
        val bubbleColors = source("settings/ChatBubbleColorsScreen.kt")

        assertTrue(
            "Bubble picker draft state must include the represented local account",
            "\"account:\$accountScope:global\"" in bubbleColors,
        )
        assertTrue(
            "Per-chat picker draft state must retain both account and chat scope",
            "\"account:\$accountScope:chat:\$it\"" in bubbleColors,
        )
    }

    private fun source(relativePath: String): String =
        sequenceOf(
            File("src/main/java/dev/ipf/whitenoise/android/ui/$relativePath"),
            File("app/src/main/java/dev/ipf/whitenoise/android/ui/$relativePath"),
        ).firstOrNull(File::isFile)?.readText()
            ?: error("Missing UI source: $relativePath")
}
