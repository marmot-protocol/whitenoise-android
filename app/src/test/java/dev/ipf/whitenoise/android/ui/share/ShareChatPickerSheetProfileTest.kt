package dev.ipf.whitenoise.android.ui.share

import android.content.Context
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.test.core.app.ApplicationProvider
import dev.ipf.marmotkit.AccountSummaryFfi
import dev.ipf.marmotkit.AppBlobEndpointFfi
import dev.ipf.marmotkit.AppGroupEncryptedMediaComponentFfi
import dev.ipf.marmotkit.AppGroupMemberRecordFfi
import dev.ipf.marmotkit.AppGroupRecordFfi
import dev.ipf.marmotkit.ChatConversationKindFfi
import dev.ipf.marmotkit.ChatListRowFfi
import dev.ipf.marmotkit.EncryptedMediaVersionFfi
import dev.ipf.marmotkit.SelfMembershipFfi
import dev.ipf.marmotkit.UserProfileMetadataFfi
import dev.ipf.whitenoise.android.R
import dev.ipf.whitenoise.android.core.IdentityFormatter
import dev.ipf.whitenoise.android.share.SharePayload
import dev.ipf.whitenoise.android.state.ChatsController
import dev.ipf.whitenoise.android.state.DraftPersistence
import dev.ipf.whitenoise.android.state.DraftStore
import dev.ipf.whitenoise.android.state.WhiteNoiseAppState
import dev.ipf.whitenoise.android.ui.theme.WhiteNoiseTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "w360dp-h780dp-mdpi")
class ShareChatPickerSheetProfileTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    private val app = ApplicationProvider.getApplicationContext<Context>()
    private val payload =
        SharePayload(
            text = "shared text",
            streamUris = emptyList(),
            intentMimeType = "text/plain",
        )

    @Test
    fun lateProfilePresentationRefreshesVisibleTitleAndActiveSearch() {
        val appState = appStateWithDirectChat(groupId = GROUP_A, peerId = PEER_A)
        val fallback = appState.shortNpub(PEER_A)

        composeRule.setContent {
            WhiteNoiseTheme(darkTheme = true) {
                ShareChatPickerSheet(
                    appState = appState,
                    payload = payload,
                    onDismiss = {},
                    onStage = {},
                )
            }
        }
        composeRule.waitForIdle()

        composeRule.onNodeWithText(fallback).assertIsDisplayed()
        composeRule.onNodeWithText(app.getString(R.string.share_search_chats)).performClick().performTextInput("Alice")
        composeRule.onNodeWithText(app.getString(R.string.share_no_matches)).assertIsDisplayed()

        composeRule.runOnIdle { appState.setContactNickname(PEER_A, "Alice") }
        composeRule.waitForIdle()

        composeRule.onAllNodesWithText("Alice").assertCountEquals(2)
        composeRule.onNodeWithText(app.getString(R.string.share_no_matches)).assertIsNotDisplayed()
    }

    @Test
    fun lateProfileUsernameBecomesSearchableWhileDisplayingPreferredName() {
        val appState = appStateWithDirectChat(GROUP_A, PEER_A)

        composeRule.setContent {
            WhiteNoiseTheme(darkTheme = true) {
                ShareChatPickerSheet(
                    appState = appState,
                    payload = payload,
                    onDismiss = {},
                    onStage = {},
                )
            }
        }
        composeRule.waitForIdle()
        composeRule
            .onNodeWithText(app.getString(R.string.share_search_chats))
            .performClick()
            .performTextInput("alice_dev")
        composeRule.onNodeWithText(app.getString(R.string.share_no_matches)).assertIsDisplayed()

        composeRule.runOnIdle {
            deliverProfile(
                appState = appState,
                accountIdHex = PEER_A,
                profile =
                    UserProfileMetadataFfi(
                        name = "alice_dev",
                        displayName = "Alice Example",
                        about = null,
                        picture = null,
                        nip05 = "alice@example.com",
                        lud16 = null,
                    ),
            )
        }
        composeRule.waitForIdle()

        composeRule.onNodeWithText("Alice Example").assertIsDisplayed()
        composeRule.onNodeWithText(app.getString(R.string.share_no_matches)).assertIsNotDisplayed()
    }

    @Test
    fun unresolvedTargetUsesStableConversationFallback() {
        val appState = appStateWithUnresolvedChat(GROUP_A)
        val fallback = "${app.getString(R.string.unknown)} · ${IdentityFormatter.short(GROUP_A)}"

        composeRule.setContent {
            WhiteNoiseTheme(darkTheme = true) {
                ShareChatPickerSheet(
                    appState = appState,
                    payload = payload,
                    onDismiss = {},
                    onStage = {},
                )
            }
        }

        composeRule.onNodeWithText(fallback).assertIsDisplayed()
    }

    @Test
    fun duplicateResolvedNamesPreserveSelectionByGroupId() {
        val appState = appStateWithDirectChats(GROUP_A to PEER_A, GROUP_B to PEER_B)
        val firstFallback = appState.shortNpub(PEER_A)
        var staged = emptyList<String>()

        composeRule.setContent {
            WhiteNoiseTheme(darkTheme = true) {
                ShareChatPickerSheet(
                    appState = appState,
                    payload = payload,
                    onDismiss = {},
                    onStage = { staged = it },
                )
            }
        }
        composeRule.waitForIdle()
        composeRule.onNodeWithText(firstFallback).performClick()

        composeRule.runOnIdle {
            deliverProfile(appState, PEER_A, profile(displayName = "Alex"))
            deliverProfile(appState, PEER_B, profile(displayName = "Alex"))
        }
        composeRule.waitForIdle()

        composeRule.onAllNodesWithText("Alex").assertCountEquals(2)
        composeRule
            .onNodeWithText(app.resources.getQuantityString(R.plurals.share_to_chats_count, 1, 1))
            .performClick()
        composeRule.runOnIdle { assertEquals(listOf(GROUP_A), staged) }
    }

    @Test
    fun lateProfileResolutionDoesNotReorderTargetsByResolvedTitle() {
        val appState = appStateWithDirectChats(GROUP_A to PEER_A, GROUP_B to PEER_B)

        composeRule.setContent {
            WhiteNoiseTheme(darkTheme = true) {
                ShareChatPickerSheet(
                    appState = appState,
                    payload = payload,
                    onDismiss = {},
                    onStage = {},
                )
            }
        }
        composeRule.runOnIdle {
            deliverProfile(appState, PEER_A, profile(displayName = "Zebra"))
            deliverProfile(appState, PEER_B, profile(displayName = "Alpha"))
        }
        composeRule.waitForIdle()

        val zebraTop =
            composeRule
                .onNodeWithText("Zebra")
                .fetchSemanticsNode()
                .boundsInRoot.top
        val alphaTop =
            composeRule
                .onNodeWithText("Alpha")
                .fetchSemanticsNode()
                .boundsInRoot.top
        assertTrue("Profile presentation must not reorder target identity", zebraTop < alphaTop)
    }

    private fun appStateWithDirectChat(
        groupId: String,
        peerId: String,
    ): WhiteNoiseAppState = appStateWithDirectChats(groupId to peerId)

    private fun appStateWithDirectChats(vararg chats: Pair<String, String>): WhiteNoiseAppState {
        val appState =
            WhiteNoiseAppState(
                context = app,
                draftStore = DraftStore(InMemoryDraftPersistence()),
                accountIdHexResolver = { null },
                accounts = listOf(activeAccount()),
                activeAccountRef = ACCOUNT_REF,
            )
        val controller = ChatsController(appState)
        bindAccount(controller)
        chats.forEach { (groupId, _) -> controller.applyChatListRow(chatRow(groupId)) }
        chats.forEach { (groupId, peerId) ->
            controller.applyLocalGroupDetails(
                record = group(groupId),
                members = listOf(member(ACCOUNT_HEX, local = true), member(peerId, local = false)),
            )
        }
        appState.attachChatsController(controller)
        return appState
    }

    private fun appStateWithUnresolvedChat(groupId: String): WhiteNoiseAppState {
        val appState =
            WhiteNoiseAppState(
                context = app,
                draftStore = DraftStore(InMemoryDraftPersistence()),
                accountIdHexResolver = { null },
                accounts = listOf(activeAccount()),
                activeAccountRef = ACCOUNT_REF,
            )
        val controller = ChatsController(appState)
        bindAccount(controller)
        controller.applyChatListRow(chatRow(groupId))
        appState.attachChatsController(controller)
        return appState
    }

    private fun bindAccount(controller: ChatsController) {
        val field = ChatsController::class.java.getDeclaredField("accountRef").apply { isAccessible = true }
        field.set(controller, ACCOUNT_REF)
    }

    private fun deliverProfile(
        appState: WhiteNoiseAppState,
        accountIdHex: String,
        profile: UserProfileMetadataFfi,
    ) {
        val presentationClass = Class.forName("dev.ipf.whitenoise.android.state.ProfilePresentation")
        val presentation =
            presentationClass
                .getDeclaredConstructor(String::class.java, String::class.java)
                .apply { isAccessible = true }
                .newInstance(profile.displayName, profile.picture)
        WhiteNoiseAppState::class.java
            .getDeclaredMethod(
                "applyProfilePresentation",
                String::class.java,
                UserProfileMetadataFfi::class.java,
                presentationClass,
            ).apply { isAccessible = true }
            .invoke(appState, accountIdHex, profile, presentation)
    }

    private fun profile(displayName: String) =
        UserProfileMetadataFfi(
            name = displayName.lowercase(),
            displayName = displayName,
            about = null,
            picture = null,
            nip05 = null,
            lud16 = null,
        )

    private fun activeAccount() =
        AccountSummaryFfi(
            label = ACCOUNT_REF,
            accountIdHex = ACCOUNT_HEX,
            localSigning = true,
            externalSigning = false,
            signedOut = false,
            running = true,
        )

    private fun member(
        id: String,
        local: Boolean,
    ) = AppGroupMemberRecordFfi(memberIdHex = id, account = id, local = local)

    private fun group(groupId: String) =
        AppGroupRecordFfi(
            selfMembership = SelfMembershipFfi.MEMBER,
            groupIdHex = groupId,
            protocolProfile = dev.ipf.marmotkit.AppProtocolProfileFfi.LEGACY,
            profilePresent = false,
            endpoint = "endpoint-$groupId",
            name = "",
            description = "",
            admins = emptyList(),
            relays = listOf("wss://relay.example"),
            nostrGroupIdHex = "nostr-$groupId",
            avatarUrl = null,
            avatarDim = null,
            avatarThumbhash = null,
            imageHashHex = null,
            encryptedMedia = encryptedMedia(),
            archived = false,
            pendingConfirmation = false,
            unrecoverable = false,
            welcomerAccountIdHex = null,
            viaWelcomeMessageIdHex = null,
            disappearingMessageSecs = 0uL,
            leaveRequestPending = false,
            leaveRequestedAtMs = null,
            disbanding = false,
            disbanded = false,
            disbandRequest = null,
        )

    private fun chatRow(groupId: String) =
        ChatListRowFfi(
            selfMembership = SelfMembershipFfi.MEMBER,
            unreadMentionCount = 0uL,
            unreadMention = false,
            groupIdHex = groupId,
            archived = false,
            pendingConfirmation = false,
            title = groupId,
            groupName = "",
            avatarUrl = null,
            avatar = null,
            lastMessage = null,
            unreadCount = 0uL,
            hasUnread = false,
            firstUnreadMessageIdHex = null,
            lastReadMessageIdHex = null,
            lastReadTimelineAt = null,
            conversationCreatedAt = 0uL,
            activitySortAt = 0uL,
            updatedAt = 0uL,
            leaveRequestPending = false,
            leaveRequestedAtMs = null,
            manuallyMarkedUnread = false,
            conversationKind = ChatConversationKindFfi.DIRECT,
            muted = false,
            mutedUntilMs = null,
            pinned = false,
            pinnedPosition = null,
            lifecycleState = dev.ipf.marmotkit.GroupLifecycleStateFfi.STABLE,
            disbanding = false,
            disbandRequest = null,
        )

    private fun encryptedMedia() =
        AppGroupEncryptedMediaComponentFfi(
            componentId = 0x8008u,
            component = "marmot.group.encrypted-media.v1",
            required = true,
            version = EncryptedMediaVersionFfi.V1,
            mediaFormat = "encrypted-media-v1",
            allowedLocatorKinds = listOf("blossom-v1"),
            defaultBlobEndpoints =
                listOf(
                    AppBlobEndpointFfi(
                        locatorKind = "blossom-v1",
                        baseUrl = "https://blossom.example",
                    ),
                ),
        )

    private class InMemoryDraftPersistence : DraftPersistence {
        override fun read(): Map<String, String> = emptyMap()

        override fun write(
            key: String,
            value: String?,
        ) = Unit
    }

    private companion object {
        const val ACCOUNT_REF = "alice"
        val ACCOUNT_HEX = "a0".repeat(32)
        val PEER_A = "b1".repeat(32)
        val PEER_B = "b2".repeat(32)
        val GROUP_A = "c2".repeat(32)
        val GROUP_B = "c3".repeat(32)
    }
}
