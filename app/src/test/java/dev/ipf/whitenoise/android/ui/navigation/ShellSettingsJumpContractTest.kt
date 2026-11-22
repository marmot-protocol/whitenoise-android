package dev.ipf.whitenoise.android.ui.navigation

import android.content.Context
import androidx.activity.ComponentActivity
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import androidx.test.espresso.Espresso
import dev.ipf.marmotkit.AppGroupMemberRecordFfi
import dev.ipf.whitenoise.android.R
import dev.ipf.whitenoise.android.audio.VoicePlaybackController
import dev.ipf.whitenoise.android.audio.VoicePlaybackSource
import dev.ipf.whitenoise.android.core.SupportContact
import dev.ipf.whitenoise.android.notifications.conversationShortcutId
import dev.ipf.whitenoise.android.share.InboundShareImportState
import dev.ipf.whitenoise.android.share.SerializedPendingShareRequestStore
import dev.ipf.whitenoise.android.share.SharePayload
import dev.ipf.whitenoise.android.share.ShareRequest
import dev.ipf.whitenoise.android.state.AppPhase
import dev.ipf.whitenoise.android.state.ConversationTimelineTestIds
import dev.ipf.whitenoise.android.state.ScriptedConversationTimelineSubscription
import dev.ipf.whitenoise.android.state.WhiteNoiseAppState
import dev.ipf.whitenoise.android.state.timelinePage
import dev.ipf.whitenoise.android.state.timelineRecord
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.ParameterizedRobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Three routes jump from Settings straight into a conversation and leave Settings behind, as master writes the Chats
 * section at each: a share aimed at one chat, the player bar's return to the playing message's chat, and the support
 * chat opened from Settings. Each is checked on screen: on a phone Back from the conversation shows the chat list,
 * and at two-pane width the list pane beside the conversation shows the chat list, never the Settings screen it left.
 */
