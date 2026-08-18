package dev.ipf.whitenoise.android.state

import android.content.Context
import android.graphics.Color
import androidx.test.core.app.ApplicationProvider
import dev.ipf.marmotkit.AccountSummaryFfi
import dev.ipf.marmotkit.AppBlobEndpointFfi
import dev.ipf.marmotkit.AppGroupEncryptedMediaComponentFfi
import dev.ipf.marmotkit.AppGroupMemberRecordFfi
import dev.ipf.marmotkit.AppGroupRecordFfi
import dev.ipf.marmotkit.AppProtocolProfileFfi
import dev.ipf.marmotkit.ChatConversationKindFfi
import dev.ipf.marmotkit.ChatListAvatarFfi
import dev.ipf.marmotkit.ChatListRowFfi
import dev.ipf.marmotkit.EncryptedMediaVersionFfi
import dev.ipf.marmotkit.GroupLifecycleStateFfi
import dev.ipf.marmotkit.SelfMembershipFfi
import dev.ipf.marmotkit.UserProfileMetadataFfi
import dev.ipf.whitenoise.android.core.AvatarImageLoader
import dev.ipf.whitenoise.android.core.GroupAvatarImageLoader
import dev.ipf.whitenoise.android.core.encryptedGroupAvatarCacheKey
import dev.ipf.whitenoise.android.ui.chats.AvatarScreenshotFixtures
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36])
@OptIn(ExperimentalCoroutinesApi::class)
class ChatListFirstFrameAvatarPublicationTest {
    @Before
    fun setUp() {
        AvatarImageLoader.clear()
        AvatarImageLoader.resetProfileImageFetcherForTests()
        GroupAvatarImageLoader.clear()
    }

    @After
    fun tearDown() {
        AvatarImageLoader.clear()
        AvatarImageLoader.resetProfileImageFetcherForTests()
        GroupAvatarImageLoader.clear()
    }

    @Test
    fun locallyDecodedLegacyAvatarIsAttachedBeforeItemsPublish() =
        runTest {
            val image = AvatarScreenshotFixtures.distinctAvatarBitmap(Color.RED)
            AvatarImageLoader.putCached(LEGACY_AVATAR_URL, image)

            val controller = bindController(group(avatarUrl = LEGACY_AVATAR_URL))

            val seed = controller.items.single().firstFrameAvatar
            assertEquals(ChatListAvatarSource.LEGACY_URL, seed?.source)
            assertEquals(LEGACY_AVATAR_URL, seed?.key)
            assertSame(image, seed?.image)
        }

    @Test
    fun locallyDecodedEncryptedAvatarIsAttachedBeforeItemsPublish() =
        runTest {
            val image = AvatarScreenshotFixtures.distinctAvatarBitmap(Color.RED)
            val cacheKey = encryptedGroupAvatarCacheKey(ACCOUNT_REF, GROUP_ID, IMAGE_HASH)
            GroupAvatarImageLoader.putCached(cacheKey, image)

            val controller = bindController(group(imageHashHex = IMAGE_HASH))

            val item = controller.items.single()
            assertEquals(IMAGE_HASH, item.group.imageHashHex)
            assertSame(image, GroupAvatarImageLoader.peek(cacheKey))
            val seed = item.firstFrameAvatar
            assertEquals(ChatListAvatarSource.ENCRYPTED_GROUP, seed?.source)
            assertEquals(cacheKey, seed?.key)
            assertSame(image, seed?.image)
        }

    @Test
    fun locallyDecodedDmFallbackAvatarIsAttachedBeforeItemsPublish() =
        runTest {
            val image = AvatarScreenshotFixtures.distinctAvatarBitmap(Color.RED)
            val appState = appState(profileReader = { peerProfile() })
            appState.warmProfilePresentationsBlocking(listOf(PEER_ID))
            AvatarImageLoader.putCached(PEER_AVATAR, image)

            val controller = bindDmController(appState)

            val seed = controller.items.single().firstFrameAvatar
            assertEquals(ChatListAvatarSource.FALLBACK_URL, seed?.source)
            assertEquals(PEER_AVATAR, seed?.key)
            assertSame(image, seed?.image)
        }

    @Test
    fun networkMissPublishesWithoutWaitingForAvatarFetch() =
        runTest {
            val releaseFetch = CompletableDeferred<Unit>()
            AvatarImageLoader.attachProfileImageFetcher { _, _ ->
                releaseFetch.await()
                AvatarScreenshotFixtures.onePixelPngBytes()
            }

            val controller = bindController(group(avatarUrl = MISS_AVATAR_URL))

            assertEquals(1, controller.items.size)
            assertNull(controller.items.single().firstFrameAvatar)
            releaseFetch.complete(Unit)
        }

    private fun bindDmController(appState: WhiteNoiseAppState): ChatsController {
        val controller =
            ChatsController(
                appState = appState,
                initialAccountRef = ACCOUNT_REF,
                memberSnapshotLoader = { _, _ -> dmMembers() },
            )
        controller.setChatListVisible(false)
        controller.applyChatListRow(dmRow())
        controller.applyLocalGroupDetails(unnamedDmGroup(), dmMembers())
        controller.setChatListVisible(true)
        return controller
    }

