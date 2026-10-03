package dev.ipf.whitenoise.android.ui.settings

import android.content.Context
import androidx.compose.material3.Surface
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeUp
import androidx.test.core.app.ApplicationProvider
import dev.ipf.marmotkit.AccountSummaryFfi
import dev.ipf.whitenoise.android.R
import dev.ipf.whitenoise.android.state.ChatFolderPreferences
import dev.ipf.whitenoise.android.state.ChatFolderRule
import dev.ipf.whitenoise.android.state.DraftPersistence
import dev.ipf.whitenoise.android.state.DraftStore
import dev.ipf.whitenoise.android.state.FolderField
import dev.ipf.whitenoise.android.state.FolderMode
import dev.ipf.whitenoise.android.state.SmartFolderCodec
import dev.ipf.whitenoise.android.state.SmartFolderFilter
import dev.ipf.whitenoise.android.state.WhiteNoiseAppState
import dev.ipf.whitenoise.android.ui.common.WHITE_NOISE_TOP_BAR_BACK_TAG
import dev.ipf.whitenoise.android.ui.theme.WhiteNoiseTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
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
    fun firstArchivedFilterReplacesTheDefaultScopeWithoutAnOppositeConstraint() {
        val appState = appState()
        renderEditor(appState, {}, folderId = null)
        composeRule.onNodeWithTag("folder.name").performTextReplacement("Archive")
        openFilterSheet()
        composeRule.onNodeWithTag("sheet.dragHandle", useUnmergedTree = true).performTouchInput {
            swipeUp(endY = -450f)
        }
        composeRule.mainClock.advanceTimeBy(1000L)
        composeRule
            .onNodeWithTag("folder.addField.ARCHIVED")
            .performScrollTo()
            .assertIsDisplayed()
            .performClick()
        composeRule.onNodeWithTag("folder.conditionDone").performClick()
        composeRule.onNodeWithTag("folder.save").performClick()
        val folder = appState.chatFolderPreferences.foldersFor(ACCOUNT_REF).first { it.name == "Archive" }
        val rule = appState.chatFolderPreferences.folderRule(ACCOUNT_REF, folder.id)!!
        val root = SmartFolderCodec.decode(rule.smartFilter!!)!!
        val conditions = root.children.filterIsInstance<SmartFolderFilter.Condition>()
        val archived = conditions.filter { it.field == FolderField.ARCHIVED }
        assertEquals(1, archived.size)
        assertEquals(FolderMode.PRESENT, archived.single().mode)
    }

    @Test
    fun clearingLastSavedFilterKeepsSimpleControlsEditable() {
        val appState = appState()
        val store = appState.chatFolderPreferences
        val id = ChatFolderPreferences.SYSTEM_FOLDER_UNREAD_ID
        store.foldersFor(ACCOUNT_REF)
        store.commitFolderDraft(ACCOUNT_REF, id, null, "", emptySet(), ChatFolderRule(keyword = "Before"))
        renderEditor(appState, {})
        val content = composeRule.onNodeWithTag(CHAT_FOLDER_EDIT_CONTENT_TAG)
        content.performScrollToNode(hasTestTag("folder.legacyEdit"))
        composeRule.onNodeWithTag("folder.legacyEdit").performClick()
        content.performScrollToNode(hasTestTag("folder.keyword"))
        composeRule.onNodeWithTag("folder.keyword").performTextReplacement("")
        composeRule.onNodeWithTag("folder.keyword").performTextReplacement("After")
        composeRule.onNodeWithTag("folder.save").performClick()
        assertEquals("After", store.folderRule(ACCOUNT_REF, id)?.keyword)
    }

    @Test
    fun turningOffLastSavedSwitchKeepsItAvailableToTurnOnAgain() {
        val appState = appState()
        val store = appState.chatFolderPreferences
        val id = ChatFolderPreferences.SYSTEM_FOLDER_UNREAD_ID
        store.foldersFor(ACCOUNT_REF)
        store.commitFolderDraft(ACCOUNT_REF, id, null, "", emptySet(), ChatFolderRule(unreadOnly = true))
        renderEditor(appState, {})
        val content = composeRule.onNodeWithTag(CHAT_FOLDER_EDIT_CONTENT_TAG)
        content.performScrollToNode(hasTestTag("folder.legacyEdit"))
        composeRule.onNodeWithTag("folder.legacyEdit").performClick()
        val label = app.getString(R.string.chat_folder_unread_only)
        content.performScrollToNode(hasText(label))
        composeRule.onNodeWithText(label).performClick()
        composeRule.onNodeWithText(label).assertIsOff()
        content.performScrollToNode(hasText(label))
        composeRule.onNodeWithText(label).performClick()
        composeRule.onNodeWithText(label).assertIsOn()
    }

    @Test
    fun optionalDescriptionCanBeAddedAndSavedWithoutChangingManualRules() {
        val appState = appState()
        renderEditor(appState, {}, folderId = null)
        composeRule.onNodeWithTag("folder.name").performTextReplacement("Personal")
        composeRule.onNodeWithTag("folder.description").assertDoesNotExist()
        composeRule.onNodeWithTag("folder.addDescription").performClick()
        composeRule.onNodeWithTag("folder.description").performTextReplacement("My notes")
        composeRule.onNodeWithTag("folder.save").performClick()
        val folder = appState.chatFolderPreferences.foldersFor(ACCOUNT_REF).first { it.name == "Personal" }
        assertEquals("My notes", folder.description)
        assertEquals(null, appState.chatFolderPreferences.folderRule(ACCOUNT_REF, folder.id))
    }

    @Test
    fun cancellingFirstFilterKeepsManualFolderAndOrdinaryWindow() {
        val appState = appState()
        renderEditor(appState, {}, folderId = null)
        composeRule.onNodeWithTag("folder.name").performTextReplacement("Personal")
        openFilterSheet()
        composeRule.onNodeWithTag("folder.addField.UNREAD").performClick()
        composeRule.onNodeWithText(app.getString(R.string.cancel)).performClick()
        composeRule.onNodeWithTag("folder.group.").assertDoesNotExist()
        composeRule.onNodeWithTag("folder.save").performClick()
        val folder = appState.chatFolderPreferences.foldersFor(ACCOUNT_REF).first { it.name == "Personal" }
        assertEquals(null, appState.chatFolderPreferences.folderRule(ACCOUNT_REF, folder.id))
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

    @Test
    fun deleteFromEditorConfirmsExactFolderWithoutSavingDraft() {
        val appState = appState()
        appState.chatFolderPreferences.foldersFor(ACCOUNT_REF)
        var closed = false
        renderEditor(appState, onClose = { closed = true })

        composeRule
            .onNodeWithText(app.getString(R.string.chat_list_filter_unread))
            .performTextReplacement("Unsaved name")
        composeRule.onNodeWithTag(CHAT_FOLDER_EDIT_CONTENT_TAG).performScrollToNode(hasTestTag("folder.delete"))
        composeRule.onNodeWithTag("folder.delete").performClick()
        composeRule.onNodeWithText(app.getString(R.string.folder_delete_title, "Unread")).assertExists()
        composeRule.onNodeWithText(app.getString(R.string.folder_delete_detail)).assertExists()
        composeRule.onNodeWithText(app.getString(R.string.cancel)).performClick()
        composeRule.onNodeWithText("Unsaved name").assertExists()
        val folderStillExists =
            appState.chatFolderPreferences.foldersFor(ACCOUNT_REF).any {
                it.id == ChatFolderPreferences.SYSTEM_FOLDER_UNREAD_ID
            }
        assertTrue(folderStillExists)

        composeRule.onNodeWithTag("folder.delete").performClick()
        composeRule.onNodeWithTag("folder.delete_confirm").performClick()
        assertTrue(closed)
        val remaining = appState.chatFolderPreferences.foldersFor(ACCOUNT_REF)
        assertTrue(remaining.none { it.id == ChatFolderPreferences.SYSTEM_FOLDER_UNREAD_ID })
        assertTrue(remaining.any { it.id == ChatFolderPreferences.SYSTEM_FOLDER_ARCHIVED_ID })
        assertTrue(remaining.none { it.name == "Unsaved name" })
    }

    @Test
    fun backWhileDeleteConfirmationOpenKeepsUnsavedEdits() {
        val appState = appState()
        appState.chatFolderPreferences.foldersFor(ACCOUNT_REF)
        var closed = false
        renderEditor(appState, onClose = { closed = true })
        composeRule
            .onNodeWithText(app.getString(R.string.chat_list_filter_unread))
            .performTextReplacement("Unsaved name")
        composeRule.onNodeWithTag(CHAT_FOLDER_EDIT_CONTENT_TAG).performScrollToNode(hasTestTag("folder.delete"))
        composeRule.onNodeWithTag("folder.delete").performClick()

        composeRule.onNodeWithTag(WHITE_NOISE_TOP_BAR_BACK_TAG).performClick()

        composeRule.onNodeWithTag("folder.delete_dialog").assertDoesNotExist()
        composeRule.onNodeWithText("Unsaved name").assertExists()
        assertTrue(!closed)
        val folderStillExists =
            appState.chatFolderPreferences.foldersFor(ACCOUNT_REF).any {
                it.id == ChatFolderPreferences.SYSTEM_FOLDER_UNREAD_ID
            }
        assertTrue(folderStillExists)
    }

    @Test
    fun attentionCategoriesSaveAndGroupDirectChoicesAreExclusive() {
        val appState = appState()
        appState.chatFolderPreferences.foldersFor(ACCOUNT_REF)
        renderEditor(appState, onClose = {})
        composeRule.onNodeWithTag(CHAT_FOLDER_EDIT_CONTENT_TAG).performScrollToNode(hasTestTag("folder.legacyEdit"))
        composeRule.onNodeWithTag("folder.legacyEdit").performClick()

        fun toggle(label: Int) {
            val text = app.getString(label)
            composeRule.onNodeWithTag(CHAT_FOLDER_EDIT_CONTENT_TAG).performScrollToNode(hasText(text))
            composeRule.onNodeWithText(text).performClick()
        }
        toggle(R.string.chat_folder_unread_mentions_only)
        toggle(R.string.chat_folder_pinned_only)
        toggle(R.string.chat_folder_groups_only)
        toggle(R.string.chat_folder_direct_chats_only)
        composeRule.onNodeWithText(app.getString(R.string.chat_folder_groups_only)).assertIsOff()
        composeRule.onNodeWithText(app.getString(R.string.chat_folder_direct_chats_only)).assertIsOn()
        toggle(R.string.chat_folder_groups_only)
        composeRule.onNodeWithText(app.getString(R.string.chat_folder_direct_chats_only)).assertIsOff()
        composeRule.onNodeWithText(app.getString(R.string.save)).performClick()
        val reloaded = ChatFolderPreferences(app)
        assertEquals(
            ChatFolderRule(
                unreadOnly = true,
                includeMuted = true,
                unreadMentionsOnly = true,
                pinnedOnly = true,
                groupsOnly = true,
            ),
            reloaded.folderRule(ACCOUNT_REF, ChatFolderPreferences.SYSTEM_FOLDER_UNREAD_ID),
        )
    }

    @Test
    fun newCategoryChangesAreDiscardableAndDoNotPersistBeforeSave() {
        val appState = appState()
        appState.chatFolderPreferences.foldersFor(ACCOUNT_REF)
        var closed = false
        renderEditor(appState, onClose = { closed = true })
        composeRule.onNodeWithTag(CHAT_FOLDER_EDIT_CONTENT_TAG).performScrollToNode(hasTestTag("folder.legacyEdit"))
        composeRule.onNodeWithTag("folder.legacyEdit").performClick()
        val label = app.getString(R.string.chat_folder_unread_mentions_only)
        composeRule.onNodeWithTag(CHAT_FOLDER_EDIT_CONTENT_TAG).performScrollToNode(hasText(label))
        composeRule.onNodeWithText(label).performClick()
        val original =
            appState.chatFolderPreferences.folderRule(
                ACCOUNT_REF,
                ChatFolderPreferences.SYSTEM_FOLDER_UNREAD_ID,
            )
        assertEquals(false, original?.unreadMentionsOnly)
        composeRule.onNodeWithTag(WHITE_NOISE_TOP_BAR_BACK_TAG).performClick()
        composeRule.onNodeWithText(app.getString(R.string.folder_keep_editing)).performClick()
        composeRule.onNodeWithText(label).assertIsOn()
        composeRule.onNodeWithTag(WHITE_NOISE_TOP_BAR_BACK_TAG).performClick()
        composeRule.onNodeWithText(app.getString(R.string.folder_discard)).performClick()
        assertTrue(closed)
        val reloaded = ChatFolderPreferences(app).folderRule(ACCOUNT_REF, ChatFolderPreferences.SYSTEM_FOLDER_UNREAD_ID)
        assertEquals(false, reloaded?.unreadMentionsOnly)
    }

    @Test
    fun renamingFolderPreservesUnsupportedRulesAndManualMembership() {
        val appState = appState()
        val store = appState.chatFolderPreferences
        val id = ChatFolderPreferences.SYSTEM_FOLDER_UNREAD_ID
        store.foldersFor(ACCOUNT_REF)
        val futureRule = ChatFolderRule(smartFilter = """{"version":99,"root":{}}""")
        val manual = setOf("b".repeat(64))
        store.commitFolderDraft(ACCOUNT_REF, id, null, "", manual, futureRule)
        var closed = false
        renderEditor(appState, onClose = { closed = true })
        composeRule.onNodeWithText(app.getString(R.string.chat_list_filter_unread)).performTextReplacement("Future")
        composeRule.onNodeWithText(app.getString(R.string.save)).performClick()
        assertTrue(closed)
        val reloaded = ChatFolderPreferences(app)
        assertEquals("Future", reloaded.foldersFor(ACCOUNT_REF).first { it.id == id }.name)
        assertEquals(futureRule, reloaded.folderRule(ACCOUNT_REF, id))
        assertEquals(manual, reloaded.membershipFor(ACCOUNT_REF, id))
    }

    @Test
    fun newManualFolderPreservesOrdinaryWindowRulesAndIncludedChats() {
        val appState = appState()
        val manual = setOf("b".repeat(64))
        var closed = false
        renderEditor(appState, { closed = true }, folderId = null, initialManualChatIds = manual)
        composeRule.onNodeWithTag("folder.name").performTextReplacement("Personal")
        composeRule.onNodeWithTag(CHAT_FOLDER_EDIT_CONTENT_TAG).performScrollToNode(hasTestTag("folder.add."))
        composeRule.onNodeWithTag("folder.legacyEdit").assertDoesNotExist()
        composeRule.onNodeWithTag("folder.group.").assertDoesNotExist()
        composeRule.onNodeWithTag("folder.save").performClick()
        assertTrue(closed)
        val reloaded = ChatFolderPreferences(app)
        val folder = reloaded.foldersFor(ACCOUNT_REF).first { it.name == "Personal" }
        assertNull(reloaded.folderRule(ACCOUNT_REF, folder.id)?.smartFilter)
        assertEquals(manual, reloaded.membershipFor(ACCOUNT_REF, folder.id))
    }

    @Test
    fun newFolderEntersAdvancedModeOnlyAfterExplicitChoice() {
        val appState = appState()
        renderEditor(appState, {}, folderId = null)
        composeRule.onNodeWithTag("folder.name").performTextReplacement("Advanced")
        openFilterSheet()
        composeRule.onNodeWithTag("folder.addField.MENTIONS").performClick()
        composeRule.onNodeWithTag("folder.group.").assertDoesNotExist()
        composeRule.onNodeWithTag("folder.conditionDone").performClick()
        composeRule.onNodeWithTag("folder.group.").assertExists()
        composeRule.onNodeWithTag("folder.save").performClick()
        val reloaded = ChatFolderPreferences(app)
        val folder = reloaded.foldersFor(ACCOUNT_REF).first { it.name == "Advanced" }
        assertTrue(reloaded.folderRule(ACCOUNT_REF, folder.id)?.smartFilter != null)
    }

    private fun openFilterSheet() {
        composeRule.onNodeWithTag(CHAT_FOLDER_EDIT_CONTENT_TAG).performScrollToNode(hasTestTag("folder.add."))
        composeRule.onNodeWithTag("folder.add.").performClick()
        composeRule.mainClock.advanceTimeBy(1000L)
    }

    private fun renderEditor(
        appState: WhiteNoiseAppState,
        onClose: () -> Unit,
        folderId: String? = ChatFolderPreferences.SYSTEM_FOLDER_UNREAD_ID,
        initialManualChatIds: Set<String> = emptySet(),
    ) {
        composeRule.setContent {
            WhiteNoiseTheme {
                Surface {
                    ChatFolderEditScreen(
                        appState = appState,
                        accountRef = ACCOUNT_REF,
                        folderId = folderId,
                        onClose = onClose,
                        initialManualChatIds = initialManualChatIds,
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
