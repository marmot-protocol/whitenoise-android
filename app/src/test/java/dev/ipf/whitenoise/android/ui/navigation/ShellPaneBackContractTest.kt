package dev.ipf.whitenoise.android.ui.navigation

import android.content.Context
import androidx.activity.ComponentActivity
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import androidx.test.espresso.Espresso
import dev.ipf.whitenoise.android.R
import dev.ipf.whitenoise.android.state.ConversationTimelineTestIds
import dev.ipf.whitenoise.android.state.ScriptedConversationTimelineSubscription
import dev.ipf.whitenoise.android.state.timelinePage
import dev.ipf.whitenoise.android.state.timelineRecord
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
        shell.withMountedShell(mutableStateOf(EXPANDED_WIDTH), messageFixture()) { fixture, holder ->
            shell.openTarget(fixture, holder)
            shell.awaitCondition { reads(MESSAGE) }
            composeRule.runOnIdle { holder.restoreSection(MainSection.Diagnostics, SettingsDetail.Developer) }
            shell.awaitCondition { shown(DIAGNOSTICS_CONTENT_TAG) }
            assertEquals(fixture.target.groupIdHex, holder.selectedChat.value?.id)
            assertFalse(reads(string(R.string.developer_mode)))

            Espresso.pressBack()
            shell.settle()
            assertFalse("Back leaves Diagnostics", shown(DIAGNOSTICS_CONTENT_TAG))
            assertEquals(MainSection.Settings.name, holder.sectionState.sectionName)
            assertEquals(SettingsDetail.Developer.name, holder.sectionState.settingsDetailName)
            val open = holder.selectedChat.value?.id
            assertEquals("the conversation beside it stays open", fixture.target.groupIdHex, open)
            assertTrue("the list pane shows Developer", reads(string(R.string.developer_mode)))
            assertTrue("the conversation beside it is still on screen", reads(MESSAGE))

            Espresso.pressBack()
            shell.settle()
            assertEquals(SettingsDetail.HelpSupport.name, holder.sectionState.settingsDetailName)
            assertEquals(fixture.target.groupIdHex, holder.selectedChat.value?.id)
            assertTrue(shown(TWO_PANE_LIST_TAG))
            assertTrue("the list pane shows Help & support", reads(string(R.string.settings_help_support)))
            assertFalse(reads(string(R.string.developer_mode)))
            assertTrue("the conversation beside it is still on screen", reads(MESSAGE))
        }
    }

    /** True while any composed node, merged or not, carries [text]. */
    private fun reads(text: String): Boolean {
        val carriers = composeRule.onAllNodes(hasText(text, substring = true), useUnmergedTree = true)
        return carriers.fetchSemanticsNodes().isNotEmpty()
    }

    /** The app's string for [id]. */
    private fun string(id: Int): String = ApplicationProvider.getApplicationContext<Context>().getString(id)

    /** A conversation whose one message tells it apart from the list. */
    private fun messageFixture(): ReturnFixture {
        val message =
            timelineRecord("ab".repeat(32), 1uL)
                .copy(groupIdHex = ConversationTimelineTestIds.GROUP_ID, plaintext = MESSAGE)
        return ReturnFixture(
            timelineScripts = List(OPEN_SCRIPTS) { ScriptedConversationTimelineSubscription(timelinePage(message)) },
        )
    }

    /** True while a node carrying [tag] is composed. */
    private fun shown(tag: String): Boolean = composeRule.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty()

    private companion object {
        val EXPANDED_WIDTH = 661.dp
        const val MESSAGE = "pane back contract only message"
        const val OPEN_SCRIPTS = 3
    }
}