@RunWith(ParameterizedRobolectricTestRunner::class)
@Config(sdk = [36], qualifiers = "en-w1280dp-h800dp-mdpi")
class ShellSettingsJumpContractTest(
    private val jump: SettingsJump,
) {
    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    /** Asserts every route audit this test drove found a well-formed stack and a matching saved section. */
    @get:Rule
    val routeAudit = ShellRouteAuditRule()

    private val shell = MountedContractShell(composeRule)
    private val context = ApplicationProvider.getApplicationContext<Context>()

    /** Releases the process-wide player so one test's session cannot reach the next. */
    @After
    fun resetPlayer() {
        VoicePlaybackController.stop()
    }

    /** On a phone the jump opens the conversation, and Back from it shows the chat list, not Settings. */
    @Test
    fun onAPhoneBackFromTheConversationShowsTheChatList() {
        withSettingsJumpShell(COMPACT_WIDTH) { fixture, holder ->
            jumpFromSettings(fixture, holder)

            Espresso.pressBack()
            shell.awaitCondition { holder.selectedChat.value == null }
            shell.settle()
            assertTrue("Back from the conversation shows the chat list", composed(hasText(FIRST_ROW)))
            assertFalse("Back must not return to the Settings screen it left", composed(jump.settingsScreen()))
            assertFalse(composed(hasText(CONVERSATION_MESSAGE, substring = true)))
        }
    }

    /** At two-pane width the jump leaves the chat list, not the Settings screen, in the list pane. */
    @Test
    fun besideTheConversationTheListPaneShowsTheChatList() {
        withSettingsJumpShell(EXPANDED_WIDTH) { fixture, holder ->
            jumpFromSettings(fixture, holder)

            val inListPane = hasAnyAncestor(hasTestTag(TWO_PANE_LIST_TAG))
            assertTrue("the list pane shows the chat list", composed(hasText(FIRST_ROW) and inListPane))
            assertFalse("the list pane must not keep the Settings screen", composed(jump.settingsScreen()))
        }
    }

    /**
     * Opens Settings at this jump's screen, checks it is on screen, then makes the jump and waits until the target
     * conversation's message is composed.
     */
    private fun jumpFromSettings(
        fixture: ReturnFixture,
        holder: MainShellStateHolder,
    ) {
        if (jump == SettingsJump.SupportChat) makeTheTargetTheSupportChat(fixture, holder)
        composeRule.runOnIdle { holder.restoreSection(MainSection.Settings, jump.detail) }
        shell.awaitCondition { composed(jump.settingsScreen()) }
        assertEquals(null, holder.selectedChat.value)

        when (jump) {
            SettingsJump.Share -> composeRule.runOnIdle { holder.acceptInboundShareRequest(shareTo(fixture)) }
            SettingsJump.PlaybackReturn -> {
                composeRule.runOnIdle { startVoiceSession(fixture.target.groupIdHex) }
                shell.awaitCondition { composed(returnToSource()) }
                composeRule.onNode(returnToSource()).performClick()
            }
            SettingsJump.SupportChat -> composeRule.onNode(hasTestTag(SUPPORT_START_TAG)).performScrollTo().performClick()
        }
        shell.awaitCondition { holder.selectedChat.value?.id == fixture.target.groupIdHex }
        shell.awaitCondition { composed(hasText(CONVERSATION_MESSAGE, substring = true)) }
        shell.settle()
    }

    /**
     * Mounts the shell on its ready surface at [width], fed the holder's inbound share as the Activity feeds it, over
     * a fixture whose target chat has one message and, for the support jump, is an unnamed direct chat.
     */
    private fun withSettingsJumpShell(
        width: Dp,
        exercise: (ReturnFixture, MainShellStateHolder) -> Unit,
    ) {
        val message =
            timelineRecord("cd".repeat(32), 1uL)
                .copy(groupIdHex = ConversationTimelineTestIds.GROUP_ID, plaintext = CONVERSATION_MESSAGE)
        val fixture =
            ReturnFixture(
                customizeRow = { index, row -> if (index == TARGET_ROW) row.copy(groupName = "") else row },
                timelineScripts = List(OPEN_SCRIPTS) { ScriptedConversationTimelineSubscription(timelinePage(message)) },
            )
        WhiteNoiseAppState::class.java
            .getDeclaredMethod("setPhase", AppPhase::class.java)
            .apply { isAccessible = true }
            .invoke(fixture.appState, AppPhase.Ready)
        val store = SerializedPendingShareRequestStore(ShellShareRouteContractTest.MemoryStore())
        val import = InboundShareImportState().apply { this.store = store }
        shell.withMountedShell(
            width = mutableStateOf(width),
            fixture = fixture,
            shell = { holder ->
                MainShell(
                    appState = fixture.appState,
                    stateHolder = holder,
                    inboundShareRequest = holder.inboundShareRequest.value,
                    shareImport = import,
                    onShareRequestHandled = { holder.acknowledgeInboundShareRequest(it.requestId) },
                )
            },
            exercise = exercise,
        )
    }

    /**
     * Makes the target chat the active account's direct chat with the support contact, the chat Settings' support
     * screen opens, by giving it exactly those two members.
     */
    private fun makeTheTargetTheSupportChat(
        fixture: ReturnFixture,
        holder: MainShellStateHolder,
    ) {
        composeRule.runOnIdle {
            val accountRef = ConversationTimelineTestIds.ACCOUNT_REF
            val self =
                fixture.appState.accounts
                    .first { it.label == accountRef }
                    .accountIdHex
            val controller = holder.chatsController(accountRef, fixture.appState.runtimeGeneration)
            controller.memberCacheByGroup =
                controller.memberCacheByGroup +
                (
                    fixture.target.groupIdHex to
                        listOf(
                            AppGroupMemberRecordFfi(self, accountRef, true),
                            AppGroupMemberRecordFfi(SupportContact.NPUB, null, false),
                        )
                )
            assertEquals(fixture.target.groupIdHex, fixture.appState.existingDirectChat(SupportContact.NPUB)?.id)
        }
    }

    /** A text share whose import has finished, aimed at the target chat's shortcut. */
    private fun shareTo(fixture: ReturnFixture): ShareRequest {
        val shortcut = conversationShortcutId(ConversationTimelineTestIds.ACCOUNT_REF, fixture.target.groupIdHex)
        return ShareRequest(
            payload = SharePayload(SHARED_TEXT, emptyList(), "text/plain", importReady = true),
            shortcutId = shortcut,
            requestId = SHARE_REQUEST_ID,
        )
    }

    /** Publishes a ready voice session for a message in [groupIdHex], whose return opens that chat without a focus. */
    @Suppress("UNCHECKED_CAST")
    private fun startVoiceSession(groupIdHex: String) {
        val field = VoicePlaybackController::class.java.getDeclaredField("_state").apply { isAccessible = true }
        val state = field.get(VoicePlaybackController) as MutableStateFlow<VoicePlaybackController.PlaybackState>
        val source =
            VoicePlaybackSource(ConversationTimelineTestIds.ACCOUNT_REF, groupIdHex, "message", "Maya", false)
        state.value =
            VoicePlaybackController.PlaybackState(key = "voice", ready = true, sessionId = 10, source = source)
    }

    /** The player bar's body, whose click returns to the playing message's chat. */
    private fun returnToSource(): SemanticsMatcher {
        val label = context.getString(R.string.tts_bar_return_to_source)
        return SemanticsMatcher("click label is $label") {
            it.config.getOrElseNullable(SemanticsActions.OnClick) { null }?.label == label
        }
    }

    /** True while any node satisfies [matcher]. */
    private fun composed(matcher: SemanticsMatcher): Boolean = composeRule.onAllNodes(matcher, useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()

    /** One route from Settings into a conversation, with the Settings screen it starts on. */
    enum class SettingsJump(
        internal val detail: SettingsDetail,
    ) {
        Share(SettingsDetail.Appearance),
        PlaybackReturn(SettingsDetail.Appearance),
        SupportChat(SettingsDetail.Support),
        ;

        /** A node only the Settings screen this jump starts on composes. */
        internal fun settingsScreen(): SemanticsMatcher =
            when (detail) {
                SettingsDetail.Support -> hasTestTag(SUPPORT_START_TAG)
                else -> hasText(ApplicationProvider.getApplicationContext<Context>().getString(R.string.appearance))
            }
    }

    companion object {
        /** One run per route. */
        @JvmStatic
        @ParameterizedRobolectricTestRunner.Parameters(name = "{0}")
        fun jumps(): List<Array<Any>> = SettingsJump.entries.map { arrayOf(it) }

        private val COMPACT_WIDTH = 839.dp
        private val EXPANDED_WIDTH = 840.dp
        private const val TARGET_ROW = 5
        private const val FIRST_ROW = "Return chat 0"
        private const val SUPPORT_START_TAG = "support.start"
        private const val CONVERSATION_MESSAGE = "settings jump contract only message"
        private const val SHARED_TEXT = "shared from Settings"
        private const val SHARE_REQUEST_ID = "share-from-settings"
        private const val OPEN_SCRIPTS = 3
    }
}
