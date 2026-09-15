package dev.ipf.whitenoise.android.ui

import android.content.Context
import android.os.SystemClock
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import dev.ipf.marmotkit.AccountSummaryFfi
import dev.ipf.marmotkit.ChatConversationKindFfi
import dev.ipf.marmotkit.ChatListRowFfi
import dev.ipf.marmotkit.GroupLifecycleStateFfi
import dev.ipf.marmotkit.SelfMembershipFfi
import dev.ipf.whitenoise.android.R
import dev.ipf.whitenoise.android.state.AppText
import dev.ipf.whitenoise.android.state.ChatsController
import dev.ipf.whitenoise.android.state.DraftPersistence
import dev.ipf.whitenoise.android.state.DraftStore
import dev.ipf.whitenoise.android.state.WhiteNoiseAppState
import dev.ipf.whitenoise.android.state.isForConversation
import dev.ipf.whitenoise.android.ui.account.AccountAvatarButton
import dev.ipf.whitenoise.android.ui.chats.CHAT_LIST_HEAD_INPUT_GATE_MILLIS
import dev.ipf.whitenoise.android.ui.chats.CHAT_LIST_ROW_PLACEMENT_MAX_MILLIS
import dev.ipf.whitenoise.android.ui.common.LoadingScreen
import dev.ipf.whitenoise.android.ui.common.STARTUP_LOADING_TEST_TAG
import dev.ipf.whitenoise.android.ui.common.StartupLoadingScreen
import dev.ipf.whitenoise.android.ui.common.WhiteNoiseTopBar
import dev.ipf.whitenoise.android.ui.conversation.CONVERSATION_TOP_BAR_TAG
import dev.ipf.whitenoise.android.ui.navigation.MainShell
import dev.ipf.whitenoise.android.ui.theme.WhiteNoiseTheme
import kotlinx.coroutines.CompletableDeferred
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class AppNavigationTest {
    @get:Rule
    val composeRule = createComposeRule()

    /** The account avatar is the settings entry: monogram shown, no drawer affordance, one click opens settings. */
    @Test
    fun avatarButtonOpensSettingsWithoutDrawerNavigation() {
        var settingsClicks = 0

        composeRule.setContent {
            WhiteNoiseTheme {
                AccountAvatarButton(
                    title = "Ada Lovelace",
                    seed = "ada",
                    pictureUrl = null,
                    size = 40.dp,
                    onClick = { settingsClicks += 1 },
                )
            }
        }

        composeRule.onNodeWithContentDescription("Open navigation").assertDoesNotExist()
        composeRule.onNodeWithContentDescription("Open settings", substring = true).assertIsDisplayed()
        // The prototype's monogram is a single glyph, so "Ada Lovelace" reads "A".
        composeRule.onNodeWithText("A").assertIsDisplayed()

        composeRule.onNodeWithContentDescription("Open settings", substring = true).performClick()
        composeRule.runOnIdle { assertEquals(1, settingsClicks) }
    }

    @Test
    fun avatarWithoutUnreadDoesNotAnnounceUnread() {
        composeRule.setContent {
            WhiteNoiseTheme {
                AccountAvatarButton(
                    title = "Ada Lovelace",
                    seed = "ada",
                    pictureUrl = null,
                    size = 40.dp,
                    onClick = {},
                    showUnreadDot = false,
                )
            }
        }

        composeRule.onNodeWithContentDescription("Open settings", substring = true).assertIsDisplayed()
        composeRule
            .onNodeWithContentDescription("This account has unread messages", substring = true)
            .assertDoesNotExist()
    }

    @Test
    fun avatarWithUnreadAnnouncesThisAccountUnread() {
        composeRule.setContent {
            WhiteNoiseTheme {
                AccountAvatarButton(
                    title = "Ada Lovelace",
                    seed = "ada",
                    pictureUrl = null,
                    size = 40.dp,
                    onClick = {},
                    showUnreadDot = true,
                )
            }
        }

        composeRule
            .onNodeWithContentDescription("This account has unread messages", substring = true)
            .assertIsDisplayed()
    }

    /** Settings top bar returns to chat list with back link. */
    @Test
    fun settingsTopBarReturnsToChatListWithBackLink() {
        var backClicks = 0

        composeRule.setContent {
            WhiteNoiseTheme {
                WhiteNoiseTopBar(title = "Settings", onBack = { backClicks += 1 })
            }
        }

        composeRule.onNodeWithText("Settings").assertIsDisplayed()
        composeRule.onNodeWithText("Chats").assertDoesNotExist()
        composeRule.onNodeWithContentDescription("Open navigation").assertDoesNotExist()

        composeRule.onNodeWithContentDescription(context.getString(R.string.back)).performClick()
        composeRule.runOnIdle { assertEquals(1, backClicks) }
    }

    @Test
    fun loadingScreenHasNoBrandingText() {
        // LoadingScreen is a bare centered spinner — no branding text. Its visual
        // rendering is covered by the Roborazzi screenshot pilot; this just guards
        // that stray branding copy doesn't creep back onto it.
        composeRule.setContent {
            WhiteNoiseTheme {
                LoadingScreen()
            }
        }

        composeRule.onNodeWithText("Loading White Noise").assertDoesNotExist()
        composeRule.onNodeWithText("Starting Marmot").assertDoesNotExist()
        composeRule.onNodeWithText("White Noise").assertDoesNotExist()
        composeRule.onNodeWithText("Starting securely…").assertDoesNotExist()
        composeRule.onNodeWithContentDescription("White Noise logo").assertDoesNotExist()
    }

    /** The startup page is a spinner with one status line: no logo or branding copy. */
    @Test
    fun startupLoadingScreenShowsProgressMessageWithoutBranding() {
        composeRule.setContent {
            WhiteNoiseTheme {
                StartupLoadingScreen()
            }
        }

        composeRule.onNodeWithTag(STARTUP_LOADING_TEST_TAG).assertIsDisplayed()
        composeRule.onNodeWithText("Starting White Noise…").assertIsDisplayed()
        composeRule.onNodeWithText("Starting securely…").assertDoesNotExist()
        composeRule.onNodeWithContentDescription("White Noise logo").assertDoesNotExist()
    }

    /** A group's confirmation stays inside its own conversation and never shifts the visible one's header. */
    @Test
    fun groupConfirmationStaysInsideItsOriginatingConversationWithoutMovingHeader() {
        val appState = appState()
        composeRule.setContent {
            WhiteNoiseTheme {
                MainShell(appState = appState)
            }
        }
        val chatsController = awaitAttachedChatsController(appState)
        composeRule.runOnIdle {
            seedGroup(chatsController, GROUP_A, GROUP_A_NAME, activity = 2uL)
            seedGroup(chatsController, GROUP_B, GROUP_B_NAME, activity = 1uL)
        }
        composeRule.waitForIdle()

        openConversation(GROUP_A_NAME)

        val deliverGroupAResult = CompletableDeferred<Unit>()
        composeRule.runOnIdle {
            appState.launchMutation {
                deliverGroupAResult.await()
                appState.presentConversationTransient(
                    accountRef = ACCOUNT_REF,
                    groupIdHex = GROUP_A,
                    title = AppText.Plain(NOTICE_TEXT),
                )
            }
        }

        composeRule.onNodeWithContentDescription(context.getString(R.string.back)).performClick()
        awaitChatList()
        openConversation(GROUP_B_NAME)
        val headerBefore = composeRule.onNodeWithTag(CONVERSATION_TOP_BAR_TAG).fetchSemanticsNode().boundsInRoot

        composeRule.runOnIdle { deliverGroupAResult.complete(Unit) }
        composeRule.waitUntil(timeoutMillis = 5_000) {
            appState.transientNotice?.isForConversation(ACCOUNT_REF, GROUP_A) == true
        }
        composeRule.waitForIdle()

        composeRule.onNodeWithText(NOTICE_TEXT).assertDoesNotExist()
        val headerAfter =
            composeRule.onNodeWithTag(CONVERSATION_TOP_BAR_TAG).fetchSemanticsNode().boundsInRoot
        assertEquals(headerBefore, headerAfter)
    }

    /** Global confirmation stays clear of settings account actions during navigation. */
    @Test
    fun globalConfirmationStaysClearOfSettingsAccountActionsDuringNavigation() {
        val appState = appState()
        composeRule.setContent {
            WhiteNoiseTheme {
                MainShell(appState = appState)
            }
        }
        awaitAttachedChatsController(appState)

        composeRule.runOnIdle {
            appState.presentTransient(AppText.Plain(GLOBAL_NOTICE_TEXT))
        }
        composeRule.onNodeWithContentDescription("Open settings", substring = true).performClick()

        composeRule.onNodeWithText("Settings").assertIsDisplayed()
        val profile =
            composeRule
                .onNodeWithTag("settings.active_profile")
                .assertIsDisplayed()
                .assertHasClickAction()
        val notice = composeRule.onNodeWithTag(GLOBAL_TRANSIENT_NOTICE_TAG).assertIsDisplayed()

        val noticeBounds = notice.fetchSemanticsNode().boundsInRoot
        assertTrue(profile.fetchSemanticsNode().boundsInRoot.bottom <= noticeBounds.top)
    }

    private val context = ApplicationProvider.getApplicationContext<Context>()

    private fun appState() =
        WhiteNoiseAppState(
            context = context,
            draftStore = DraftStore(DiscardedDrafts),
            accountIdHexResolver = { ACCOUNT_ID },
            accounts =
                listOf(
                    AccountSummaryFfi(
                        label = ACCOUNT_REF,
                        accountIdHex = ACCOUNT_ID,
                        localSigning = true,
                        externalSigning = false,
                        signedOut = false,
                        running = true,
                    ),
                ),
            activeAccountRef = ACCOUNT_REF,
        )

    private fun awaitAttachedChatsController(appState: WhiteNoiseAppState): ChatsController {
        var controller: ChatsController? = null
        composeRule.waitUntil(timeoutMillis = 5_000) {
            controller = attachedChatsController(appState)
            controller?.boundAccountRef == ACCOUNT_REF && controller?.isLoading == false
        }
        return requireNotNull(controller)
    }

    private fun attachedChatsController(appState: WhiteNoiseAppState): ChatsController? {
        val field = WhiteNoiseAppState::class.java.getDeclaredField("chatsController").apply { isAccessible = true }
        return field.get(appState) as? ChatsController
    }

    /**
     * Taps a chat row until its conversation renders. Seeding promotes a new list head, which closes
     * row input for the head-reorder and row-placement gate windows while the row stays semantically
     * enabled (a disabled ListItem would flash grey), so `isEnabled()` no longer tells when a tap lands.
     * Both gates count down on the compose clock, which only moves while the test waits, so the clock is
     * advanced past the longest window before each tap.
     */
    private fun openConversation(name: String) {
        val deadline = SystemClock.uptimeMillis() + OPEN_CONVERSATION_TIMEOUT_MS
        while (!conversationOpen()) {
            check(SystemClock.uptimeMillis() < deadline) { "Tapping $name never opened its conversation" }
            composeRule.mainClock.advanceTimeBy(CHAT_LIST_HEAD_INPUT_GATE_MILLIS + CHAT_LIST_ROW_PLACEMENT_MAX_MILLIS)
            composeRule.waitForIdle()
            composeRule.onAllNodes(hasText(name)).onFirst().performClick()
            composeRule.waitForIdle()
        }
        composeRule.onNodeWithTag(CONVERSATION_TOP_BAR_TAG).assertIsDisplayed()
        composeRule.onAllNodes(hasText(name)).onFirst().assertIsDisplayed()
    }

    /** Waits for the conversation to leave so the next row tap targets the list, not the exiting header. */
    private fun awaitChatList() {
        composeRule.waitUntil(timeoutMillis = OPEN_CONVERSATION_TIMEOUT_MS) { !conversationOpen() }
    }

    /** Whether a conversation header is composed, i.e. a conversation route is on screen. */
    private fun conversationOpen(): Boolean {
        val headers = composeRule.onAllNodes(hasTestTag(CONVERSATION_TOP_BAR_TAG)).fetchSemanticsNodes()
        return headers.isNotEmpty()
    }

    private fun seedGroup(
        controller: ChatsController,
        groupId: String,
        name: String,
        activity: ULong,
    ) = controller.applyChatListRow(chatRow(groupId, name, activity))

    private fun chatRow(
        groupId: String,
        name: String,
        activity: ULong,
    ) = ChatListRowFfi(
        selfMembership = SelfMembershipFfi.MEMBER,
        unreadMentionCount = 0uL,
        unreadMention = false,
        groupIdHex = groupId,
        archived = false,
        pendingConfirmation = false,
        title = name,
        groupName = name,
        avatarUrl = null,
        avatar = null,
        lastMessage = null,
        unreadCount = 0uL,
        hasUnread = false,
        firstUnreadMessageIdHex = null,
        lastReadMessageIdHex = null,
        lastReadTimelineAt = null,
        conversationCreatedAt = activity,
        activitySortAt = activity,
        updatedAt = activity,
        leaveRequestPending = false,
        leaveRequestedAtMs = null,
        manuallyMarkedUnread = false,
        conversationKind = ChatConversationKindFfi.GROUP,
        muted = false,
        mutedUntilMs = null,
        pinned = false,
        pinnedPosition = null,
        lifecycleState = GroupLifecycleStateFfi.STABLE,
        disbanding = false,
        disbandRequest = null,
    )

    private object DiscardedDrafts : DraftPersistence {
        override fun read(): Map<String, String> = emptyMap()

        override fun write(
            key: String,
            value: String?,
        ) = Unit
    }

    private companion object {
        const val ACCOUNT_REF = "alice"
        val ACCOUNT_ID = "a0".repeat(32)
        val GROUP_A = "c2".repeat(32)
        val GROUP_B = "c3".repeat(32)
        const val GROUP_A_NAME = "Group A"
        const val GROUP_B_NAME = "Group B"
        const val NOTICE_TEXT = "Admin added"
        const val GLOBAL_NOTICE_TEXT = "Notifications enabled"
        const val OPEN_CONVERSATION_TIMEOUT_MS = 5_000L
    }
}
