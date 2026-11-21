package dev.ipf.whitenoise.android.ui.navigation

import android.content.Context
import androidx.activity.ComponentActivity
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import androidx.test.espresso.Espresso
import dev.ipf.whitenoise.android.R
import dev.ipf.whitenoise.android.state.ConversationTimelineTestIds
import dev.ipf.whitenoise.android.state.ScriptedConversationTimelineSubscription
import dev.ipf.whitenoise.android.state.WhiteNoiseAppState
import dev.ipf.whitenoise.android.state.timelinePage
import dev.ipf.whitenoise.android.state.timelineRecord
import dev.ipf.whitenoise.android.ui.group.ChatDetailsDestination
import dev.ipf.whitenoise.android.ui.group.GROUP_RELAYS_SCREEN_TAG
import dev.ipf.whitenoise.android.ui.group.MUTE_SWITCH_ROW_TAG
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.ParameterizedRobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The named lifetime change of checkpoint C (plan section 5, "Lifted screens"), pinned for every chat-details screen
 * that moved onto the route stack. On master these screens were composition-only flags in group details: lost on
 * Activity recreation, app lock and process death. As stack entries above their conversation they are now kept on
 * Activity recreation and app lock, still dropped on process death, and cleared by an account change. Back from each
 * keeps master's ladder: chat details, then the conversation.
 */
