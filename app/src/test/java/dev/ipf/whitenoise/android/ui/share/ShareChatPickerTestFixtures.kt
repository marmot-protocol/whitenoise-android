package dev.ipf.whitenoise.android.ui.share

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import dev.ipf.marmotkit.AccountSummaryFfi
import dev.ipf.marmotkit.AppBlobEndpointFfi
import dev.ipf.marmotkit.AppGroupEncryptedMediaComponentFfi
import dev.ipf.marmotkit.AppGroupMemberRecordFfi
import dev.ipf.marmotkit.AppGroupRecordFfi
import dev.ipf.marmotkit.AppProtocolProfileFfi
import dev.ipf.marmotkit.ChatConversationKindFfi
import dev.ipf.marmotkit.ChatListRowFfi
import dev.ipf.marmotkit.EncryptedMediaVersionFfi
import dev.ipf.marmotkit.GroupLifecycleStateFfi
import dev.ipf.marmotkit.SelfMembershipFfi
import dev.ipf.marmotkit.UserProfileMetadataFfi
import dev.ipf.whitenoise.android.state.ChatsController
import dev.ipf.whitenoise.android.state.DraftPersistence
import dev.ipf.whitenoise.android.state.DraftStore
import dev.ipf.whitenoise.android.state.WhiteNoiseAppState

internal const val ACCOUNT_REF = "alice"
internal val ACCOUNT_HEX = "a0".repeat(32)
internal val PEER_A = "b1".repeat(32)
internal val PEER_B = "b2".repeat(32)
internal val GROUP_A = "c2".repeat(32)
internal val GROUP_B = "c3".repeat(32)

internal fun appStateWithDirectChat(
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

internal fun appStateWithDirectChats(
    vararg chats: Pair<String, String>,
    profiles: MutableMap<String, UserProfileMetadataFfi> = mutableMapOf(),
    profileRefresh: suspend (String) -> Unit = {},
    accounts: List<AccountSummaryFfi> = listOf(testAccount(ACCOUNT_REF, ACCOUNT_HEX)),
    activeAccountRef: String = ACCOUNT_REF,
): WhiteNoiseAppState {
    val appState =
        emptyAppState(
            profiles = profiles,
            profileRefresh = profileRefresh,
            accounts = accounts,
            activeAccountRef = activeAccountRef,
        )
    val activeAccountHex = accounts.first { it.label == activeAccountRef }.accountIdHex
    val controller = ChatsController(appState, activeAccountRef) { _, _ -> emptyList() }
    chats.forEach { (groupId, _) -> controller.applyChatListRow(chatRow(groupId)) }
    chats.forEach { (groupId, peerId) ->
        controller.applyLocalGroupDetails(
            record = group(groupId),
            members = listOf(member(activeAccountHex, local = true), member(peerId, local = false)),
        )
    }
    appState.attachChatsController(controller)
    return appState
}

/** Seeds one resolved direct chat into a controller bound to [selfAccountIdHex]'s owner. */
internal fun ChatsController.applyLocalDirectChat(
    groupId: String,
    selfAccountIdHex: String,
    peerId: String,
) {
    applyChatListRow(chatRow(groupId))
    applyLocalGroupDetails(
        record = group(groupId),
        members = listOf(member(selfAccountIdHex, local = true), member(peerId, local = false)),
    )
}

/** Builds an app state whose single chat has no resolved members. */
internal fun appStateWithUnresolvedChat(groupId: String): WhiteNoiseAppState {
    val appState = emptyAppState()
    val controller = ChatsController(appState, ACCOUNT_REF) { _, _ -> emptyList() }
    controller.applyChatListRow(chatRow(groupId))
    appState.attachChatsController(controller)
    return appState
}

internal fun emptyAppState(
    profiles: MutableMap<String, UserProfileMetadataFfi> = mutableMapOf(),
    profileRefresh: suspend (String) -> Unit = {},
    profileDisplayName: suspend (String) -> String? = { profiles[it]?.displayName },
    accounts: List<AccountSummaryFfi> = listOf(testAccount(ACCOUNT_REF, ACCOUNT_HEX)),
    activeAccountRef: String = ACCOUNT_REF,
): WhiteNoiseAppState =
    WhiteNoiseAppState(
        context = ApplicationProvider.getApplicationContext<Context>(),
        draftStore = DraftStore(InMemoryDraftPersistence()),
        accountIdHexResolver = { null },
        accounts = accounts,
        activeAccountRef = activeAccountRef,
        profileReader = { profiles[it] },
        profileDisplayNameReader = profileDisplayName,
        profileRefreshRequest = profileRefresh,
    )

internal fun profile(
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

internal fun testAccount(
    label: String,
    accountIdHex: String,
) = AccountSummaryFfi(
    label = label,
    accountIdHex = accountIdHex,
    localSigning = true,
    externalSigning = false,
    signedOut = false,
    running = true,
)

internal fun member(
    id: String,
    local: Boolean,
) = AppGroupMemberRecordFfi(memberIdHex = id, account = id, local = local)

internal fun group(groupId: String) =
    AppGroupRecordFfi(
        selfMembership = SelfMembershipFfi.MEMBER,
        groupIdHex = groupId,
        protocolProfile = AppProtocolProfileFfi.LEGACY,
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

internal fun chatRow(groupId: String) =
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
        lifecycleState = GroupLifecycleStateFfi.STABLE,
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
