package dev.ipf.whitenoise.android.ui.settings

import android.content.Context
import androidx.compose.material3.Surface
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextReplacement
import androidx.test.core.app.ApplicationProvider
import dev.ipf.marmotkit.AccountSummaryFfi
import dev.ipf.whitenoise.android.R
import dev.ipf.whitenoise.android.state.ChatFolderPreferences
import dev.ipf.whitenoise.android.state.DraftPersistence
import dev.ipf.whitenoise.android.state.DraftStore
import dev.ipf.whitenoise.android.state.WhiteNoiseAppState
import dev.ipf.whitenoise.android.ui.theme.WhiteNoiseTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], qualifiers = "en")
class ChatFolderEditScreenTest {
    @get:Rule
    val composeRule = createComposeRule()

    private val app = ApplicationProvider.getApplicationContext<Context>()

    @Before
    fun clearFolderPreferences() {
        app
            .getSharedPreferences("whitenoise.chat_folders", Context.MODE_PRIVATE)
            .edit()
            .clear()
            .commit()
    }

    @Test
    fun savingAnUntouchedDefaultKeepsItsStoredNameBlank() {
        val appState = appState()
        appState.chatFolderPreferences.foldersFor(ACCOUNT_REF)
        var closed = false

        renderEditor(appState, onClose = { closed = true })
        // Save with the prefilled localized label untouched — a rule tweak,
        // not a rename: the stored name must stay blank so the folder keeps
        // following locale changes.
        composeRule.onNodeWithText(app.getString(R.string.save)).performClick()

        assertTrue(closed)
        val unread =
            appState.chatFolderPreferences
                .foldersFor(ACCOUNT_REF)
                .first { it.id == ChatFolderPreferences.SYSTEM_FOLDER_UNREAD_ID }
        assertEquals("", unread.name)
    }

    @Test
    fun typingANewNamePersistsTheRename() {
        val appState = appState()
        appState.chatFolderPreferences.foldersFor(ACCOUNT_REF)

        renderEditor(appState, onClose = {})
        composeRule
            .onNodeWithText(app.getString(R.string.chat_list_filter_unread))
            .performTextReplacement("Catch up")
        composeRule.onNodeWithText(app.getString(R.string.save)).performClick()

        val unread =
            appState.chatFolderPreferences
                .foldersFor(ACCOUNT_REF)
                .first { it.id == ChatFolderPreferences.SYSTEM_FOLDER_UNREAD_ID }
        assertEquals("Catch up", unread.name)
    }

    private fun renderEditor(
        appState: WhiteNoiseAppState,
        onClose: () -> Unit,
    ) {
        composeRule.setContent {
            WhiteNoiseTheme {
                Surface {
                    ChatFolderEditScreen(
                        appState = appState,
                        accountRef = ACCOUNT_REF,
                        folderId = ChatFolderPreferences.SYSTEM_FOLDER_UNREAD_ID,
                        onClose = onClose,
                    )
                }
            }
        }
        composeRule.waitForIdle()
    }

    private fun appState() =
        WhiteNoiseAppState(
            context = app,
            draftStore = DraftStore(InMemoryDraftPersistence()),
            accountIdHexResolver = { null },
            accounts = listOf(activeAccount()),
            activeAccountRef = ACCOUNT_REF,
        )

    private fun activeAccount() =
        AccountSummaryFfi(
            label = ACCOUNT_REF,
            accountIdHex = ACCOUNT_HEX,
            localSigning = true,
            externalSigning = false,
            signedOut = false,
            running = true,
        )

    private class InMemoryDraftPersistence : DraftPersistence {
        override fun read(): Map<String, String> = emptyMap()

        override fun write(
            key: String,
            value: String?,
        ) = Unit
    }

    private companion object {
        const val ACCOUNT_REF = "acct-a"
        val ACCOUNT_HEX = "a".repeat(64)
    }
}
