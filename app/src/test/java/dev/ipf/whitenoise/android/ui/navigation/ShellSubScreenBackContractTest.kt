package dev.ipf.whitenoise.android.ui.navigation

import androidx.activity.ComponentActivity
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.unit.dp
import androidx.test.espresso.Espresso
import dev.ipf.whitenoise.android.R
import dev.ipf.whitenoise.android.ui.group.GROUP_RELAYS_SCREEN_TAG
import dev.ipf.whitenoise.android.ui.settings.DIAGNOSTICS_CONTENT_TAG
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
 * Where Back leads from the screens one level below chat details and Settings: each press undoes one step, and
 * none of them skips a level or leaves the app.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], qualifiers = "en-w1280dp-h800dp-mdpi")
class ShellSubScreenBackContractTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    /** Asserts every route audit this test drove found a well-formed stack and a matching saved section. */
    @get:Rule
    val routeAudit = ShellRouteAuditRule()

    private val shell = MountedContractShell(composeRule)

    /** Back from the chat's relay screen returns to chat details, then the conversation, then the chat list. */
    @Test
    fun backFromChatRelaysReturnsToDetailsThenTheConversationThenChats() {
        shell.withMountedShell(mutableStateOf(COMPACT_WIDTH)) { fixture, holder ->
            shell.openTarget(fixture, holder)
            composeRule.onNode(conversationHeader()).performClick()
            shell.awaitCondition { shown(DETAILS_TAG) }
            composeRule.onNodeWithTag(RELAYS_ROW_TAG).performScrollTo().performClick()
            shell.awaitCondition { shown(GROUP_RELAYS_SCREEN_TAG) }
            assertFalse("the relay screen replaces the details list", shown(DETAILS_TAG))

            systemBack()
            assertTrue("the first Back returns to chat details", shown(DETAILS_TAG))
            assertFalse(shown(GROUP_RELAYS_SCREEN_TAG))
            assertEquals(fixture.target.groupIdHex, holder.selectedChat.value?.id)

            systemBack()
            assertFalse("the second Back returns to the conversation", shown(DETAILS_TAG))
            assertEquals(fixture.target.groupIdHex, holder.selectedChat.value?.id)

            systemBack()
            assertNull("the third Back returns to the chat list", holder.selectedChat.value)
            assertFalse(composeRule.activity.isFinishing)
        }
    }

    /**
     * Diagnostics remembers the Settings screen it was opened from: Back returns to that screen, and from there
     * Back climbs to its parent, Settings home and Chats.
     */
    @Test
    fun backFromDiagnosticsReturnsToTheSettingsScreenItWasOpenedFrom() {
        shell.withMountedShell(mutableStateOf(COMPACT_WIDTH)) { _, holder ->
            composeRule.runOnIdle {
                holder.restoreSection(MainSection.Diagnostics, SettingsDetail.Developer)
            }
            shell.awaitCondition { shown(DIAGNOSTICS_CONTENT_TAG) }

            systemBack()
            assertEquals(MainSection.Settings.name, holder.sectionState.sectionName)
            assertEquals(SettingsDetail.Developer.name, holder.sectionState.settingsDetailName)
            assertFalse(shown(DIAGNOSTICS_CONTENT_TAG))

            systemBack()
            assertEquals(SettingsDetail.HelpSupport.name, holder.sectionState.settingsDetailName)
            systemBack()
            assertNull(holder.sectionState.settingsDetailName)
            assertEquals(MainSection.Settings.name, holder.sectionState.sectionName)
            systemBack()
            assertEquals(MainSection.Chats.name, holder.sectionState.sectionName)
            assertFalse(composeRule.activity.isFinishing)
        }
    }

    /**
     * The profile opened from the photo on the Settings home header arrives in edit mode with the photo menu open.
     * System Back undoes that one step at a time: the menu closes, then edit mode, and only the third press leaves
     * the profile, for Settings home and not for the Account screen that is its parent everywhere else.
     */
    @Test
    fun backFromTheProfileOpenedFromTheHeaderPhotoReturnsToSettingsHome() {
        shell.withMountedShell(mutableStateOf(COMPACT_WIDTH)) { _, holder ->
            composeRule.runOnIdle { holder.restoreSection(MainSection.Settings) }
            shell.awaitCondition { shown(HEADER_PHOTO_TAG) }
            composeRule.onNodeWithTag(HEADER_PHOTO_TAG).performClick()
            shell.awaitCondition { holder.sectionState.settingsDetailName == SettingsDetail.Profile.name }
            shell.awaitCondition { labelled(R.string.profile_choose_photos) }
            assertEquals(ProfileControls(menu = true, save = true, edit = false), profileControls())

            systemBack()
            assertEquals("the first Back closes only the photo menu", EDITING, profileControls())
            assertEquals(SettingsDetail.Profile.name, holder.sectionState.settingsDetailName)

            systemBack()
            assertEquals("the second Back leaves edit mode", ProfileControls(false, false, true), profileControls())
            assertEquals(SettingsDetail.Profile.name, holder.sectionState.settingsDetailName)

            systemBack()
            assertNull("the third Back skips the Account screen", holder.sectionState.settingsDetailName)
            assertEquals(MainSection.Settings.name, holder.sectionState.sectionName)
            assertTrue(shown(HEADER_PHOTO_TAG))

            systemBack()
            assertEquals(MainSection.Chats.name, holder.sectionState.sectionName)
            assertFalse(composeRule.activity.isFinishing)
        }
    }

    /** Which of the profile's photo menu, Save and Edit controls are composed. */
    private fun profileControls() =
        ProfileControls(
            menu = labelled(R.string.profile_choose_photos),
            save = labelled(R.string.save),
            edit = labelled(R.string.edit),
        )

    /** True while a node reading exactly the string [resId] is composed in any window, popups included. */
    private fun labelled(resId: Int): Boolean {
        val label = composeRule.activity.getString(resId)
        return composeRule.onAllNodes(hasText(label)).fetchSemanticsNodes().isNotEmpty()
    }

    /** Delivers Back as the system does, to whichever window has focus, so an open popup takes it first. */
    private fun systemBack() {
        Espresso.pressBack()
        shell.settle()
    }

    /** True while a node carrying [tag] is composed. */
    private fun shown(tag: String): Boolean = composeRule.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty()

    /** The conversation header, the one clickable heading on the conversation, which opens its details. */
    private fun conversationHeader() = hasClickAction() and SemanticsMatcher.keyIsDefined(SemanticsProperties.Heading)

    /** The profile screen's controls that tell its photo menu, edit mode and read mode apart. */
    private data class ProfileControls(
        val menu: Boolean,
        val save: Boolean,
        val edit: Boolean,
    )

    private companion object {
        val EDITING = ProfileControls(menu = false, save = true, edit = false)
        val COMPACT_WIDTH = 660.dp
        const val DETAILS_TAG = "chat_info.list"
        const val RELAYS_ROW_TAG = "chat_info.relays"
        const val HEADER_PHOTO_TAG = "settings.active_profile_avatar"
    }
}
