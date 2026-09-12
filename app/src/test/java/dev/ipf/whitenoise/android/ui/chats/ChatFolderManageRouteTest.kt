package dev.ipf.whitenoise.android.ui.chats

import android.content.Context
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToIndex
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
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Actual Chats manage action opens the existing native manager and preserves shell-owned folder state on return. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], qualifiers = "en-w360dp-h780dp-mdpi")
class ChatFolderManageRouteTest {
    @get:Rule val composeRule = createComposeRule()

    @Test fun managerWorksWithNoChatsAndReturnPreservesSelectedNativeFolder() = route(teardown = false)

    @Test fun teardownDismissesManagerAndNeverChangesFolderMembership() = route(teardown = true)

    /** No fake folder route/model is supplied: actual native preference/controller/screen owners handle the click. */
    @Suppress("LongMethod")
    private fun route(teardown: Boolean) {
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
            composeRule.onNodeWithTag("chats.folders").performScrollToIndex(2)
            composeRule.onNodeWithTag("chats.manageFolders").performClick()
            composeRule.onNodeWithTag(CHAT_FOLDERS_CONTENT_TAG).assertExists()
            composeRule.onNodeWithText("Pending diagnostics consent").assertDoesNotExist()
            if (teardown) {
                composeRule.runOnIdle { app.wipeInProgress = true }
            } else {
                composeRule.onNodeWithContentDescription(context.getString(R.string.back)).performClick()
            }
            composeRule.onNodeWithTag(CHAT_FOLDERS_CONTENT_TAG).assertDoesNotExist()
            assertEquals(folder.id, selected)
            assertEquals(emptySet<String>(), app.chatFolderPreferences.membershipFor("a", folder.id))
        } finally {
            controller.onCleared()
            app.mutationsScope.cancel()
        }
    }
}
