package dev.ipf.whitenoise.android.ui.navigation

import androidx.activity.ComponentActivity
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import androidx.test.espresso.Espresso
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Where system Back leads from each level of the production shell. One press leaves exactly one level, in the
 * order a reader went in, and the last press from a route lands on the chat list. Activity recreation keeps the
 * ladder as it was.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], qualifiers = "en-w1280dp-h800dp-mdpi")
class ShellBackLadderContractTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    /** Asserts the shadow navigator agreed with the hand-written route on every comparison this test drove. */
    @get:Rule
    val shadow = ShellShadowRule()

    private val shell = MountedContractShell(composeRule)

    /** Back from an open conversation closes it and leaves no open context behind. */
    @Test
    fun backFromAConversationReturnsToTheChatList() {
        shell.withMountedShell(mutableStateOf(COMPACT_WIDTH)) { fixture, holder ->
            shell.openTarget(fixture, holder)

            pressBack()
            shell.awaitCondition { holder.selectedChat.value == null }

            assertEquals(ConversationOpenContext(), holder.selectedChatOpenContext.value)
            assertEquals(MainSection.Chats.name, holder.sectionState.sectionName)
        }
    }

    /** Back from chat details returns to the same conversation, and only the next press closes it. */
    @Test
    fun backFromChatDetailsReturnsToItsConversationFirst() {
        shell.withMountedShell(mutableStateOf(COMPACT_WIDTH)) { fixture, holder ->
            shell.openTarget(fixture, holder)
            composeRule.onNode(conversationHeader()).performClick()
            shell.awaitCondition { detailsShown() }

            pressBack()
            shell.awaitCondition { !detailsShown() }
            assertEquals(fixture.target.groupIdHex, holder.selectedChat.value?.id)

            pressBack()
            shell.awaitCondition { holder.selectedChat.value == null }
            assertFalse(detailsShown())
        }
    }

    /** Back from a Settings screen returns to the Settings list, and the next press returns to Chats. */
    @Test
    fun backFromASettingsScreenReturnsToSettingsThenChats() {
        shell.withMountedShell(mutableStateOf(COMPACT_WIDTH)) { _, holder ->
            composeRule.runOnIdle {
                holder.restoreSection(MainSection.Settings, SettingsDetail.Appearance)
            }
            shell.settle()

            pressBack()
            shell.awaitCondition { holder.sectionState.settingsDetailName == null }
            assertEquals(MainSection.Settings.name, holder.sectionState.sectionName)

            pressBack()
            shell.awaitCondition { holder.sectionState.sectionName == MainSection.Chats.name }
            assertNull(holder.sectionState.settingsDetailName)
            assertFalse("Back to Chats must not leave the app", composeRule.activity.isFinishing)
        }
    }

    /**
     * Recreating the Activity keeps chat details open over the same conversation controller, and Back still
     * returns to that conversation first.
     */
    @Test
    fun recreationKeepsChatDetailsOpenOnTheSameController() {
        shell.withMountedShell(mutableStateOf(COMPACT_WIDTH)) { fixture, holder ->
            shell.openTarget(fixture, holder)
            composeRule.onNode(conversationHeader()).performClick()
            shell.awaitCondition { detailsShown() }
            val opened = fixture.appState.attachedConversationControllers().single()

            shell.recreate()

            assertTrue("chat details must survive recreation", detailsShown())
            assertSame(opened, fixture.appState.attachedConversationControllers().single())
            assertEquals(fixture.target.groupIdHex, holder.selectedChat.value?.id)

            pressBack()
            shell.awaitCondition { !detailsShown() }
            assertEquals(fixture.target.groupIdHex, holder.selectedChat.value?.id)
        }
    }

    /** Delivers Back as the system does, to whichever window has focus, and lets the route settle. */
    private fun pressBack() {
        Espresso.pressBack()
        shell.settle()
    }

    /** True while the chat details list is composed. */
    private fun detailsShown(): Boolean = composeRule.onAllNodesWithTag(DETAILS_TAG).fetchSemanticsNodes().isNotEmpty()

    /** The conversation header, the one clickable heading on the conversation, which opens its details. */
    private fun conversationHeader() = hasClickAction() and SemanticsMatcher.keyIsDefined(SemanticsProperties.Heading)

    private companion object {
        val COMPACT_WIDTH = 839.dp
        const val DETAILS_TAG = "chat_info.list"
    }
}
