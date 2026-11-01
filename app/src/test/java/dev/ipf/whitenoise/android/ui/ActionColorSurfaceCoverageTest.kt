package dev.ipf.whitenoise.android.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** Source-level guards that action and bubble colours flow from the account token and per-account drafts. */
class ActionColorSurfaceCoverageTest {
    /** Account actions and unread markers resolve their colours through the account action token. */
    @Test
    fun accountActionsAndUnreadSurfacesConsumeTheAccountActionToken() {
        // Neutral New Message appearance is covered by rendered theme goldens.
        val composer = source("conversation/composer/ComposerBar.kt")
        val chatRow = source("chats/ChatRow.kt")
        val topBar = source("chats/ChatListTopBar.kt")
        val accountSwitcher = source("account/AccountSwitcher.kt")
        val accountSelector = source("account/AccountSelectorSheet.kt")

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
            "Chat unread counts must use the represented account token",
            "actionColors = accountActionColors(appState, accountRef)" in chatRow,
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

    /** Colour drafts restart whenever the represented account, chat or theme changes, never leaking between them. */
    @Test
    fun colorPickerDraftStateIsScopedToTheActiveAccount() {
        val bubbleColors = source("settings/ChatBubbleColorsScreen.kt")
        val actionColor = source("settings/ActionColorScreen.kt")

        assertTrue(
            "Bubble editor drafts must restart per account, chat and theme",
            "key(accountScope, groupIdHex, bubbleTheme, initial, inherited)" in bubbleColors,
        )
        assertTrue(
            "Action colour drafts must restart per account and theme",
            "key(accountRef, theme, initial)" in actionColor,
        )
    }

    /** Reads one UI source file from either the module or repository root. */
    private fun source(relativePath: String): String =
        sequenceOf(
            File("src/main/java/dev/ipf/whitenoise/android/ui/$relativePath"),
            File("app/src/main/java/dev/ipf/whitenoise/android/ui/$relativePath"),
        ).firstOrNull(File::isFile)?.readText()
            ?: error("Missing UI source: $relativePath")
}
