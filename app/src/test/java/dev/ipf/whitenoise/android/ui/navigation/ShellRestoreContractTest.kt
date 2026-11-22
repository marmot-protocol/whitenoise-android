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
        val fixture = messageFixture()
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

    /**
     * Process death in a new process, with the same account active again: the saved Settings screen comes back and the
     * saved conversation reopens above it, on screen.
     */
    @Test
    fun processDeathInANewProcessRestoresTheSameAccountsRoute() {
        shell.withRestartableShell(mutableStateOf(COMPACT_WIDTH), messageFixture()) { fixture, holder, restart ->
            openSettingsAndTheConversation(fixture, holder)

            val restored = composeRule.runOnIdle { restart() }
            shell.awaitCondition { restored.selectedChat.value?.id == fixture.target.groupIdHex }
            shell.awaitCondition { reads(MESSAGE) }
            Espresso.pressBack()
            shell.awaitCondition { restored.selectedChat.value == null }
            shell.settle()
            assertTrue("the same account's Settings screen came back", reads(appearance()))
        }
    }

    /**
     * Process death in a new process while another account becomes active: nothing of the previous account's saved
     * route comes back, neither its Settings screen nor its conversation.
     */
    @Test
    fun processDeathRestoresNothingOfAnotherAccountsRoute() {
        shell.withRestartableShell(mutableStateOf(COMPACT_WIDTH), messageFixture()) { fixture, holder, restart ->
            openSettingsAndTheConversation(fixture, holder)

            val restored =
                composeRule.runOnIdle {
                    fixture.appState.setActiveAccountForContract(OTHER_ACCOUNT_REF)
                    restart()
                }
            shell.awaitCondition { reads("Return chat 0") }
            shell.settle()

            assertEquals(CHATS_ROOT, restored.shellNavigator.stack)
            assertFalse("the previous account's Settings screen must not come back", reads(appearance()))
            assertFalse("the previous account's conversation must not reopen", reads(MESSAGE))
            assertEquals(MainSection.Chats.name, restored.sectionState.sectionName)
        }
    }

    /** Opens Settings on Appearance and then the target conversation over it, both checked first. */
    private fun openSettingsAndTheConversation(
        fixture: ReturnFixture,
        holder: MainShellStateHolder,
    ) {
        composeRule.runOnIdle { holder.restoreSection(MainSection.Settings, SettingsDetail.Appearance) }
        shell.awaitCondition { reads(appearance()) }
        composeRule.runOnIdle {
            val runtime = fixture.appState.runtimeGeneration
            val controller = holder.chatsController(ConversationTimelineTestIds.ACCOUNT_REF, runtime)
            holder.openForTest(controller.items.first { it.id == fixture.target.groupIdHex })
        }
        shell.awaitCondition { reads(MESSAGE) }
        shell.settle()
        assertEquals(SettingsDetail.Appearance.name, holder.sectionState.settingsDetailName)
        assertEquals(fixture.target.groupIdHex, holder.selectedChat.value?.id)
    }

    /** A conversation whose one message tells it apart, with one ready script per open. */
    private fun messageFixture(): ReturnFixture {
        val message =
            timelineRecord("ab".repeat(32), 1uL)
                .copy(groupIdHex = ConversationTimelineTestIds.GROUP_ID, plaintext = MESSAGE)
        return ReturnFixture(
            timelineScripts = List(OPEN_SCRIPTS) { ScriptedConversationTimelineSubscription(timelinePage(message)) },
        )
    }

    /** Makes [accountRef] the active account through the same private setter as the other account contracts. */
    private fun WhiteNoiseAppState.setActiveAccountForContract(accountRef: String) {
        WhiteNoiseAppState::class.java
            .getDeclaredMethod("setActiveAccountRef", String::class.java)
            .apply { isAccessible = true }
            .invoke(this, accountRef)
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
    private fun appearance(): String {
        val context = ApplicationProvider.getApplicationContext<Context>()
        return context.getString(R.string.appearance)
    }

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
        const val OTHER_ACCOUNT_REF = "other-restore-account"
        const val OPEN_SCRIPTS = 3
    }
}
