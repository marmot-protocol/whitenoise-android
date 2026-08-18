package dev.ipf.whitenoise.android.ui.chats

import android.content.Context
import android.graphics.Color
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.test.core.app.ApplicationProvider
import com.github.takahirom.roborazzi.captureRoboImage
import dev.ipf.marmotkit.AccountSummaryFfi
import dev.ipf.marmotkit.AppGroupEncryptedMediaComponentFfi
import dev.ipf.marmotkit.AppGroupRecordFfi
import dev.ipf.marmotkit.AppProtocolProfileFfi
import dev.ipf.marmotkit.EncryptedMediaVersionFfi
import dev.ipf.marmotkit.SelfMembershipFfi
import dev.ipf.marmotkit.UserProfileMetadataFfi
import dev.ipf.whitenoise.android.core.AvatarImageLoader
import dev.ipf.whitenoise.android.state.ChatListAvatarSeed
import dev.ipf.whitenoise.android.state.ChatListAvatarSource
import dev.ipf.whitenoise.android.state.ChatListItem
import dev.ipf.whitenoise.android.state.DraftPersistence
import dev.ipf.whitenoise.android.state.DraftStore
import dev.ipf.whitenoise.android.state.WhiteNoiseAppState
import dev.ipf.whitenoise.android.ui.theme.WhiteNoiseTheme
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Visual contract for #2091: cached DM avatars render on the first committed frame. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "w360dp-h780dp-mdpi")
class ChatListFirstFrameCachedDmAvatarScreenshotTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Before
    fun resetAvatarLoader() {
        AvatarImageLoader.clear()
        AvatarImageLoader.resetProfileImageFetcherForTests()
    }

    @After
    fun clearAvatarLoader() {
        AvatarImageLoader.clear()
        AvatarImageLoader.resetProfileImageFetcherForTests()
    }

    @Test
    fun cachedDmAvatarFirstFrameLight() =
        runTest {
            captureCachedDmAvatar(darkTheme = false, amoled = false, themeName = "light")
        }

    @Test
    fun cachedDmAvatarFirstFrameDark() =
        runTest {
            captureCachedDmAvatar(darkTheme = true, amoled = false, themeName = "dark")
        }

    @Test
    fun cachedDmAvatarFirstFrameAmoled() =
        runTest {
            captureCachedDmAvatar(darkTheme = true, amoled = true, themeName = "amoled")
        }

    @Test
    fun cachedAvatarUrlAFirstFrame() =
        runTest {
            val appState = appState()
            appState.warmProfilePresentationsBlocking(listOf(PEER_ID))
            AvatarImageLoader.putCached(PEER_AVATAR_A, AvatarScreenshotFixtures.distinctAvatarBitmap(Color.RED))

            composeRule.setContent {
                WhiteNoiseTheme(darkTheme = false) {
                    Surface(color = MaterialTheme.colorScheme.background) {
                        Box(Modifier.fillMaxWidth().testTag(SCREENSHOT_TAG)) {
                            ChatRow(
                                item = directChatItem(),
                                appState = appState,
                                interactionsEnabled = false,
                                onClick = {},
                                onOpenProfile = {},
                            )
                        }
                    }
                }
            }
            composeRule
                .onNodeWithTag(SCREENSHOT_TAG)
                .captureRoboImage("src/test/snapshots/chat_list_first_frame_cached_dm_avatar_url_a_light.png")
        }

    @Test
    fun changedAvatarUrlMissesUntilNewUrlIsCached() =
        runTest {
            val appState = appState(profilePicture = PEER_AVATAR_B)
            appState.warmProfilePresentationsBlocking(listOf(PEER_ID))
            val staleImage = AvatarScreenshotFixtures.distinctAvatarBitmap(Color.RED)
            val staleSeed =
                ChatListAvatarSeed(ChatListAvatarSource.FALLBACK_URL, PEER_AVATAR_A, staleImage)

            composeRule.setContent {
                WhiteNoiseTheme(darkTheme = false) {
                    Surface(color = MaterialTheme.colorScheme.background) {
                        Box(Modifier.fillMaxWidth().testTag(SCREENSHOT_TAG)) {
                            ChatRow(
                                item = directChatItem(firstFrameAvatar = staleSeed),
                                appState = appState,
                                interactionsEnabled = false,
                                onClick = {},
                                onOpenProfile = {},
                            )
                        }
                    }
                }
            }
            composeRule
                .onNodeWithTag(SCREENSHOT_TAG)
                .captureRoboImage("src/test/snapshots/chat_list_first_frame_cached_dm_avatar_url_miss_light.png")
        }

    @Test
    fun trueMissKeepsFallbackOnFirstFrame() =
        runTest {
            val appState = appState()
            appState.warmProfilePresentationsBlocking(listOf(PEER_ID))

            composeRule.setContent {
                WhiteNoiseTheme(darkTheme = false) {
                    Surface(color = MaterialTheme.colorScheme.background) {
                        Box(Modifier.fillMaxWidth().testTag(SCREENSHOT_TAG)) {
                            ChatRow(
                                item = directChatItem(),
                                appState = appState,
                                interactionsEnabled = false,
                                onClick = {},
                                onOpenProfile = {},
                            )
                        }
                    }
                }
            }
            composeRule
                .onNodeWithTag(SCREENSHOT_TAG)
                .captureRoboImage("src/test/snapshots/chat_list_first_frame_cached_dm_avatar_miss_light.png")
        }

    private suspend fun captureCachedDmAvatar(
        darkTheme: Boolean,
        amoled: Boolean,
        themeName: String,
    ) {
        val appState = appState()
        appState.warmProfilePresentationsBlocking(listOf(PEER_ID))
        val image = AvatarScreenshotFixtures.distinctAvatarBitmap(Color.RED)
        val firstFrameAvatar =
            ChatListAvatarSeed(ChatListAvatarSource.FALLBACK_URL, PEER_AVATAR_A, image)
        AvatarImageLoader.clear()

        composeRule.setContent {
            WhiteNoiseTheme(darkTheme = darkTheme, amoled = amoled) {
                Surface(color = MaterialTheme.colorScheme.background) {
                    Box(Modifier.fillMaxWidth().testTag(SCREENSHOT_TAG)) {
                        ChatRow(
                            item = directChatItem(firstFrameAvatar = firstFrameAvatar),
                            appState = appState,
                            interactionsEnabled = false,
                            onClick = {},
                            onOpenProfile = {},
                        )
                    }
                }
            }
        }
        composeRule
            .onNodeWithTag(SCREENSHOT_TAG)
            .captureRoboImage("src/test/snapshots/chat_list_first_frame_cached_dm_avatar_$themeName.png")
    }

    private fun appState(profilePicture: String = PEER_AVATAR_A): WhiteNoiseAppState {
        val context = ApplicationProvider.getApplicationContext<Context>()
        return WhiteNoiseAppState(
            context = context,
            draftStore = DraftStore(EmptyDraftPersistence),
            accountIdHexResolver = { ACCOUNT_ID },
            accounts = listOf(activeAccount()),
            activeAccountRef = ACCOUNT_REF,
            profileReader = { peerProfile(profilePicture) },
            profileDisplayNameReader = { PEER_NAME },
            profileRefreshRequest = {},
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

    private fun peerProfile(picture: String = PEER_AVATAR_A) =
        UserProfileMetadataFfi(
            name = "alice",
            displayName = PEER_NAME,
            about = null,
            picture = picture,
            nip05 = null,
            lud16 = null,
        )

    private fun directChatItem(firstFrameAvatar: ChatListAvatarSeed? = null) =
        ChatListItem(
            group = directGroup(),
            latest = null,
            otherMemberAccount = PEER_ID,
            memberCount = 2,
            memberSnapshot = null,
            firstFrameAvatar = firstFrameAvatar,
        )

    private fun directGroup() =
        AppGroupRecordFfi(
            groupIdHex = GROUP_ID,
            protocolProfile = AppProtocolProfileFfi.LEGACY,
            endpoint = "wss://relay.example",
            profilePresent = false,
            name = "",
            description = "",
            admins = emptyList(),
            relays = emptyList(),
            nostrGroupIdHex = "33".repeat(32),
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
                    allowedLocatorKinds = emptyList(),
                    defaultBlobEndpoints = emptyList(),
                ),
            disappearingMessageSecs = 0uL,
            archived = false,
            pendingConfirmation = false,
            unrecoverable = false,
            selfMembership = SelfMembershipFfi.MEMBER,
            leaveRequestPending = false,
            leaveRequestedAtMs = null,
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
        const val SCREENSHOT_TAG = "chat-list-first-frame-cached-dm-avatar"
        const val ACCOUNT_REF = "primary"
        val ACCOUNT_ID = "11".repeat(32)
        val PEER_ID = "22".repeat(32)
        val GROUP_ID = "44".repeat(32)
        const val PEER_NAME = "Alice"
        const val PEER_AVATAR_A = "https://profiles.example/alice-a.jpg"
        const val PEER_AVATAR_B = "https://profiles.example/alice-b.jpg"
    }
}
