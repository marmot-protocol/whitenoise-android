package dev.ipf.whitenoise.android.ui.chats

import android.content.Context
import androidx.activity.ComponentActivity
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.test.performTextReplacement
import androidx.test.core.app.ApplicationProvider
import dev.ipf.marmotkit.AccountSummaryFfi
import dev.ipf.whitenoise.android.R
import dev.ipf.whitenoise.android.state.ChatsController
import dev.ipf.whitenoise.android.state.DraftStore
import dev.ipf.whitenoise.android.state.WhiteNoiseAppState
import dev.ipf.whitenoise.android.ui.settings.CHAT_FOLDERS_CONTENT_TAG
import dev.ipf.whitenoise.android.ui.theme.WhiteNoiseTheme
import kotlinx.coroutines.cancel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Actual Chats manage action opens the existing native manager and preserves shell-owned folder state on return. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], qualifiers = "en-w360dp-h780dp-mdpi")
class ChatFolderManageRouteTest {
    @get:Rule val composeRule = createAndroidComposeRule<ComponentActivity>()

    /** Manager works with no chats and return preserves selected native folder. */
    @Test fun managerWorksWithNoChatsAndReturnPreservesSelectedNativeFolder() = route(teardown = false)

    /** System Back follows the same Home return route as the visible Back button. */
    @Test fun systemBackReturnsHomeAndPreservesSelectedNativeFolder() = route(teardown = false, systemBack = true)

    /** Existing-folder drafts consume Back before the manager returns Home. */
    @Test
    fun existingFolderDraftBackKeepsManagerOpen() = route(teardown = false, systemBack = true, editExisting = true)

    /** New-folder drafts keep their discard confirmation above the manager. */
    @Test
    fun newFolderDraftBackKeepsManagerOpen() = route(teardown = false, systemBack = true, editExisting = false)

    /** Teardown dismisses manager and never changes folder membership. */
    @Test fun teardownDismissesManagerAndNeverChangesFolderMembership() = route(teardown = true)

    /** No fake folder route/model is supplied: actual native preference/controller/screen owners handle the click. */
    @Suppress("LongMethod")
    private fun route(
        teardown: Boolean,
        systemBack: Boolean = false,
        editExisting: Boolean? = null,
    ) {
        val context = ApplicationProvider.getApplicationContext<Context>()
        context
            .getSharedPreferences("whitenoise.chat_folders", Context.MODE_PRIVATE)
            .edit()
            .clear()
            .commit()
        val app =
            WhiteNoiseAppState(
                context,
                DraftStore.forContext(context),
                { null },
                listOf(AccountSummaryFfi("a", "aa".repeat(32), true, false, false, true)),
                "a",
                profileReader = { null },
                profileRefreshRequest = {},
            )
        val controller = ChatsController(app)
        app.attachChatsController(controller)
        val folder = checkNotNull(app.chatFolderPreferences.createFolder("a", "Work"))
        var selected by mutableStateOf<String?>(folder.id)
        try {
            composeRule.setContent {
                WhiteNoiseTheme {
                    ChatsScreen(
                        app,
                        controller,
                        {},
                        { _, _, _, _ -> },
                        selectedFolderId = selected,
                        onSelectFolder = { selected = it },
                        diagnosticsPrompt = { Text("Pending diagnostics consent") },
                    )
                }
            }
            composeRule.onNodeWithTag("chats.folders").performScrollToIndex(1)
            composeRule.onNodeWithTag("chats.manageFolders").performClick()
            composeRule.onNodeWithTag(CHAT_FOLDERS_CONTENT_TAG).assertExists()
            composeRule.onNodeWithText("Pending diagnostics consent").assertDoesNotExist()
            if (editExisting != null) {
                if (editExisting) {
                    composeRule.onNodeWithTag("folder.row.${folder.id}").performClick()
                } else {
                    composeRule
                        .onNodeWithContentDescription(context.getString(R.string.folder_new_title))
                        .performClick()
                }
                composeRule.onNodeWithTag("folder.name").performTextReplacement("Unsaved name")
                composeRule.runOnIdle { composeRule.activity.onBackPressedDispatcher.onBackPressed() }
                composeRule.onNodeWithText(context.getString(R.string.folder_discard_title)).assertExists()
                composeRule.onNodeWithText(context.getString(R.string.folder_discard)).performClick()
                composeRule.onNodeWithTag(CHAT_FOLDERS_CONTENT_TAG).assertExists()
                assertEquals(
                    "Work",
                    app.chatFolderPreferences
                        .foldersFor("a")
                        .first { it.id == folder.id }
                        .name,
                )
                assertFalse(app.chatFolderPreferences.foldersFor("a").any { it.name == "Unsaved name" })
            }
            if (teardown) {
                composeRule.runOnIdle { app.wipeInProgress = true }
            } else if (systemBack) {
                composeRule.runOnIdle { composeRule.activity.onBackPressedDispatcher.onBackPressed() }
            } else {
                composeRule.onNodeWithContentDescription(context.getString(R.string.back)).performClick()
            }
            composeRule.onNodeWithTag(CHAT_FOLDERS_CONTENT_TAG).assertDoesNotExist()
            if (!teardown) {
                assertFalse(composeRule.activity.isFinishing)
                composeRule.onNodeWithTag("chats.folders").assertExists()
            }
            assertEquals(folder.id, selected)
            assertEquals(emptySet<String>(), app.chatFolderPreferences.membershipFor("a", folder.id))
        } finally {
            controller.onCleared()
            app.mutationsScope.cancel()
        }
    }
}