    private fun bindController(group: AppGroupRecordFfi): ChatsController {
        val controller =
            ChatsController(
                appState = appState(),
                initialAccountRef = ACCOUNT_REF,
                memberSnapshotLoader = { _, _ -> emptyList() },
            )
        controller.setChatListVisible(false)
        controller.applyChatListRow(chatRow(group.avatarUrl, group.imageHashHex))
        controller.applyLocalGroupUpdate(group)
        controller.setChatListVisible(true)
        return controller
    }

    private fun appState(profileReader: suspend (String) -> UserProfileMetadataFfi? = { null }): WhiteNoiseAppState {
        val context = ApplicationProvider.getApplicationContext<Context>()
        return WhiteNoiseAppState(
            context = context,
            draftStore = DraftStore(InMemoryDraftPersistence()),
            accountIdHexResolver = { ACCOUNT_ID },
            accounts = listOf(activeAccount()),
            activeAccountRef = ACCOUNT_REF,
            profileReader = profileReader,
        )
    }

    private fun activeAccount() =
        AccountSummaryFfi(
            label = ACCOUNT_REF,
            accountIdHex = ACCOUNT_ID,
            localSigning = true,
            externalSigning = false,
            signedOut = false,
            running = true,
        )

    private fun dmRow() =
        chatRow(
            avatarUrl = null,
            imageHashHex = null,
        ).copy(
            groupName = "",
            title = "Alice",
            conversationKind = ChatConversationKindFfi.DIRECT,
        )

    private fun unnamedDmGroup() =
        group().copy(
            name = "",
            avatarUrl = null,
            imageHashHex = null,
        )

    private fun dmMembers() =
        listOf(
            AppGroupMemberRecordFfi(
                memberIdHex = ACCOUNT_ID,
                account = ACCOUNT_REF,
                local = true,
            ),
            AppGroupMemberRecordFfi(
                memberIdHex = PEER_ID,
                account = null,
                local = false,
            ),
        )

    private fun peerProfile() =
        UserProfileMetadataFfi(
            name = "alice",
            displayName = PEER_NAME,
            about = null,
            picture = PEER_AVATAR,
            nip05 = null,
            lud16 = null,
        )

    private fun chatRow(
        avatarUrl: String?,
        imageHashHex: String?,
    ) = ChatListRowFfi(
        selfMembership = SelfMembershipFfi.MEMBER,
        unreadMentionCount = 0uL,
        unreadMention = false,
        groupIdHex = GROUP_ID,
        archived = false,
        pendingConfirmation = false,
        title = "Conversation",
        groupName = "Conversation",
        avatarUrl = avatarUrl,
        avatar =
            imageHashHex?.let {
                ChatListAvatarFfi(
                    imageHashHex = it,
                    imageKeyHex = "redacted-test-key",
                    imageNonceHex = "redacted-test-nonce",
                    imageUploadKeyHex = "redacted-test-upload-key",
                    mediaType = "image/png",
                )
            },
        lastMessage = null,
        unreadCount = 0uL,
        hasUnread = false,
        firstUnreadMessageIdHex = null,
        lastReadMessageIdHex = null,
        lastReadTimelineAt = null,
        conversationCreatedAt = 1uL,
        activitySortAt = 1_700_000_000uL,
        updatedAt = 1_700_000_000uL,
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

    private fun group(
        avatarUrl: String? = null,
        imageHashHex: String? = null,
    ) = AppGroupRecordFfi(
        selfMembership = SelfMembershipFfi.MEMBER,
        groupIdHex = GROUP_ID,
        protocolProfile = AppProtocolProfileFfi.LEGACY,
        profilePresent = false,
        endpoint = "wss://relay.example",
        name = "Conversation",
        description = "",
        admins = emptyList(),
        relays = emptyList(),
        nostrGroupIdHex = "66".repeat(32),
        avatarUrl = avatarUrl,
        avatarDim = null,
        avatarThumbhash = null,
        imageHashHex = imageHashHex,
        encryptedMedia =
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
            ),
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

    private class InMemoryDraftPersistence : DraftPersistence {
        override fun read(): Map<String, String> = emptyMap()

        override fun write(
            key: String,
            value: String?,
        ) = Unit
    }

    private companion object {
        const val ACCOUNT_REF = "primary"
        val ACCOUNT_ID = "11".repeat(32)
        val GROUP_ID = "44".repeat(32)
        val IMAGE_HASH = "77".repeat(32)
        const val LEGACY_AVATAR_URL = "https://groups.example/avatar.jpg"
        const val MISS_AVATAR_URL = "https://groups.example/missing.jpg"
        val PEER_ID = "22".repeat(32)
        const val PEER_NAME = "Alice"
        const val PEER_AVATAR = "https://profiles.example/alice.jpg"
    }
}
