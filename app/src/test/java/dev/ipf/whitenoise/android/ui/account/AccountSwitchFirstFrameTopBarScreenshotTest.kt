package dev.ipf.whitenoise.android.ui.account

import android.content.Context
import android.graphics.Color
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.test.core.app.ApplicationProvider
import com.github.takahirom.roborazzi.captureRoboImage
import dev.ipf.marmotkit.AccountSummaryFfi
import dev.ipf.marmotkit.UserProfileMetadataFfi
import dev.ipf.whitenoise.android.core.AvatarImageLoader
import dev.ipf.whitenoise.android.state.DraftPersistence
import dev.ipf.whitenoise.android.state.DraftStore
import dev.ipf.whitenoise.android.state.WhiteNoiseAppState
import dev.ipf.whitenoise.android.ui.chats.AvatarScreenshotFixtures
import dev.ipf.whitenoise.android.ui.chats.ChatListTopBar
import dev.ipf.whitenoise.android.ui.chats.ConnectivityBannerState
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

/** Visual contract for #2155: account-switch profile seeds own the first top-bar frame. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "w360dp-h780dp-mdpi")
class AccountSwitchFirstFrameTopBarScreenshotTest {
    @get:Rule
    val composeRule = createComposeRule()

    private val context = ApplicationProvider.getApplicationContext<Context>()

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
    fun locallySeededOtherAccountNamesAndAvatarsOwnFirstFrame() =
        runTest {
            val appState = appState()
            appState.warmProfilePresentationsBlocking(listOf(STUDIO_ID, WORK_ID))
            AvatarImageLoader.putCached(STUDIO_AVATAR, AvatarScreenshotFixtures.distinctAvatarBitmap(Color.RED))
            AvatarImageLoader.putCached(WORK_AVATAR, AvatarScreenshotFixtures.distinctAvatarBitmap(Color.BLUE))

            assertEquals(STUDIO_NAME, appState.displayName(STUDIO_ID))
            assertEquals(WORK_NAME, appState.displayName(WORK_ID))
            assertEquals(STUDIO_AVATAR, appState.avatarUrl(STUDIO_ID))
            assertEquals(WORK_AVATAR, appState.avatarUrl(WORK_ID))

            composeRule.setContent {
                WhiteNoiseTheme(darkTheme = false) {
                    Surface(color = MaterialTheme.colorScheme.background) {
                        Box(Modifier.fillMaxWidth().testTag(SCREENSHOT_TAG)) {
                            ChatListTopBar(
                                appState = remember { appState },
                                searchOpen = false,
                                searchQuery = "",
                                searchFocusRequester = remember { FocusRequester() },
                                onSearchQueryChange = {},
                                onSearchOpen = {},
                                onSearchClose = {},
                                onMic = {},
                                onOpenSettings = {},
                                onSwitchAccount = {},
                                connectivityState = ConnectivityBannerState.Hidden,
                            )
                        }
                    }
                }
            }

            composeRule.onNodeWithTag(otherAccountAvatarTag(STUDIO_REF)).assertIsDisplayed()
            composeRule.onNodeWithTag(otherAccountAvatarTag(WORK_REF)).assertIsDisplayed()
            composeRule.onNodeWithContentDescription(STUDIO_NAME, substring = true).assertIsDisplayed()
            composeRule.onNodeWithContentDescription(WORK_NAME, substring = true).assertIsDisplayed()
            composeRule
                .onNodeWithTag(SCREENSHOT_TAG)
                .captureRoboImage("src/test/snapshots/account_switch_first_frame_seeded_profiles_light.png")
        }

    private fun appState() =
        WhiteNoiseAppState(
            context = context,
            draftStore = DraftStore(EmptyDraftPersistence),
            accountIdHexResolver = { ACTIVE_ID },
            accounts =
                listOf(
                    account(ACTIVE_REF, ACTIVE_ID),
                    account(STUDIO_REF, STUDIO_ID),
                    account(WORK_REF, WORK_ID),
                ),
            activeAccountRef = ACTIVE_REF,
            profileReader = { accountId -> profile(accountId) },
            profileDisplayNameReader = { accountId -> profileName(accountId) },
            profileRefreshRequest = {},
        )

    private fun account(
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

    private fun profile(accountIdHex: String) =
        when (accountIdHex) {
            STUDIO_ID -> userProfile(STUDIO_NAME, STUDIO_AVATAR)
            WORK_ID -> userProfile(WORK_NAME, WORK_AVATAR)
            else -> null
        }

    private fun profileName(accountIdHex: String) =
        when (accountIdHex) {
            STUDIO_ID -> STUDIO_NAME
            WORK_ID -> WORK_NAME
            else -> null
        }

    private fun userProfile(
        displayName: String,
        picture: String,
    ) = UserProfileMetadataFfi(
        name = displayName.lowercase(),
        displayName = displayName,
        about = null,
        picture = picture,
        nip05 = null,
        lud16 = null,
    )

    private object EmptyDraftPersistence : DraftPersistence {
        override fun read(): Map<String, String> = emptyMap()

        override fun write(
            key: String,
            value: String?,
        ) = Unit
    }

    private companion object {
        const val SCREENSHOT_TAG = "account-switch-first-frame-seeded-profiles"
        const val ACTIVE_REF = "personal"
        const val STUDIO_REF = "studio"
        const val WORK_REF = "work"
        const val STUDIO_NAME = "Studio profile"
        const val WORK_NAME = "Work profile"
        const val STUDIO_AVATAR = "https://profiles.example/studio.png"
        const val WORK_AVATAR = "https://profiles.example/work.png"
        val ACTIVE_ID = "11".repeat(32)
        val STUDIO_ID = "22".repeat(32)
        val WORK_ID = "33".repeat(32)
    }
}
