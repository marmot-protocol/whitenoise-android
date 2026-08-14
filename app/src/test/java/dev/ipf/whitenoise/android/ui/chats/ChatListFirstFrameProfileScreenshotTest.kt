package dev.ipf.whitenoise.android.ui.chats

import android.content.Context
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
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
import dev.ipf.whitenoise.android.state.ChatListItem
import dev.ipf.whitenoise.android.state.DraftPersistence
import dev.ipf.whitenoise.android.state.DraftStore
import dev.ipf.whitenoise.android.state.WhiteNoiseAppState
import dev.ipf.whitenoise.android.ui.theme.WhiteNoiseTheme
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Visual contract for #1534: the cached DM identity owns the first rendered row. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "w360dp-h780dp-mdpi")
class ChatListFirstFrameProfileScreenshotTest {
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
    fun cachedDmProfileRendersInTheFirstChatRow() =
        runTest {
            val appState = appState()
            appState.warmProfilePresentationsBlocking(listOf(PEER_ID))

            assertEquals(PEER_NAME, appState.chatMemberTitleCached(PEER_ID))
            assertEquals(PEER_AVATAR, appState.avatarUrl(PEER_ID))

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
            composeRule.waitForIdle()
            composeRule.onNodeWithText(PEER_NAME).assertIsDisplayed()
            composeRule
                .onNodeWithTag(SCREENSHOT_TAG)
                .captureRoboImage("src/test/snapshots/chat_list_first_frame_cached_dm_profile_light.png")
        }

    private fun appState(): WhiteNoiseAppState {
        val context = ApplicationProvider.getApplicationContext<Context>()
        return WhiteNoiseAppState(
            context = context,
            draftStore = DraftStore(EmptyDraftPersistence),
            accountIdHexResolver = { ACCOUNT_ID },
            accounts = listOf(activeAccount()),
            activeAccountRef = ACCOUNT_REF,
            profileReader = { peerProfile() },
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

    private fun peerProfile() =
        UserProfileMetadataFfi(
            name = "alice",
            displayName = PEER_NAME,
            about = null,
            picture = PEER_AVATAR,
            nip05 = null,
            lud16 = null,
        )

    private fun directChatItem() =
        ChatListItem(
            group = directGroup(),
            latest = null,
            otherMemberAccount = PEER_ID,
            memberCount = 2,
            memberSnapshot = null,
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
        const val SCREENSHOT_TAG = "chat-list-first-frame-cached-dm-profile"
        const val ACCOUNT_REF = "primary"
        val ACCOUNT_ID = "11".repeat(32)
        val PEER_ID = "22".repeat(32)
        val GROUP_ID = "44".repeat(32)
        const val PEER_NAME = "Alice"
        const val PEER_AVATAR = "https://profiles.example/alice.jpg"
    }
}
