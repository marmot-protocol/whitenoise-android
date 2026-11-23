package dev.ipf.whitenoise.android.ui

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import dev.ipf.marmotkit.AccountSummaryFfi
import dev.ipf.marmotkit.AppBlobEndpointFfi
import dev.ipf.marmotkit.AppGroupEncryptedMediaComponentFfi
import dev.ipf.marmotkit.AppGroupRecordFfi
import dev.ipf.marmotkit.AppProtocolProfileFfi
import dev.ipf.marmotkit.ChatConversationKindFfi
import dev.ipf.marmotkit.ChatListRowFfi
import dev.ipf.marmotkit.EncryptedMediaVersionFfi
import dev.ipf.marmotkit.GroupLifecycleStateFfi
import dev.ipf.marmotkit.SelfMembershipFfi
import dev.ipf.whitenoise.android.R
import dev.ipf.whitenoise.android.state.ChatListItem
import dev.ipf.whitenoise.android.state.ConversationController
import dev.ipf.whitenoise.android.state.DraftPersistence
import dev.ipf.whitenoise.android.state.DraftStore
import dev.ipf.whitenoise.android.state.WhiteNoiseAppState
import dev.ipf.whitenoise.android.ui.conversation.ConversationScreen
import dev.ipf.whitenoise.android.ui.theme.WhiteNoiseTheme
import org.junit.Assert.assertFalse
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * #4030: a chat whose entry row already counts unread mentions shows the jump-to-mention control on the first
 * conversation frame, while the timeline has not anchored yet, and the control accepts a tap at once. A chat or
 * account with no unread mentions must not inherit a stale control.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "w360dp-h780dp-mdpi")
class ConversationMentionJumpFirstFrameCoverageTest {
    @get:Rule
    val composeRule = createComposeRule()

    private val context = ApplicationProvider.getApplicationContext<Context>()

    @Test
    fun knownUnreadMentionShowsOnTheFirstFrameBeforeTheTimelineAnchors() {
        val appState = appState()
        val controller = controller(appState, GROUP_ID, mentions = 2uL)
        try {
            composeRule.mainClock.autoAdvance = false
            composeRule.setContent { Screen(appState, controller, GROUP_ID, mentions = 2uL) }
            composeRule.mainClock.advanceTimeByFrame()

            assertFalse("the timeline is still anchoring", controller.timeline.isNotEmpty())
            val button = composeRule.onNodeWithContentDescription(mentionLabel(2), useUnmergedTree = true)
            button.assertIsDisplayed()
            button.assert(SemanticsMatcher.keyIsDefined(SemanticsActions.OnClick))
            // A first-frame tap is accepted: it waits for entry positioning instead of being dropped.
            button.performClick()
            composeRule.mainClock.advanceTimeByFrame()
            composeRule.onNodeWithContentDescription(mentionLabel(2), useUnmergedTree = true).assertIsDisplayed()
        } finally {
            composeRule.mainClock.autoAdvance = true
            controller.onCleared()
        }
    }

    @Test
    fun chatWithoutUnreadMentionsShowsNoControl() {
        val appState = appState()
        val controller = controller(appState, GROUP_ID, mentions = 0uL)
        try {
            composeRule.mainClock.autoAdvance = false
            composeRule.setContent { Screen(appState, controller, GROUP_ID, mentions = 0uL) }
            composeRule.mainClock.advanceTimeByFrame()

            mentionNodes().assertCountEquals(0)
        } finally {
            composeRule.mainClock.autoAdvance = true
            controller.onCleared()
        }
    }

    @Test
    fun switchingToAChatWithoutMentionsDropsTheSeededControlOnItsFirstFrame() {
        val appState = appState()
        val mentioned = controller(appState, GROUP_ID, mentions = 3uL)
        val quiet = controller(appState, OTHER_GROUP_ID, mentions = 0uL)
        var open by mutableStateOf(Triple(mentioned, GROUP_ID, 3uL))
        try {
            composeRule.mainClock.autoAdvance = false
            // One call site: the same composition carries over to the next chat, as in the app.
            composeRule.setContent {
                val (controller, groupId, mentions) = open
                Screen(appState, controller, groupId, mentions)
            }
            composeRule.mainClock.advanceTimeByFrame()
            composeRule.onNodeWithContentDescription(mentionLabel(3), useUnmergedTree = true).assertIsDisplayed()

            open = Triple(quiet, OTHER_GROUP_ID, 0uL)
            composeRule.mainClock.advanceTimeByFrame()
            mentionNodes().assertCountEquals(0)
        } finally {
            composeRule.mainClock.autoAdvance = true
            mentioned.onCleared()
            quiet.onCleared()
        }
    }

