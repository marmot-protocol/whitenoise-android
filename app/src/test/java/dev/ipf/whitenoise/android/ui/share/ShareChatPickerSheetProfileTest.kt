package dev.ipf.whitenoise.android.ui.share

import android.content.Context
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotDisplayed
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextClearance
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
import dev.ipf.whitenoise.android.state.BoundedNpubCache
import dev.ipf.whitenoise.android.state.ChatsController
import dev.ipf.whitenoise.android.state.DraftPersistence
import dev.ipf.whitenoise.android.state.DraftStore
import dev.ipf.whitenoise.android.state.WhiteNoiseAppState
import dev.ipf.whitenoise.android.ui.theme.WhiteNoiseTheme
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
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
        val profiles = mutableMapOf<String, UserProfileMetadataFfi>()
        val appState = appStateWithDirectChat(GROUP_A, PEER_A, profiles = profiles)

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

        profiles[PEER_A] =
            UserProfileMetadataFfi(
                name = "alice_dev",
                displayName = "Alice Example",
                about = null,
                picture = null,
                nip05 = "alice@example.com",
                lud16 = null,
            )
        refreshProfile(appState, PEER_A)
        composeRule.waitForIdle()

        composeRule.onNodeWithText("Alice Example").assertIsDisplayed()
        composeRule.onNodeWithText(app.getString(R.string.share_no_matches)).assertIsNotDisplayed()
    }

    @Test
    fun aliasOnlyProfileUpdateRefreshesActiveSearch() {
        val profiles = mutableMapOf(PEER_A to profile(displayName = "Alice Example", name = "old_alias"))
        val appState = appStateWithDirectChat(GROUP_A, PEER_A, profiles = profiles)

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
            .performTextInput("new_alias")
        composeRule.onNodeWithText(app.getString(R.string.share_no_matches)).assertIsDisplayed()

        profiles[PEER_A] = profile(displayName = "Alice Example", name = "new_alias")
        refreshProfile(appState, PEER_A)
        composeRule.waitForIdle()

        composeRule.onNodeWithText("Alice Example").assertIsDisplayed()
        composeRule.onNodeWithText(app.getString(R.string.share_no_matches)).assertIsNotDisplayed()
    }

    @Test
    fun localProfileMaterializesWhenRelayRefreshFails() {
        val profiles = mutableMapOf(PEER_A to profile(displayName = "Alice\u202E Example"))
        var refreshAttempts = 0
        val appState =
            appStateWithDirectChat(
                GROUP_A,
                PEER_A,
                profiles = profiles,
                profileRefresh = {
                    refreshAttempts += 1
                    error("relay unavailable")
                },
            )

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

        composeRule.waitUntil(timeoutMillis = 5_000) {
            composeRule.onAllNodesWithText("Alice Example").fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.waitUntil(timeoutMillis = 5_000) { refreshAttempts > 0 }
        composeRule.onNodeWithText("Alice Example").assertIsDisplayed()
    }

    @Test
    fun cachedPresentationNameResolvesWithoutProfileMetadata() {
        val appState =
            emptyAppState(
                profileDisplayName = { accountIdHex ->
                    "Alice Example".takeIf { accountIdHex == PEER_A }
                },
            )
        val controller = ChatsController(appState, ACCOUNT_REF) { _, _ -> emptyList() }
        controller.applyChatListRow(chatRow(GROUP_A))
        controller.applyLocalGroupDetails(
            record = group(GROUP_A),
            members = listOf(member(ACCOUNT_HEX, local = true), member(PEER_A, local = false)),
        )
        appState.attachChatsController(controller)

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

        composeRule.waitUntil(timeoutMillis = 5_000) {
            composeRule.onAllNodesWithText("Alice Example").fetchSemanticsNodes().isNotEmpty()
        }
        composeRule
            .onNodeWithText(app.getString(R.string.share_search_chats))
            .performClick()
            .performTextInput("Alice")
        composeRule.onNodeWithText(app.getString(R.string.share_no_matches)).assertIsNotDisplayed()
    }

    @Test
    fun shortIdentityFragmentsDoNotMatchUnresolvedProfiles() {
        val appState = appStateWithDirectChat(GROUP_A, PEER_A)
        val fallbackTitle = appState.shortNpub(PEER_A)

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
        composeRule.onNodeWithText(fallbackTitle).assertIsDisplayed()

        val search = composeRule.onNodeWithText(app.getString(R.string.share_search_chats))
        search.performClick().performTextInput("b1")

        composeRule.onNodeWithText(app.getString(R.string.share_no_matches)).assertIsDisplayed()

        composeRule.onNode(hasSetTextAction()).performTextClearance()
        composeRule.onNode(hasSetTextAction()).performTextInput("b1b1b1b1")
        composeRule.onNodeWithText(fallbackTitle).assertIsDisplayed()
    }

    @Test
    fun npubSearchRequiresFullIdentityThreshold() {
        assertFalse(looksLikeShareIdentityNeedle("npub1"))
        assertTrue(looksLikeShareIdentityNeedle("npub1abc"))
    }

    @Test
    fun stagingRequiresTheAccountThatOpenedThePicker() {
        assertTrue(sharePickerAccountStillActive("account-a", "account-a"))
        assertFalse(sharePickerAccountStillActive("account-a", "account-b"))
        assertFalse(sharePickerAccountStillActive(null, "account-a"))
    }

    @Test
    fun visibleMultiMemberTitleRemainsSearchableWhenMemberProfilesResolve() {
        val profiles =
            mutableMapOf(
                PEER_A to profile(displayName = "Alice"),
                PEER_B to profile(displayName = "Bob"),
            )
        val appState = emptyAppState(profiles = profiles)
        val controller = ChatsController(appState, ACCOUNT_REF) { _, _ -> emptyList() }
        controller.applyChatListRow(chatRow(GROUP_A))
        controller.applyLocalGroupDetails(
            record = group(GROUP_A),
            members =
                listOf(
                    member(ACCOUNT_HEX, local = true),
                    member(PEER_A, local = false),
                    member(PEER_B, local = false),
                ),
        )
        appState.attachChatsController(controller)
        val visibleTitle = app.getString(R.string.group_title_people_count, 3)

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

        composeRule.onNodeWithText(visibleTitle).assertIsDisplayed()
        composeRule
            .onNodeWithText(app.getString(R.string.share_search_chats))
            .performClick()
            .performTextInput(visibleTitle)

        composeRule.onAllNodesWithText(visibleTitle).assertCountEquals(2)
        composeRule.onNodeWithText(app.getString(R.string.share_no_matches)).assertIsNotDisplayed()
    }

    @Test
    fun profileArrivalInvalidatesOnlyItsAccountAliases() {
        val profiles = mutableMapOf<String, UserProfileMetadataFfi>()
        val appState = emptyAppState(profiles = profiles)
        val peerABefore = appState.profileAccountRevisionForCompose(PEER_A)
        val peerBBefore = appState.profileAccountRevisionForCompose(PEER_B)

        profiles[PEER_A] = profile(displayName = "Alice")
        refreshProfile(appState, PEER_A)

        assertNotEquals(peerABefore, appState.profileAccountRevisionForCompose(PEER_A))
        assertEquals(peerBBefore, appState.profileAccountRevisionForCompose(PEER_B))
    }

    @Test
    fun unresolvedDirectTargetRequestsRosterAndResolvesPeer() {
        val profiles = mutableMapOf(PEER_A to profile(displayName = "Alice Example"))
        var rosterRequests = 0
        val appState = emptyAppState(profiles = profiles)
        val controller =
            ChatsController(appState, ACCOUNT_REF) { _, groupId ->
                rosterRequests += 1
                check(groupId == GROUP_A)
                listOf(member(ACCOUNT_HEX, local = true), member(PEER_A, local = false))
            }
        controller.applyChatListRow(chatRow(GROUP_A))
        controller.applyLocalGroupUpdate(group(GROUP_A))
        appState.attachChatsController(controller)

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

        composeRule.waitUntil(timeoutMillis = 5_000) { rosterRequests == 1 }
        composeRule.waitUntil(timeoutMillis = 5_000) {
            composeRule.onAllNodesWithText("Alice Example").fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithText("Alice Example").assertIsDisplayed()
    }

    @Test
    fun invalidatedRosterFetchRetriesWhileChatListIsHidden() {
        val profiles = mutableMapOf(PEER_A to profile(displayName = "Alice Example"))
        val firstRosterStarted = CompletableDeferred<Unit>()
        val releaseFirstRoster = CompletableDeferred<Unit>()
        var rosterRequests = 0
        val appState = emptyAppState(profiles = profiles)
        val controller =
            ChatsController(appState, ACCOUNT_REF) { _, groupId ->
                check(groupId == GROUP_A)
                rosterRequests += 1
                if (rosterRequests == 1) {
                    firstRosterStarted.complete(Unit)
                    releaseFirstRoster.await()
                }
                listOf(member(ACCOUNT_HEX, local = true), member(PEER_A, local = false))
            }
        controller.applyChatListRow(chatRow(GROUP_A))
        controller.applyLocalGroupUpdate(group(GROUP_A))
        controller.setChatListVisible(false)
        appState.attachChatsController(controller)

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

        composeRule.waitUntil(timeoutMillis = 5_000) { firstRosterStarted.isCompleted }
        composeRule.runOnIdle {
            controller.applyLocalGroupUpdate(group(GROUP_A))
            releaseFirstRoster.complete(Unit)
        }
        composeRule.waitUntil(timeoutMillis = 5_000) { rosterRequests >= 2 }
        composeRule.waitUntil(timeoutMillis = 5_000) {
            composeRule.onAllNodesWithText("Alice Example").fetchSemanticsNodes().isNotEmpty()
        }
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
        val profiles = mutableMapOf<String, UserProfileMetadataFfi>()
        val appState =
            appStateWithDirectChats(
                GROUP_A to PEER_A,
                GROUP_B to PEER_B,
                profiles = profiles,
            )
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

        profiles[PEER_A] = profile(displayName = "Alex")
        profiles[PEER_B] = profile(displayName = "Alex")
        refreshProfile(appState, PEER_A)
        refreshProfile(appState, PEER_B)
        composeRule.waitForIdle()

        composeRule.onAllNodesWithText("Alex").assertCountEquals(2)
        composeRule
            .onNodeWithText(app.resources.getQuantityString(R.plurals.share_to_chats_count, 1, 1))
            .performClick()
        composeRule.runOnIdle { assertEquals(listOf(GROUP_A), staged) }
    }

    @Test
    fun lateProfileResolutionDoesNotReorderTargetsByResolvedTitle() {
        val profiles = mutableMapOf<String, UserProfileMetadataFfi>()
        val appState =
            appStateWithDirectChats(
                GROUP_A to PEER_A,
                GROUP_B to PEER_B,
                profiles = profiles,
            )

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
        profiles[PEER_A] = profile(displayName = "Zebra")
        profiles[PEER_B] = profile(displayName = "Alpha")
        refreshProfile(appState, PEER_A)
        refreshProfile(appState, PEER_B)
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
        profiles: MutableMap<String, UserProfileMetadataFfi> = mutableMapOf(),
        profileRefresh: suspend (String) -> Unit = {},
    ): WhiteNoiseAppState =
        appStateWithDirectChats(
            groupId to peerId,
            profiles = profiles,
            profileRefresh = profileRefresh,
        )

    private fun appStateWithDirectChats(
        vararg chats: Pair<String, String>,
        profiles: MutableMap<String, UserProfileMetadataFfi> = mutableMapOf(),
        profileRefresh: suspend (String) -> Unit = {},
    ): WhiteNoiseAppState {
        val appState = emptyAppState(profiles = profiles, profileRefresh = profileRefresh)
        val controller = ChatsController(appState, ACCOUNT_REF) { _, _ -> emptyList() }
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
        val appState = emptyAppState()
        val controller = ChatsController(appState, ACCOUNT_REF) { _, _ -> emptyList() }
        controller.applyChatListRow(chatRow(groupId))
        appState.attachChatsController(controller)
        return appState
    }

    private fun emptyAppState(
        profiles: MutableMap<String, UserProfileMetadataFfi> = mutableMapOf(),
        profileRefresh: suspend (String) -> Unit = {},
        profileDisplayName: suspend (String) -> String? = { profiles[it]?.displayName },
    ): WhiteNoiseAppState {
        val appState =
            WhiteNoiseAppState(
                context = app,
                draftStore = DraftStore(InMemoryDraftPersistence()),
                accountIdHexResolver = { null },
                accounts = listOf(activeAccount()),
                activeAccountRef = ACCOUNT_REF,
                profileReader = { profiles[it] },
                profileDisplayNameReader = profileDisplayName,
                profileRefreshRequest = profileRefresh,
            )
        seedTestNpub(appState, PEER_A, NPUB_PEER_A)
        seedTestNpub(appState, PEER_B, NPUB_PEER_B)
        return appState
    }

    private fun seedTestNpub(
        appState: WhiteNoiseAppState,
        accountIdHex: String,
        npub: String,
    ) {
        val field = WhiteNoiseAppState::class.java.getDeclaredField("npubs")
        field.isAccessible = true
        val cache = field.get(appState) as BoundedNpubCache
        cache.put(accountIdHex, npub)
    }

    private fun refreshProfile(
        appState: WhiteNoiseAppState,
        accountIdHex: String,
    ) {
        composeRule.runOnIdle {
            CoroutineScope(Dispatchers.Main.immediate).launch {
                appState.refreshProfile(accountIdHex)
            }
        }
    }

    private fun profile(
        displayName: String,
        name: String = displayName.lowercase(),
    ) = UserProfileMetadataFfi(
        name = name,
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
        const val NPUB_PEER_A = "npub1aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
        const val NPUB_PEER_B = "npub1zzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzz"
        val ACCOUNT_HEX = "a0".repeat(32)
        val PEER_A = "b1".repeat(32)
        val PEER_B = "b2".repeat(32)
        val GROUP_A = "c2".repeat(32)
        val GROUP_B = "c3".repeat(32)
    }
}
