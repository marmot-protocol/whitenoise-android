package dev.ipf.whitenoise.android.ui.navigation

import android.content.Context
import androidx.activity.ComponentActivity
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.unit.dp
import androidx.lifecycle.SavedStateHandle
import androidx.test.core.app.ApplicationProvider
import androidx.test.espresso.Espresso
import dev.ipf.whitenoise.android.R
import dev.ipf.whitenoise.android.state.ConversationTimelineTestIds
import dev.ipf.whitenoise.android.state.DestructiveAccountWipeRuntimeState
import dev.ipf.whitenoise.android.state.ScriptedConversationTimelineSubscription
import dev.ipf.whitenoise.android.state.WhiteNoiseAppState
import dev.ipf.whitenoise.android.state.timelinePage
import dev.ipf.whitenoise.android.state.timelineRecord
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Restore rules of the production shell (contract items 7 and 8): what a holder restored from saved state after
 * process death reopens, and what a same-process runtime replacement retires.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], qualifiers = "en-w1280dp-h800dp-mdpi")
class ShellRestoreContractTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    /** Asserts every route audit this test drove found a well-formed stack and a matching saved section. */
    @get:Rule
    val routeAudit = ShellRouteAuditRule()

    private val shell = MountedContractShell(composeRule)

    /**
     * After process death the saved Settings screen comes back at once and the saved conversation reopens over it once
     * the account's chat list is loaded. Back closes the conversation onto that Settings screen, not onto Chats.
     */
    @Test
    fun processDeathRestoresTheSettingsScreenAndTheConversationAboveIt() {
        val message =
            timelineRecord("ab".repeat(32), 1uL)
                .copy(groupIdHex = ConversationTimelineTestIds.GROUP_ID, plaintext = MESSAGE)
        val scripts = List(OPEN_SCRIPTS) { ScriptedConversationTimelineSubscription(timelinePage(message)) }
        val fixture = ReturnFixture(timelineScripts = scripts)
        val saved = savedRoute(fixture.target.groupIdHex)
        shell.withMountedShell(mutableStateOf(COMPACT_WIDTH), fixture, { MainShell(fixture.appState, it) }, saved) {
            _,
            holder,
            ->
            shell.awaitCondition { holder.selectedChat.value?.id == fixture.target.groupIdHex }
            shell.settle()
            assertEquals(MainSection.Settings.name, holder.sectionState.sectionName)
            assertEquals(SettingsDetail.Appearance.name, holder.sectionState.settingsDetailName)
            assertEquals(ConversationOpenContext(), holder.selectedChatOpenContext.value)
            shell.awaitCondition { reads(MESSAGE) }

            Espresso.pressBack()
            shell.awaitCondition { holder.selectedChat.value == null }
            shell.settle()
            assertEquals(MainSection.Settings.name, holder.sectionState.sectionName)
            assertEquals(SettingsDetail.Appearance.name, holder.sectionState.settingsDetailName)
            assertTrue("Back shows the restored Appearance screen", reads(appearance()))
            assertFalse("the conversation has left the screen", reads(MESSAGE))
            assertFalse("Back does not land on the chat list", reads("Return chat 0"))
            assertFalse(composeRule.activity.isFinishing)
        }
    }

    /** A same-process runtime replacement for the same account closes the open conversation and leaves Settings. */
    @Test
    fun aRuntimeReplacementRetiresTheRouteForTheSameAccount() {
        shell.withMountedShell(mutableStateOf(TWO_PANE_WIDTH)) { fixture, holder ->
            shell.openTarget(fixture, holder)
            composeRule.runOnIdle { holder.restoreSection(MainSection.Settings, SettingsDetail.Appearance) }
            shell.settle()
            assertEquals(fixture.target.groupIdHex, holder.selectedChat.value?.id)
            assertEquals(SettingsDetail.Appearance.name, holder.sectionState.settingsDetailName)

            val replacedRuntime = composeRule.runOnIdle { fixture.appState.replaceRuntime() }
            shell.settle()
            assertEquals(replacedRuntime, fixture.appState.runtimeGeneration)
            shell.awaitCondition { holder.selectedChat.value == null }
            assertEquals(MainSection.Chats.name, holder.sectionState.sectionName)
            assertNull(holder.sectionState.settingsDetailName)
            assertEquals(ConversationTimelineTestIds.ACCOUNT_REF, fixture.appState.activeAccountRef)
            assertTrue(composeRule.onAllNodes(hasText("Return chat 0")).fetchSemanticsNodes().isNotEmpty())
        }
    }

    /** True while any composed node, merged or not, carries [text]. */
    private fun reads(text: String): Boolean {
        val carriers = composeRule.onAllNodes(hasText(text, substring = true), useUnmergedTree = true)
        return carriers.fetchSemanticsNodes().isNotEmpty()
    }

    /** The Appearance screen's title. */
    private fun appearance(): String = ApplicationProvider.getApplicationContext<Context>().getString(R.string.appearance)

    /** The saved state a process-killed Activity hands back: the open conversation and Settings on Appearance. */
    private fun savedRoute(groupIdHex: String) =
        SavedStateHandle(
            mapOf(
                "main_shell_selected_account_ref" to ConversationTimelineTestIds.ACCOUNT_REF,
                "main_shell_selected_group_id" to groupIdHex,
                "main_shell_section_account" to ConversationTimelineTestIds.ACCOUNT_REF,
                "main_shell_section_runtime" to 1,
                "main_shell_section_process" to "a process that died",
                "main_shell_section_name" to MainSection.Settings.name,
                "main_shell_section_detail" to SettingsDetail.Appearance.name,
            ),
        )

    /** Publishes a new runtime generation for the same active account, as recovery does, and returns it. */
    private fun WhiteNoiseAppState.replaceRuntime(): Int {
        val state =
            DestructiveAccountWipeRuntimeState(
                activeAccountRef = activeAccountRef,
                activeConversationAccountRef = null,
                activeConversationGroupIdHex = null,
                runtimeGeneration = runtimeGeneration + 1,
            )
        WhiteNoiseAppState::class.java
            .getDeclaredMethod("applyDestructiveWipeRuntimeState", DestructiveAccountWipeRuntimeState::class.java)
            .apply { isAccessible = true }
            .invoke(this, state)
        return state.runtimeGeneration
    }

    private companion object {
        val COMPACT_WIDTH = 839.dp
        val TWO_PANE_WIDTH = 1_000.dp
        const val MESSAGE = "restore contract only message"
        const val OPEN_SCRIPTS = 3
    }
}
