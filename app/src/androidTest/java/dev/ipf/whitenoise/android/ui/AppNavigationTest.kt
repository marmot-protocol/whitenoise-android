package dev.ipf.whitenoise.android.ui

import android.content.Context
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isEnabled
import androidx.compose.ui.test.junit4.v2.createComposeRule
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
import dev.ipf.whitenoise.android.ui.account.SettingsAccountHeader
import dev.ipf.whitenoise.android.ui.common.LoadingScreen
import dev.ipf.whitenoise.android.ui.common.StartupLoadingScreen
import dev.ipf.whitenoise.android.ui.conversation.CONVERSATION_TOP_BAR_TAG
import dev.ipf.whitenoise.android.ui.navigation.MainShell
import dev.ipf.whitenoise.android.ui.settings.SettingsTopBar
import dev.ipf.whitenoise.android.ui.theme.WhiteNoiseTheme
import kotlinx.coroutines.CompletableDeferred
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class AppNavigationTest {
    @get:Rule
    val composeRule = createComposeRule()

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
        composeRule.onNodeWithText("AL").assertIsDisplayed()

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

    @Test
    fun settingsTopBarReturnsToChatListWithBackLink() {
        var backClicks = 0

        composeRule.setContent {
            WhiteNoiseTheme {
                SettingsTopBar(onBackToChats = { backClicks += 1 })
            }
        }

        composeRule.onNodeWithText("Settings").assertIsDisplayed()
        composeRule.onNodeWithText("Chats").assertDoesNotExist()
        composeRule.onNodeWithContentDescription("Open navigation").assertDoesNotExist()

        composeRule.onNodeWithContentDescription("Back to chats").performClick()
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

    @Test
    fun startupLoadingScreenShowsBrandedProgress() {
        composeRule.setContent {
            WhiteNoiseTheme {
                StartupLoadingScreen()
            }
        }

        composeRule.onNodeWithText("White Noise").assertIsDisplayed()
        composeRule.onNodeWithText("Starting securely…").assertIsDisplayed()
        composeRule.onNodeWithContentDescription("White Noise logo").assertIsDisplayed()
    }

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

        // Seeding promotes a new list head, which closes row input for the
        // head-reorder gate window — click only once the row re-enables.
        awaitEnabledRow(GROUP_A_NAME)
        composeRule.onNodeWithText(GROUP_A_NAME).performClick()
        composeRule.onNodeWithText(GROUP_A_NAME).assertIsDisplayed()

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
        awaitEnabledRow(GROUP_B_NAME)
        composeRule.onNodeWithText(GROUP_B_NAME).performClick()
        composeRule.onNodeWithText(GROUP_B_NAME).assertIsDisplayed()
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

    @Test
    fun globalConfirmationStaysClearOfSettingsAccountActionsDuringNavigation() {
        val appState = appState()
        composeRule.setContent {
            WhiteNoiseTheme {
                ShellTransientNoticeLayout(notice = appState.transientNotice) {
                    MainShell(appState = appState)
                }
            }
        }
        awaitAttachedChatsController(appState)

        composeRule.runOnIdle {
            appState.presentTransient(AppText.Plain(GLOBAL_NOTICE_TEXT))
        }
        composeRule.onNodeWithContentDescription("Open settings", substring = true).performClick()

        composeRule.onNodeWithText("Settings").assertIsDisplayed()
        val selector =
            composeRule
                .onNodeWithContentDescription("Switch Account")
                .assertIsDisplayed()
                .assertHasClickAction()
        val qr =
            composeRule
                .onNodeWithContentDescription("My QR code")
                .assertIsDisplayed()
                .assertHasClickAction()
        val notice = composeRule.onNodeWithTag(GLOBAL_TRANSIENT_NOTICE_TAG).assertIsDisplayed()

        val noticeBounds = notice.fetchSemanticsNode().boundsInRoot
        assertTrue(selector.fetchSemanticsNode().boundsInRoot.bottom <= noticeBounds.top)
        assertTrue(qr.fetchSemanticsNode().boundsInRoot.bottom <= noticeBounds.top)
    }

    @Test
    fun accountHeaderSeparatesSelectorFromQrAction() {
        var selectorClicks = 0
        var qrClicks = 0

        composeRule.setContent {
            WhiteNoiseTheme {
                SettingsAccountHeader(
                    title = "Main Identity",
                    subtitle = "npub1abc...xyz",
                    seed = "main-identity",
                    pictureUrl = null,
                    onOpenAccountSelector = { selectorClicks += 1 },
                    onOpenQr = { qrClicks += 1 },
                )
            }
        }

        composeRule.onNodeWithContentDescription("Switch Account").assertIsDisplayed()
        composeRule.onNodeWithContentDescription("My QR code").assertIsDisplayed()

        composeRule.onNodeWithContentDescription("Switch Account").performClick()
        composeRule.runOnIdle {
            assertEquals(1, selectorClicks)
            assertEquals(0, qrClicks)
        }

        composeRule.onNodeWithContentDescription("My QR code").performClick()
        composeRule.runOnIdle {
            assertEquals(1, selectorClicks)
            assertEquals(1, qrClicks)
        }
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

    private fun awaitEnabledRow(name: String) {
        composeRule.waitUntil(timeoutMillis = 5_000) {
            composeRule
                .onAllNodes(hasText(name) and isEnabled())
                .fetchSemanticsNodes()
                .isNotEmpty()
        }
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
    }
}