    @androidx.compose.runtime.Composable
    @Suppress("FunctionNaming")
    private fun Screen(
        appState: WhiteNoiseAppState,
        controller: ConversationController,
        groupId: String,
        mentions: ULong,
    ) {
        WhiteNoiseTheme {
            ConversationScreen(
                appState = appState,
                chat =
                    ChatListItem(
                        group = group(groupId),
                        latest = null,
                        otherMemberAccount = null,
                        memberCount = 3,
                        memberSnapshot = null,
                        projection = row(groupId, mentions),
                    ),
                controller = controller,
                onBack = {},
            )
        }
    }

    private fun mentionLabel(count: Int): String =
        context.getString(R.string.conversation_jump_to_mention) + ", " +
            context.resources.getQuantityString(R.plurals.unread_mentions_count, count, count)

    private fun mentionNodes() =
        composeRule.onAllNodesWithContentDescription(
            context.getString(R.string.conversation_jump_to_mention),
            substring = true,
            useUnmergedTree = true,
        )

    private fun controller(
        appState: WhiteNoiseAppState,
        groupId: String,
        mentions: ULong,
    ) = ConversationController(
        appState = appState,
        initialGroup = group(groupId),
        initialChatListRow = row(groupId, mentions),
    ).apply {
        // The first native page has published but no row is anchored yet: the transcript's controls are live
        // while the timeline-derived mention targets are still unknown.
        markAuthoritativeTimelinePublishedForTest()
    }

    private fun appState() =
        WhiteNoiseAppState(
            context = context,
            draftStore = DraftStore(EmptyDraftPersistence),
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
                        unlinked = false,
                    ),
                ),
            activeAccountRef = ACCOUNT_REF,
        )

    /** A busy chat: dozens of unread rows after the read anchor, [mentions] of them mentioning this account. */
    private fun row(
        groupId: String,
        mentions: ULong,
    ) = ChatListRowFfi(
        selfMembership = SelfMembershipFfi.MEMBER,
        unreadMentionCount = mentions,
        unreadMention = mentions > 0uL,
        groupIdHex = groupId,
        archived = false,
        pendingConfirmation = false,
        title = "Busy group",
        groupName = "Busy group",
        avatarUrl = null,
        avatar = null,
        lastMessage = null,
        unreadCount = 60uL,
        hasUnread = true,
        firstUnreadMessageIdHex = "f1".repeat(32),
        lastReadMessageIdHex = "e1".repeat(32),
        lastReadTimelineAt = 1uL,
        conversationCreatedAt = 1uL,
        activitySortAt = 2uL,
        updatedAt = 2uL,
        leaveRequestPending = false,
        leaveRequestedAtMs = null,
        leaveBlocked = false,
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

    private fun group(groupId: String) =
        AppGroupRecordFfi(
            groupIdHex = groupId,
            protocolProfile = AppProtocolProfileFfi.LEGACY,
            endpoint = "wss://relay.example",
            profilePresent = true,
            name = "Busy group",
            description = "",
            admins = listOf(ACCOUNT_ID),
            relays = emptyList(),
            nostrGroupIdHex = "03".repeat(32),
            avatarUrl = null,
            avatarDim = null,
            avatarThumbhash = null,
            imageHashHex = null,
            encryptedMedia =
                AppGroupEncryptedMediaComponentFfi(
                    componentId = 0x8008u,
                    component = "marmot.group.encrypted-media.v1",
                    required = true,
                    version = EncryptedMediaVersionFfi.V1,
                    mediaFormat = "encrypted-media-v1",
                    allowedLocatorKinds = listOf("blossom-v1"),
                    defaultBlobEndpoints =
                        listOf(AppBlobEndpointFfi(locatorKind = "blossom-v1", baseUrl = "https://blossom.example")),
                ),
            disappearingMessageSecs = 0uL,
            archived = false,
            pendingConfirmation = false,
            unrecoverable = false,
            selfMembership = SelfMembershipFfi.MEMBER,
            leaveRequestPending = false,
            leaveRequestedAtMs = null,
            leaveBlocked = false,
            disbanding = false,
            disbandRequest = null,
            disbanded = false,
            welcomerAccountIdHex = null,
            viaWelcomeMessageIdHex = null,
        )

    private object EmptyDraftPersistence : DraftPersistence {
        override fun read(): Map<String, String> = emptyMap()

        override fun write(
            key: String,
            value: String?,
        ) = Unit
    }

    private companion object {
        const val ACCOUNT_REF = "alice"
        val ACCOUNT_ID = "a1".repeat(32)
        val GROUP_ID = "01".repeat(32)
        val OTHER_GROUP_ID = "02".repeat(32)
    }
}
