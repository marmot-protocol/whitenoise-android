package dev.ipf.whitenoise.android.ui.navigation

import androidx.activity.ComponentActivity
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.unit.dp
import androidx.test.espresso.Espresso
import dev.ipf.whitenoise.android.state.ConversationTimelineTestIds
import dev.ipf.whitenoise.android.state.WhiteNoiseAppState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * How long the chat list's chosen folder lasts. The choice belongs to the shell, not to the list, so it outlives
 * the list being replaced by a conversation, and it ends at an account boundary.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], qualifiers = "en-w1280dp-h800dp-mdpi")
class ShellFolderFilterContractTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    /** Asserts the shadow navigator agreed with the hand-written route on every comparison this test drove. */
    @get:Rule
    val shadow = ShellShadowRule()

    private val shell = MountedContractShell(composeRule)

    /** The chosen folder is still chosen after a conversation opened from it is closed with Back. */
    @Test
    fun theChosenFolderSurvivesOpeningAndClosingAConversation() {
        val fixture = ReturnFixture()
        val folder = folderHoldingTheTarget(fixture)
        shell.withMountedShell(mutableStateOf(COMPACT_WIDTH), fixture) { _, holder ->
            chooseFolder(folder)
            assertEquals(true, chosen(folderChip(folder)))

            shell.openTarget(fixture, holder)
            assertTrue("the list, and its folder chips, are replaced by the conversation", chosen(ALL_CHIP) == null)

            Espresso.pressBack()
            shell.settle()
            assertNull(holder.selectedChat.value)
            assertEquals("the folder is still the chosen one", true, chosen(folderChip(folder)))
            assertEquals(false, chosen(ALL_CHIP))
        }
    }

    /** Another account never inherits the chosen folder, and returning to the first account finds it cleared. */
    @Test
    fun anAccountSwitchClearsTheChosenFolder() {
        val fixture = ReturnFixture()
        val folder = folderHoldingTheTarget(fixture)
        shell.withMountedShell(mutableStateOf(COMPACT_WIDTH), fixture) { _, _ ->
            chooseFolder(folder)
            assertEquals(true, chosen(folderChip(folder)))

            composeRule.runOnIdle { fixture.appState.replaceActiveAccount(OTHER_ACCOUNT_REF) }
            shell.settle()
            composeRule.runOnIdle { fixture.appState.replaceActiveAccount(ConversationTimelineTestIds.ACCOUNT_REF) }
            shell.awaitCondition { chosen(folderChip(folder)) != null }

            assertEquals("the first account's folder is no longer chosen", false, chosen(folderChip(folder)))
            assertEquals(true, chosen(ALL_CHIP))
        }
    }

    /** Creates a folder for the fixture's account that holds its target chat, and returns the folder's id. */
    private fun folderHoldingTheTarget(fixture: ReturnFixture): String {
        val folders = fixture.appState.chatFolderPreferences
        val folder = checkNotNull(folders.createFolder(ConversationTimelineTestIds.ACCOUNT_REF, "Contract folder"))
        val added =
            folders.setChatInFolder(
                ConversationTimelineTestIds.ACCOUNT_REF,
                folder.id,
                fixture.target.groupIdHex,
                included = true,
            )
        check(added) { "the target chat was not added to the folder" }
        return folder.id
    }

    /** Taps the chip of folder [id] in the list's chip row. */
    private fun chooseFolder(id: String) {
        composeRule.onNodeWithTag(CHIP_ROW_TAG).performScrollToNode(hasTestTag(folderChip(id)))
        composeRule.onNodeWithTag(folderChip(id)).performClick()
        shell.settle()
    }

    /** Whether the chip tagged [tag] reports itself selected, or null while no such chip is composed. */
    private fun chosen(tag: String): Boolean? {
        val chips = composeRule.onAllNodesWithTag(tag).fetchSemanticsNodes()
        return chips.singleOrNull()?.config?.getOrNull(SemanticsProperties.Selected)
    }

    /** The tag of folder [id]'s chip. */
    private fun folderChip(id: String) = "chats.folder.$id"

    /** Writes the active account directly, as the app state does once a switch has been decided. */
    private fun WhiteNoiseAppState.replaceActiveAccount(accountRef: String?) {
        WhiteNoiseAppState::class.java
            .getDeclaredMethod("setActiveAccountRef", String::class.java)
            .apply { isAccessible = true }
            .invoke(this, accountRef)
    }

    private companion object {
        val COMPACT_WIDTH = 839.dp
        const val CHIP_ROW_TAG = "chats.folders"
        const val ALL_CHIP = "chats.scope.chats"
        const val OTHER_ACCOUNT_REF = "other-contract-account"
    }
}