@RunWith(ParameterizedRobolectricTestRunner::class)
@Config(sdk = [36], qualifiers = "en-w1280dp-h800dp-mdpi")
class ShellLiftedChatDetailsScreenContractTest(
    private val screen: LiftedScreen,
) {
    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    /** Asserts every route audit this test drove found a well-formed stack and a matching saved section. */
    @get:Rule
    val routeAudit = ShellRouteAuditRule()

    private val shell = MountedContractShell(composeRule)
    private val app = MountedContractApp(composeRule)

    /** Activity recreation keeps the screen open, and Back returns to chat details, then the conversation. */
    @Test
    fun theScreenIsKeptByRecreation() {
        shell.withMountedShell(mutableStateOf(COMPACT_WIDTH), messageFixture()) { fixture, holder ->
            openScreen(fixture, holder, shell::awaitCondition)

            shell.recreate()
            assertTrue("recreation keeps the ${screen.name} screen", screen.shown(composeRule))
            assertTopIs(holder, screen.destination)

            Espresso.pressBack()
            shell.settle()
            assertTrue("Back returns to chat details", shown(DETAILS_TAG))
            assertFalse(screen.shown(composeRule))
            Espresso.pressBack()
            shell.settle()
            assertFalse("the next Back returns to the conversation", shown(DETAILS_TAG))
            assertEquals(fixture.target.groupIdHex, holder.selectedChat.value?.id)
        }
    }

    /** The lock composes nothing of it, and unlocking shows the screen again; Back returns to chat details. */
    @Test
    fun theScreenIsKeptByTheLock() {
        app.withMountedApp(messageFixture()) { fixture, holder ->
            openScreen(fixture, holder, app::awaitCondition, viaApp = true)

            app.lock(fixture.appState)
            assertFalse("nothing of the screen is composed under the lock", screen.shown(composeRule))
            app.unlock(fixture.appState)
            assertTrue("the lock keeps the ${screen.name} screen", screen.shown(composeRule))
            assertTopIs(holder, screen.destination)

            app.systemBack()
            assertTrue("Back returns to chat details", shown(DETAILS_TAG))
            assertFalse(screen.shown(composeRule))
        }
    }

    /**
     * Process death does not bring the screen back: a fresh process restores what the saved state holds, the
     * conversation, and nothing above it.
     */
    @Test
    fun theScreenIsDroppedByProcessDeath() {
        shell.withRestartableShell(mutableStateOf(COMPACT_WIDTH), messageFixture()) { fixture, holder, restart ->
            openScreen(fixture, holder, shell::awaitCondition)

            val restored = composeRule.runOnIdle { restart() }
            shell.awaitCondition { restored.selectedChat.value?.id == fixture.target.groupIdHex }
            shell.settle()

            val conversation = conversationRoot(restored.shellNavigator.stack)
            assertEquals(listOf(ShellRoute.Chats, conversation), restored.shellNavigator.stack)
            assertFalse("process death drops the ${screen.name} screen", screen.shown(composeRule))
        }
    }

    /** An account switch closes the conversation and clears the screen with it. */
    @Test
    fun theScreenIsClearedByAnAccountSwitch() {
        shell.withMountedShell(mutableStateOf(COMPACT_WIDTH), messageFixture()) { fixture, holder ->
            openScreen(fixture, holder, shell::awaitCondition)

            composeRule.runOnIdle { fixture.appState.replaceActiveAccount(OTHER_ACCOUNT_REF) }
            shell.awaitCondition { holder.selectedChat.value == null }
            shell.settle()

            assertEquals(CHATS_ROOT, holder.shellNavigator.stack)
            assertFalse("the account switch clears the ${screen.name} screen", screen.shown(composeRule))
        }
    }

    /** Opens the target conversation, its details and then this screen, checking each step is on screen. */
    private fun openScreen(
        fixture: ReturnFixture,
        holder: MainShellStateHolder,
        await: (() -> Boolean) -> Unit,
        viaApp: Boolean = false,
    ) {
        if (viaApp) app.openTarget(fixture, holder) else shell.openTarget(fixture, holder)
        await { reads(CONVERSATION_MESSAGE) }
        composeRule.onNode(conversationHeader()).performClick()
        await { shown(DETAILS_TAG) }
        composeRule.onNode(screen.entry()).performScrollTo().performClick()
        await { screen.shown(composeRule) }
        assertFalse("the ${screen.name} screen replaces the details list", shown(DETAILS_TAG))
        assertTopIs(holder, screen.destination)
    }

    /** Asserts the route stack's top entry is [destination] above the open conversation. */
    private fun assertTopIs(
        holder: MainShellStateHolder,
        destination: ChatDetailsDestination,
    ) {
        assertEquals(ShellRoute.ChatDetailsScreen(destination), holder.shellNavigator.stack.last())
    }

    /** A conversation whose one message tells it apart from the list, with one ready script per open. */
    private fun messageFixture(): ReturnFixture {
        val message =
            timelineRecord("ef".repeat(32), 1uL)
                .copy(groupIdHex = ConversationTimelineTestIds.GROUP_ID, plaintext = CONVERSATION_MESSAGE)
        return ReturnFixture(
            timelineScripts = List(OPEN_SCRIPTS) { ScriptedConversationTimelineSubscription(timelinePage(message)) },
        )
    }

    /** True while a node carrying [tag] is composed. */
    private fun shown(tag: String): Boolean = composeRule.onAllNodes(hasTestTag(tag)).fetchSemanticsNodes().isNotEmpty()

    /** True while any node carries [text]. */
    private fun reads(text: String): Boolean {
        val carriers = composeRule.onAllNodes(hasText(text, substring = true))
        return carriers.fetchSemanticsNodes().isNotEmpty()
    }

    /** The conversation header, the one clickable heading on the conversation, which opens its details. */
    private fun conversationHeader() = hasClickAction() and SemanticsMatcher.keyIsDefined(SemanticsProperties.Heading)

    /** One lifted screen: its stack destination, the details row that opens it and a node only it composes. */
    enum class LiftedScreen(
        internal val destination: ChatDetailsDestination,
        private val entryTag: String?,
        private val entryText: Int?,
        private val screenTag: String?,
        private val screenText: Int?,
    ) {
        Relays(ChatDetailsDestination.Relays, "chat_info.relays", null, GROUP_RELAYS_SCREEN_TAG, null),
        GroupInfo(
            destination = ChatDetailsDestination.GroupInfo,
            entryTag = "chat_info.developer_tools",
            entryText = null,
            screenTag = null,
            screenText = R.string.group_identifiers,
        ),
        NotificationSettings(
            ChatDetailsDestination.NotificationSettings,
            null,
            R.string.sounds_and_notifications,
            MUTE_SWITCH_ROW_TAG,
            null,
        ),
        BubbleColors(
            destination = ChatDetailsDestination.BubbleColors,
            entryTag = null,
            entryText = R.string.chat_bubble_colors,
            screenTag = "bubble_colors.mine.picker",
            screenText = null,
        ),
        ;

        /** The clickable details row that opens this screen. */
        internal fun entry(): SemanticsMatcher {
            val tag = entryTag ?: return hasText(string(requireNotNull(entryText))) and hasClickAction()
            return hasTestTag(tag)
        }

        /** True while this screen is composed. */
        internal fun shown(rule: androidx.compose.ui.test.junit4.AndroidComposeTestRule<*, *>): Boolean {
            val matcher = screenTag?.let(::hasTestTag) ?: hasText(string(requireNotNull(screenText)))
            return rule.onAllNodes(matcher, useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()
        }

        /** The app's string for [id]. */
        private fun string(id: Int): String = ApplicationProvider.getApplicationContext<Context>().getString(id)
    }

    companion object {
        /** One run per lifted screen. */
        @JvmStatic
        @ParameterizedRobolectricTestRunner.Parameters(name = "{0}")
        fun screens(): List<Array<Any>> = LiftedScreen.entries.map { arrayOf(it) }

        private val COMPACT_WIDTH = 839.dp
        private const val DETAILS_TAG = "chat_info.list"
        private const val CONVERSATION_MESSAGE = "lifted screen contract only message"
        private const val OTHER_ACCOUNT_REF = "other-lifted-account"
        private const val OPEN_SCRIPTS = 3
    }
}

/** Uses the same private account setter as the other mounted account-switch contract tests. */
private fun WhiteNoiseAppState.replaceActiveAccount(accountRef: String?) {
    WhiteNoiseAppState::class.java
        .getDeclaredMethod("setActiveAccountRef", String::class.java)
        .apply { isAccessible = true }
        .invoke(this, accountRef)
}
