package dev.ipf.whitenoise.android.ui.navigation

import androidx.activity.ComponentActivity
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import dev.ipf.whitenoise.android.R
import dev.ipf.whitenoise.android.state.ConversationTimelineTestIds
import dev.ipf.whitenoise.android.state.ScriptedConversationTimelineSubscription
import dev.ipf.whitenoise.android.state.timelinePage
import dev.ipf.whitenoise.android.state.timelineRecord
import dev.ipf.whitenoise.android.ui.group.GROUP_RELAYS_SCREEN_TAG
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
 * What the app shows again after it locks and unlocks. The lock is drawn by the production root in place of the
 * shell, so each test mounts that root, checks that nothing of the route is composed under the lock, and then
 * records which level of the route comes back and where Back leads from it.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], qualifiers = "en-w1280dp-h800dp-mdpi")
class ShellAppLockLifetimeContractTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    private val app = MountedContractApp(composeRule)

    /**
     * Under the lock nothing of the open conversation is composed and its controller is detached. Unlocking
     * shows the same conversation again on the same controller, and Back still closes it to the chat list.
     */
    @Test
    fun anOpenConversationIsNotComposedUnderTheLockAndReturnsOnTheSameController() {
        app.withMountedApp(messageFixture()) { fixture, holder ->
            app.openTarget(fixture, holder)
            app.awaitCondition { app.reads(CONVERSATION_MESSAGE) }
            composeRule.onNode(hasSetTextAction()).assertExists()
            val opened = fixture.appState.attachedConversationControllers().single()

            app.lock(fixture.appState)
            assertFalse("no message may be composed under the lock", app.reads(CONVERSATION_MESSAGE))
            composeRule.onNode(hasSetTextAction()).assertDoesNotExist()
            assertFalse(app.shown(TARGET_ROW_PREFIX + fixture.target.groupIdHex))
            assertEquals(emptyList<Any>(), fixture.appState.attachedConversationControllers())
            assertEquals("the lock keeps the selection", fixture.target.groupIdHex, holder.selectedChat.value?.id)

            app.unlock(fixture.appState)
            app.awaitCondition { app.reads(CONVERSATION_MESSAGE) }
            assertSame(opened, fixture.appState.attachedConversationControllers().single())
            assertEquals(fixture.target.groupIdHex, holder.selectedChat.value?.id)
            composeRule.onNode(hasSetTextAction()).assertExists()

            app.systemBack()
            assertNull(holder.selectedChat.value)
            assertTrue(app.shown(TARGET_ROW_PREFIX + fixture.target.groupIdHex))
            assertFalse(composeRule.activity.isFinishing)
        }
    }

    /**
     * Chat details are not composed under the lock and are open again after it, over the same conversation
     * controller. Back then returns to the conversation first and to the chat list second.
     */
    @Test
    fun chatDetailsReopenAfterTheLockOverTheSameController() {
        app.withMountedApp(messageFixture()) { fixture, holder ->
            app.openTarget(fixture, holder)
            app.awaitCondition { app.reads(CONVERSATION_MESSAGE) }
            val opened = fixture.appState.attachedConversationControllers().single()
            composeRule.onNode(conversationHeader()).performClick()
            app.awaitCondition { app.shown(DETAILS_TAG) }

            app.lock(fixture.appState)
            assertFalse("details may not be composed under the lock", app.shown(DETAILS_TAG))

            app.unlock(fixture.appState)
            assertTrue("chat details must be open again after unlocking", app.shown(DETAILS_TAG))
            assertFalse("details still cover the transcript", app.reads(CONVERSATION_MESSAGE))
            assertSame(opened, fixture.appState.attachedConversationControllers().single())

            app.systemBack()
            assertFalse("the first Back returns to the conversation", app.shown(DETAILS_TAG))
            assertTrue(app.reads(CONVERSATION_MESSAGE))
            assertEquals(fixture.target.groupIdHex, holder.selectedChat.value?.id)

            app.systemBack()
            assertNull("the second Back returns to the chat list", holder.selectedChat.value)
            assertFalse(composeRule.activity.isFinishing)
        }
    }

    /**
     * A screen opened from chat details does not come back after the lock: unlocking shows chat details, one
     * level up, over the same controller. Back from there returns to the conversation and then the chat list.
     */
    @Test
    fun theChatRelayScreenIsDroppedByTheLockLeavingChatDetailsOpen() {
        app.withMountedApp(messageFixture()) { fixture, holder ->
            app.openTarget(fixture, holder)
            app.awaitCondition { app.reads(CONVERSATION_MESSAGE) }
            val opened = fixture.appState.attachedConversationControllers().single()
            composeRule.onNode(conversationHeader()).performClick()
            app.awaitCondition { app.shown(DETAILS_TAG) }
            composeRule.onNodeWithTag(RELAYS_ROW_TAG).performScrollTo().performClick()
            app.awaitCondition { app.shown(GROUP_RELAYS_SCREEN_TAG) }
            assertFalse("the relay screen replaces the details list", app.shown(DETAILS_TAG))

            app.lock(fixture.appState)
            assertFalse(app.shown(GROUP_RELAYS_SCREEN_TAG))
            assertFalse(app.shown(DETAILS_TAG))

            app.unlock(fixture.appState)
            assertFalse("the relay screen does not survive the lock", app.shown(GROUP_RELAYS_SCREEN_TAG))
            assertTrue("chat details, its parent, is what returns", app.shown(DETAILS_TAG))
            assertSame(opened, fixture.appState.attachedConversationControllers().single())

            app.systemBack()
            assertFalse(app.shown(DETAILS_TAG))
            assertTrue("the first Back returns to the conversation", app.reads(CONVERSATION_MESSAGE))
            assertEquals(fixture.target.groupIdHex, holder.selectedChat.value?.id)

            app.systemBack()
            assertNull("the second Back returns to the chat list", holder.selectedChat.value)
            assertFalse(composeRule.activity.isFinishing)
        }
    }

    /**
     * An open Settings screen is not composed under the lock and is open again after it. Back then returns to
     * Settings home and to Chats.
     */
    @Test
    fun anOpenSettingsScreenReopensAfterTheLock() {
        app.withMountedApp { fixture, holder ->
            composeRule.runOnIdle {
                holder.sectionState.sectionName = MainSection.Settings.name
                holder.sectionState.settingsDetailName = SettingsDetail.Appearance.name
            }
            app.awaitCondition { app.shown(APPEARANCE_TAG) }

            app.lock(fixture.appState)
            assertFalse("Settings may not be composed under the lock", app.shown(APPEARANCE_TAG))
            assertEquals(MainSection.Settings.name, holder.sectionState.sectionName)
            assertEquals(SettingsDetail.Appearance.name, holder.sectionState.settingsDetailName)

            app.unlock(fixture.appState)
            assertTrue("the Settings screen must be open again after unlocking", app.shown(APPEARANCE_TAG))
            assertEquals(MainSection.Settings.name, holder.sectionState.sectionName)
            assertEquals(SettingsDetail.Appearance.name, holder.sectionState.settingsDetailName)

            app.systemBack()
            assertNull("the first Back returns to Settings home", holder.sectionState.settingsDetailName)
            assertEquals(MainSection.Settings.name, holder.sectionState.sectionName)
            assertTrue(app.shown(HEADER_PHOTO_TAG))

            app.systemBack()
            assertEquals(MainSection.Chats.name, holder.sectionState.sectionName)
            assertFalse(composeRule.activity.isFinishing)
        }
    }

    /**
     * The profile opened from the Settings header photo locks while in edit mode with its photo menu open. After
     * the lock the menu is gone and edit mode is kept, so Back takes two steps and not three: it leaves edit mode,
     * then returns to Settings home, still skipping the Account screen.
     */
    @Test
    fun theHeaderPhotoProfileKeepsEditModeButNotItsPhotoMenuAcrossTheLock() {
        app.withMountedApp { fixture, holder ->
            composeRule.runOnIdle { holder.sectionState.sectionName = MainSection.Settings.name }
            app.awaitCondition { app.shown(HEADER_PHOTO_TAG) }
            composeRule.onNodeWithTag(HEADER_PHOTO_TAG).performClick()
            app.awaitCondition { labelled(R.string.profile_choose_photos) }
            assertEquals(ProfileControls(menu = true, save = true, edit = false), profileControls())

            app.lock(fixture.appState)
            assertEquals("no profile control is composed under the lock", NOTHING, profileControls())

            app.unlock(fixture.appState)
            assertEquals("edit mode returns without the photo menu", EDITING, profileControls())
            assertEquals(SettingsDetail.Profile.name, holder.sectionState.settingsDetailName)

            app.systemBack()
            assertEquals("the first Back leaves edit mode", READING, profileControls())
            assertEquals(SettingsDetail.Profile.name, holder.sectionState.settingsDetailName)

            app.systemBack()
            assertNull("the second Back skips the Account screen", holder.sectionState.settingsDetailName)
            assertEquals(MainSection.Settings.name, holder.sectionState.sectionName)
            assertTrue(app.shown(HEADER_PHOTO_TAG))

            app.systemBack()
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

    /** A fixture whose target conversation holds one message with text no other surface carries. */
    private fun messageFixture(): ReturnFixture {
        val message =
            timelineRecord("ab".repeat(32), 1uL)
                .copy(groupIdHex = ConversationTimelineTestIds.GROUP_ID, plaintext = CONVERSATION_MESSAGE)
        return ReturnFixture(timelineScripts = listOf(ScriptedConversationTimelineSubscription(timelinePage(message))))
    }

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
        val READING = ProfileControls(menu = false, save = false, edit = true)
        val NOTHING = ProfileControls(menu = false, save = false, edit = false)
        const val TARGET_ROW_PREFIX = "chat.row."
        const val APPEARANCE_TAG = "appearance.colors.group"
        const val CONVERSATION_MESSAGE = "locked conversation only message"
        const val DETAILS_TAG = "chat_info.list"
        const val RELAYS_ROW_TAG = "chat_info.relays"
        const val HEADER_PHOTO_TAG = "settings.active_profile_avatar"
    }
}
