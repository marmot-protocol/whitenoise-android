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
import dev.ipf.marmotkit.AppBlobEndpointFfi
import dev.ipf.marmotkit.AppGroupEncryptedMediaComponentFfi
import dev.ipf.marmotkit.AppGroupRecordFfi
import dev.ipf.marmotkit.AppProtocolProfileFfi
import dev.ipf.marmotkit.EncryptedMediaVersionFfi
import dev.ipf.marmotkit.SelfMembershipFfi
import dev.ipf.whitenoise.android.core.AvatarImageLoader
import dev.ipf.whitenoise.android.core.GroupAvatarImageLoader
import dev.ipf.whitenoise.android.state.ChatListAvatarSeed
import dev.ipf.whitenoise.android.state.ChatListAvatarSource
import dev.ipf.whitenoise.android.state.ChatListItem
import dev.ipf.whitenoise.android.state.DraftPersistence
import dev.ipf.whitenoise.android.state.DraftStore
import dev.ipf.whitenoise.android.state.WhiteNoiseAppState
import dev.ipf.whitenoise.android.ui.common.encryptedGroupAvatarCacheKey
import dev.ipf.whitenoise.android.ui.theme.WhiteNoiseTheme
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Visual contract for #2091: encrypted group avatars on the first committed frame. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "w360dp-h780dp-mdpi")
class ChatListFirstFrameEncryptedGroupAvatarScreenshotTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Before
    fun resetLoaders() {
        AvatarImageLoader.clear()
        AvatarImageLoader.resetProfileImageFetcherForTests()
        GroupAvatarImageLoader.clear()
    }

    @After
    fun clearLoaders() {
        AvatarImageLoader.clear()
        AvatarImageLoader.resetProfileImageFetcherForTests()
        GroupAvatarImageLoader.clear()
    }

    @Test
    fun cachedEncryptedGroupAvatarFirstFrameLight() {
        captureEncryptedGroupAvatar(darkTheme = false, amoled = false, themeName = "light")
    }

    @Test
    fun cachedEncryptedGroupAvatarFirstFrameDark() {
        captureEncryptedGroupAvatar(darkTheme = true, amoled = false, themeName = "dark")
    }

    @Test
    fun cachedEncryptedGroupAvatarFirstFrameAmoled() {
        captureEncryptedGroupAvatar(darkTheme = true, amoled = true, themeName = "amoled")
    }

    @Test
    fun accountAEncryptedGroupAvatarFirstFrame() {
        val appState = appState()
        val accountAKey = encryptedGroupAvatarCacheKey(ACCOUNT_REF_A, GROUP_ID, IMAGE_HASH_A)
        GroupAvatarImageLoader.putCached(accountAKey, AvatarScreenshotFixtures.distinctAvatarBitmap(Color.RED))

        composeRule.setContent {
            WhiteNoiseTheme(darkTheme = false) {
                Surface(color = MaterialTheme.colorScheme.background) {
                    Box(Modifier.fillMaxWidth().testTag(SCREENSHOT_TAG)) {
                        ChatRow(
                            item = encryptedGroupItem(hash = IMAGE_HASH_A),
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
            .captureRoboImage("src/test/snapshots/chat_list_first_frame_encrypted_group_avatar_account_a_light.png")
    }

    @Test
    fun accountBDoesNotReuseAccountAFirstFrameSeed() {
        val accountAKey = encryptedGroupAvatarCacheKey(ACCOUNT_REF_A, GROUP_ID, IMAGE_HASH_A)
        val staleSeed = encryptedSeed(accountAKey, Color.RED)
        val appStateB =
            appState(
                accountRef = ACCOUNT_REF_B,
                accountId = ACCOUNT_ID_B,
            )

        composeRule.setContent {
            WhiteNoiseTheme(darkTheme = false) {
                Surface(color = MaterialTheme.colorScheme.background) {
                    Box(Modifier.fillMaxWidth().testTag(SCREENSHOT_TAG)) {
                        ChatRow(
                            item = encryptedGroupItem(hash = IMAGE_HASH_A, firstFrameAvatar = staleSeed),
                            appState = appStateB,
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
            .captureRoboImage("src/test/snapshots/chat_list_first_frame_encrypted_group_avatar_account_b_light.png")
    }

    @Test
    fun cachedEncryptedHashAFirstFrame() {
        val appState = appState()
        val oldKey = encryptedGroupAvatarCacheKey(ACCOUNT_REF_A, GROUP_ID, IMAGE_HASH_A)
        GroupAvatarImageLoader.putCached(oldKey, AvatarScreenshotFixtures.distinctAvatarBitmap(Color.RED))

        composeRule.setContent {
            WhiteNoiseTheme(darkTheme = false) {
                Surface(color = MaterialTheme.colorScheme.background) {
                    Box(Modifier.fillMaxWidth().testTag(SCREENSHOT_TAG)) {
                        ChatRow(
                            item = encryptedGroupItem(hash = IMAGE_HASH_A),
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
            .captureRoboImage("src/test/snapshots/chat_list_first_frame_encrypted_group_avatar_hash_a_light.png")
    }

    @Test
    fun changedHashMissesUntilNewHashIsCached() {
        val appState = appState()
        val staleKey = encryptedGroupAvatarCacheKey(ACCOUNT_REF_A, GROUP_ID, IMAGE_HASH_A)
        val staleSeed = encryptedSeed(staleKey, Color.RED)

        composeRule.setContent {
            WhiteNoiseTheme(darkTheme = false) {
                Surface(color = MaterialTheme.colorScheme.background) {
                    Box(Modifier.fillMaxWidth().testTag(SCREENSHOT_TAG)) {
                        ChatRow(
                            item = encryptedGroupItem(hash = IMAGE_HASH_B, firstFrameAvatar = staleSeed),
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
            .captureRoboImage("src/test/snapshots/chat_list_first_frame_encrypted_group_avatar_hash_miss_light.png")
    }

    @Test
    fun legacyUrlPrecedesEncryptedImage() {
        val appState = appState()
        AvatarImageLoader.putCached(GROUP_AVATAR_URL, AvatarScreenshotFixtures.distinctAvatarBitmap(Color.GREEN))
        val encryptedKey = encryptedGroupAvatarCacheKey(ACCOUNT_REF_A, GROUP_ID, IMAGE_HASH_A)
        val staleSeed = encryptedSeed(encryptedKey, Color.RED)

        composeRule.setContent {
            WhiteNoiseTheme(darkTheme = false) {
                Surface(color = MaterialTheme.colorScheme.background) {
                    Box(Modifier.fillMaxWidth().testTag(SCREENSHOT_TAG)) {
                        ChatRow(
                            item =
                                encryptedGroupItem(
                                    hash = IMAGE_HASH_A,
                                    avatarUrl = GROUP_AVATAR_URL,
                                    firstFrameAvatar = staleSeed,
                                ),
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
            .captureRoboImage(
                "src/test/snapshots/chat_list_first_frame_encrypted_group_avatar_url_precedence_light.png",
            )
    }

    @Test
    fun pendingConfirmationKeepsFallback() {
        val appState = appState()
        val encryptedKey = encryptedGroupAvatarCacheKey(ACCOUNT_REF_A, GROUP_ID, IMAGE_HASH_A)
        val staleSeed = encryptedSeed(encryptedKey, Color.RED)

        composeRule.setContent {
            WhiteNoiseTheme(darkTheme = false) {
                Surface(color = MaterialTheme.colorScheme.background) {
                    Box(Modifier.fillMaxWidth().testTag(SCREENSHOT_TAG)) {
                        ChatRow(
                            item =
                                encryptedGroupItem(
                                    hash = IMAGE_HASH_A,
                                    pendingConfirmation = true,
                                    firstFrameAvatar = staleSeed,
                                ),
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
            .captureRoboImage("src/test/snapshots/chat_list_first_frame_encrypted_group_avatar_pending_light.png")
    }

    @Test
    fun cancelledLoadKeepsFallback() {
        val appState = appState()
        GroupAvatarImageLoader.clear()

        composeRule.setContent {
            WhiteNoiseTheme(darkTheme = false) {
                Surface(color = MaterialTheme.colorScheme.background) {
                    Box(Modifier.fillMaxWidth().testTag(SCREENSHOT_TAG)) {
                        ChatRow(
                            item = encryptedGroupItem(hash = IMAGE_HASH_A),
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
            .captureRoboImage("src/test/snapshots/chat_list_first_frame_encrypted_group_avatar_miss_light.png")
    }

    private fun captureEncryptedGroupAvatar(
        darkTheme: Boolean,
        amoled: Boolean,
        themeName: String,
    ) {
        val appState = appState()
        val cacheKey = encryptedGroupAvatarCacheKey(ACCOUNT_REF_A, GROUP_ID, IMAGE_HASH_A)
        val firstFrameAvatar = encryptedSeed(cacheKey, Color.RED)
        GroupAvatarImageLoader.clear()

        composeRule.setContent {
            WhiteNoiseTheme(darkTheme = darkTheme, amoled = amoled) {
                Surface(color = MaterialTheme.colorScheme.background) {
                    Box(Modifier.fillMaxWidth().testTag(SCREENSHOT_TAG)) {
                        ChatRow(
                            item =
                                encryptedGroupItem(
                                    hash = IMAGE_HASH_A,
                                    firstFrameAvatar = firstFrameAvatar,
                                ),
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
            .captureRoboImage("src/test/snapshots/chat_list_first_frame_encrypted_group_avatar_$themeName.png")
    }

    private fun appState(
        accountRef: String = ACCOUNT_REF_A,
        accountId: String = ACCOUNT_ID_A,
    ): WhiteNoiseAppState {
        val context = ApplicationProvider.getApplicationContext<Context>()
        return WhiteNoiseAppState(
            context = context,
            draftStore = DraftStore(EmptyDraftPersistence),
            accountIdHexResolver = { accountId },
            accounts =
                listOf(
                    AccountSummaryFfi(
                        label = accountRef,
                        accountIdHex = accountId,
                        localSigning = true,
                        externalSigning = false,
                        signedOut = false,
                        running = true,
                    ),
                ),
            activeAccountRef = accountRef,
        )
    }

    private fun encryptedGroupItem(
        hash: String = IMAGE_HASH_A,
        avatarUrl: String? = null,
        pendingConfirmation: Boolean = false,
        firstFrameAvatar: ChatListAvatarSeed? = null,
    ) = ChatListItem(
        group = encryptedGroup(hash = hash, avatarUrl = avatarUrl, pendingConfirmation = pendingConfirmation),
        latest = null,
        otherMemberAccount = null,
        memberCount = 3,
        memberSnapshot = null,
        firstFrameAvatar = firstFrameAvatar,
    )

    private fun encryptedSeed(
        key: String,
        color: Int,
    ) = ChatListAvatarSeed(
        source = ChatListAvatarSource.ENCRYPTED_GROUP,
        key = key,
        image = AvatarScreenshotFixtures.distinctAvatarBitmap(color),
    )

    private fun encryptedGroup(
        hash: String,
        avatarUrl: String? = null,
        pendingConfirmation: Boolean = false,
    ) = AppGroupRecordFfi(
        groupIdHex = GROUP_ID,
        protocolProfile = AppProtocolProfileFfi.LEGACY,
        endpoint = "wss://relay.example",
        profilePresent = false,
        name = "Weekend hikers",
        description = "",
        admins = emptyList(),
        relays = emptyList(),
        nostrGroupIdHex = "33".repeat(32),
        avatarUrl = avatarUrl,
        avatarDim = null,
        avatarThumbhash = null,
        imageHashHex = hash,
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
                            baseUrl = "https://blossom.primal.net",
                        ),
                    ),
            ),
        disappearingMessageSecs = 0uL,
        archived = false,
        pendingConfirmation = pendingConfirmation,
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
        const val SCREENSHOT_TAG = "chat-list-first-frame-encrypted-group-avatar"
        const val ACCOUNT_REF_A = "account-a"
        const val ACCOUNT_REF_B = "account-b"
        val ACCOUNT_ID_A = "aa".repeat(32)
        val ACCOUNT_ID_B = "bb".repeat(32)
        val GROUP_ID = "44".repeat(32)
        val IMAGE_HASH_A = "66".repeat(32)
        val IMAGE_HASH_B = "77".repeat(32)
        const val GROUP_AVATAR_URL = "https://profiles.example/group.jpg"
    }
}
