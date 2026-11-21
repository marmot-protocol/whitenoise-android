package dev.ipf.whitenoise.android.ui.navigation

import androidx.activity.ComponentActivity
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.unit.dp
import androidx.test.espresso.Espresso
import dev.ipf.whitenoise.android.ui.settings.DIAGNOSTICS_CONTENT_TAG
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Back with two panes acts on the pane that owns it. The section in the list pane sits below the conversation on the
 * route stack, so popping the top would close the conversation: system Back from Diagnostics beside a conversation
 * must instead return to the Settings screen it came from and leave the conversation open, as on master.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], qualifiers = "en-w1280dp-h800dp-mdpi")
class ShellPaneBackContractTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    /** Asserts every route audit this test drove found a well-formed stack and a matching saved section. */
    @get:Rule
    val routeAudit = ShellRouteAuditRule()

    private val shell = MountedContractShell(composeRule)

    /** Diagnostics beside a conversation goes Back to Developer, then Help & support, and the chat stays open. */
    @Test
    fun backFromDiagnosticsBesideAConversationKeepsTheConversationOpen() {
        shell.withMountedShell(mutableStateOf(EXPANDED_WIDTH)) { fixture, holder ->
            shell.openTarget(fixture, holder)
            composeRule.runOnIdle { holder.restoreSection(MainSection.Diagnostics, SettingsDetail.Developer) }
            shell.awaitCondition { shown(DIAGNOSTICS_CONTENT_TAG) }
            assertEquals(fixture.target.groupIdHex, holder.selectedChat.value?.id)

            Espresso.pressBack()
            shell.settle()
            assertFalse("Back leaves Diagnostics", shown(DIAGNOSTICS_CONTENT_TAG))
            assertEquals(MainSection.Settings.name, holder.sectionState.sectionName)
            assertEquals(SettingsDetail.Developer.name, holder.sectionState.settingsDetailName)
            val open = holder.selectedChat.value?.id
            assertEquals("the conversation beside it stays open", fixture.target.groupIdHex, open)

            Espresso.pressBack()
            shell.settle()
            assertEquals(SettingsDetail.HelpSupport.name, holder.sectionState.settingsDetailName)
            assertEquals(fixture.target.groupIdHex, holder.selectedChat.value?.id)
            assertTrue(shown(TWO_PANE_LIST_TAG))
        }
    }

    /** True while a node carrying [tag] is composed. */
    private fun shown(tag: String): Boolean = composeRule.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty()

    private companion object {
        val EXPANDED_WIDTH = 840.dp
    }
}
