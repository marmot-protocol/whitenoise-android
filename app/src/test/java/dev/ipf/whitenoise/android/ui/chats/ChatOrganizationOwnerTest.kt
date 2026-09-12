package dev.ipf.whitenoise.android.ui.chats

import android.content.Context
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import dev.ipf.marmotkit.AccountSummaryFfi
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

/** Real picker callbacks exercise local pending intent, persisted state and same-frame callback ownership. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], qualifiers = "en-w360dp-h780dp-mdpi")
class ChatOrganizationOwnerTest {
    @get:Rule val composeRule = createComposeRule()
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private lateinit var app: WhiteNoiseAppState
    private var dismissed = 0

    @Before fun setup() {
        context
            .getSharedPreferences("whitenoise.chat_folders", Context.MODE_PRIVATE)
            .edit()
            .clear()
            .commit()
        app = chatOrganizationAppState(context)
    }

    @Test fun selectingMixedFolderDoesNotWriteUntilSave() {
        val folder = app.chatFolderPreferences.createFolder("alice", "Work")!!
        app.chatFolderPreferences.setChatInFolder("alice", folder.id, "g1", true)
        show()
        composeRule.onNodeWithTag("chat.folderSave").assertIsNotEnabled()
        composeRule.onNodeWithTag("chat.folderChoice.${folder.id}").performClick().assertIsOn()
        assertEquals(setOf("g1"), app.chatFolderPreferences.membershipFor("alice", folder.id))
        composeRule.onNodeWithTag("chat.folderSave").performClick()
        assertEquals(setOf("g1", "g2"), app.chatFolderPreferences.membershipFor("alice", folder.id))
        assertEquals(1, dismissed)
    }

    @Test fun actualSavedStateRestorationKeepsPendingIntentWithoutWriting() {
        val folder = app.chatFolderPreferences.createFolder("alice", "Work")!!
        val restoration = StateRestorationTester(composeRule)
        restoration.setContent {
            WhiteNoiseTheme { ChatFolderPickerSheet(app, listOf("g1", "g2"), {}, { dismissed++ }) }
        }
        composeRule.onNodeWithTag("chat.folderChoice.${folder.id}").performClick()
        restoration.emulateSavedInstanceStateRestore()
        composeRule.onNodeWithTag("chat.folderChoice.${folder.id}").assertIsOn()
        assertTrue(app.chatFolderPreferences.membershipFor("alice", folder.id).isEmpty())
        composeRule.onNodeWithTag("chat.folderSave").performClick()
        assertEquals(setOf("g1", "g2"), app.chatFolderPreferences.membershipFor("alice", folder.id))
    }

    @Test fun capturedSaveAfterCancelInSameFrameDoesNotWrite() {
        val folder = app.chatFolderPreferences.createFolder("alice", "Work")!!
        show()
        composeRule.onNodeWithTag("chat.folderChoice.${folder.id}").performClick()
        val save = click("chat.folderSave")
        val cancel = click("chat.folderCancel")
        composeRule.runOnIdle {
            cancel()
            save()
        }
        assertEquals(1, dismissed)
        assertTrue(app.chatFolderPreferences.membershipFor("alice", folder.id).isEmpty())
    }

    @Test fun capturedSaveAfterAccountSwitchCannotWriteEitherAccount() {
        val folder = app.chatFolderPreferences.createFolder("alice", "Work")!!
        show()
        composeRule.onNodeWithTag("chat.folderChoice.${folder.id}").performClick()
        val save = click("chat.folderSave")
        composeRule.runOnIdle {
            replaceChatOrganizationAccount(app, "bob")
            save()
        }
        assertTrue(app.chatFolderPreferences.membershipFor("alice", folder.id).isEmpty())
        assertTrue(app.chatFolderPreferences.membershipFor("bob", folder.id).isEmpty())
    }

    @Test fun nativeNewFolderHandoffCancelsUncommittedPickerChoices() {
        val folder = app.chatFolderPreferences.createFolder("alice", "Work")!!
        var creates = 0
        composeRule.setContent {
            WhiteNoiseTheme { ChatFolderPickerSheet(app, listOf("g1", "g2"), { creates++ }, {}) }
        }
        composeRule.onNodeWithTag("chat.folderChoice.${folder.id}").performClick()
        val save = click("chat.folderSave")
        composeRule.onNodeWithTag("chat.folderCreate").performClick()
        composeRule.runOnIdle { save() }
        assertEquals(1, creates)
        assertTrue(app.chatFolderPreferences.membershipFor("alice", folder.id).isEmpty())
    }

    private fun show() {
        composeRule.setContent {
            WhiteNoiseTheme { ChatFolderPickerSheet(app, listOf("g1", "g2"), {}, { dismissed++ }) }
        }
    }

    private fun click(tag: String) =
        composeRule
            .onNodeWithTag(tag)
            .fetchSemanticsNode()
            .config[SemanticsActions.OnClick]
            .action!!
}

/** Local account metadata fixture; no native identity, publication or user data is created. */
internal fun chatOrganizationAppState(context: Context): WhiteNoiseAppState =
    WhiteNoiseAppState(
        context = context,
        draftStore = DraftStore.forContext(context),
        accountIdHexResolver = { null },
        accounts =
            listOf(
                AccountSummaryFfi(
                    label = "alice",
                    accountIdHex = "a".repeat(64),
                    localSigning = true,
                    externalSigning = false,
                    signedOut = false,
                    running = true,
                ),
            ),
        activeAccountRef = "alice",
    )

/**
 * Mirrors the actual Compose-observable account replacement without invoking unrelated native account setup
 * in UI tests.
 */
internal fun replaceChatOrganizationAccount(
    app: WhiteNoiseAppState,
    account: String,
) {
    WhiteNoiseAppState::class.java
        .getDeclaredMethod("setActiveAccountRef", String::class.java)
        .apply { isAccessible = true }
        .invoke(app, account)
}
