package dev.ipf.whitenoise.android.ui.account

import android.content.Context
import android.graphics.Color
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.hasScrollToIndexAction
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.unit.LayoutDirection
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
    @Suppress("LongMethod") // Keep cached seed, first header and real selector checks in one ordered flow.
    fun locallySeededActiveAndOtherAccountProfilesOwnFirstFrame() =
        runTest {
            val appState = appState(includeActiveProfile = true)
            appState.warmProfilePresentationsBlocking(listOf(ACTIVE_ID, STUDIO_ID, WORK_ID))
            AvatarImageLoader.putCached(ACTIVE_AVATAR, AvatarScreenshotFixtures.distinctAvatarBitmap(Color.GREEN))
            AvatarImageLoader.putCached(STUDIO_AVATAR, AvatarScreenshotFixtures.distinctAvatarBitmap(Color.RED))
            AvatarImageLoader.putCached(WORK_AVATAR, AvatarScreenshotFixtures.distinctAvatarBitmap(Color.BLUE))

            assertEquals(ACTIVE_NAME, appState.displayName(ACTIVE_ID))
            assertEquals(STUDIO_NAME, appState.displayName(STUDIO_ID))
            assertEquals(WORK_NAME, appState.displayName(WORK_ID))
            assertEquals(ACTIVE_AVATAR, appState.avatarUrl(ACTIVE_ID))
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

            composeRule.onNodeWithContentDescription(ACTIVE_NAME, substring = true).assertIsDisplayed()
            assertEquals(ACTIVE_REF, appState.activeAccountRef)
            composeRule
                .onNodeWithTag(SCREENSHOT_TAG)
                .captureRoboImage("src/test/snapshots/account_switch_first_frame_seeded_profiles_light.png")

            // The active avatar owns the first header frame; other cached identities now live in the selector.
            composeRule.onNodeWithTag("chats.switchProfile").assertHasClickAction().performClick()
            composeRule.onNodeWithTag(profileRowTag(ACTIVE_REF)).assertIsDisplayed().assertIsSelected()
            composeRule.onNodeWithTag(profileRowTag(STUDIO_REF)).assertIsDisplayed().assertIsNotSelected()
            composeRule.onNodeWithTag(profileRowTag(WORK_REF)).assertIsDisplayed().assertIsNotSelected()
            composeRule.onNodeWithText(ACTIVE_NAME).assertIsDisplayed()
            composeRule.onNodeWithText(STUDIO_NAME).assertIsDisplayed()
            composeRule.onNodeWithText(WORK_NAME).assertIsDisplayed()
            assertEquals(ACTIVE_AVATAR, appState.avatarUrl(ACTIVE_ID))
            assertEquals(STUDIO_AVATAR, appState.avatarUrl(STUDIO_ID))
            assertEquals(WORK_AVATAR, appState.avatarUrl(WORK_ID))
            composeRule
                .onNodeWithTag(ACCOUNT_SELECTOR_CONTENT_TAG)
                .captureRoboImage("src/test/snapshots/account_switch_seeded_profiles_selector_light.png")
        }

    @Test
    fun allAccountTargetsRemainReachableInSelectorLtr() {
        captureAllAccountTargets(LayoutDirection.Ltr, "account_switch_overlapping_targets_ltr.png")
    }

    @Test
    fun allAccountTargetsRemainReachableInSelectorRtl() {
        captureAllAccountTargets(LayoutDirection.Rtl, "account_switch_overlapping_targets_rtl.png")
    }

    @Suppress("LongMethod") // Keep both directions on the same real header-to-selector route.
    private fun captureAllAccountTargets(
        layoutDirection: LayoutDirection,
        snapshotName: String,
    ) {
        val appState =
            appState(
                otherAccounts =
                    listOf(
                        account(STUDIO_REF, STUDIO_ID),
                        account(WORK_REF, WORK_ID),
                        account("travel", "44".repeat(32)),
                        account("community", "55".repeat(32)),
                        account("archive", "66".repeat(32)),
                    ),
            )
        composeRule.setContent {
            CompositionLocalProvider(LocalLayoutDirection provides layoutDirection) {
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
        }

        assertEquals(ACTIVE_REF, appState.activeAccountRef)
        composeRule
            .onNodeWithTag("chats.switchProfile")
            .assertIsDisplayed()
            .assertHasClickAction()
            .performClick()
        val labels = listOf(ACTIVE_REF, STUDIO_REF, WORK_REF, "travel", "community", "archive")
        labels.forEachIndexed { index, label ->
            composeRule.onNode(hasScrollToIndexAction()).performScrollToIndex(index)
            val row = composeRule.onNodeWithTag(profileRowTag(label))
            row.assertIsDisplayed().assertHasClickAction()
            if (label == ACTIVE_REF) row.assertIsSelected() else row.assertIsNotSelected()
        }
        // Former overflow accounts remain reachable through the real scrolling list; pinned actions stay available.
        composeRule.onNodeWithTag("profile_switcher.add_profile").assertIsDisplayed().assertHasClickAction()
        composeRule.onNodeWithTag("profile_switcher.settings").assertIsDisplayed().assertHasClickAction()
        assertEquals(ACTIVE_REF, appState.activeAccountRef)
        composeRule.onNode(hasScrollToIndexAction()).performScrollToIndex(0)
        composeRule.onNodeWithTag(profileRowTag(ACTIVE_REF)).assertIsDisplayed()
        composeRule
            .onNodeWithTag(ACCOUNT_SELECTOR_CONTENT_TAG)
            .captureRoboImage("src/test/snapshots/$snapshotName")
    }

    private fun profileRowTag(label: String): String = "profile_switcher.profile.$label"

    private fun appState(
        otherAccounts: List<AccountSummaryFfi> =
            listOf(
                account(STUDIO_REF, STUDIO_ID),
                account(WORK_REF, WORK_ID),
            ),
        includeActiveProfile: Boolean = false,
    ) = WhiteNoiseAppState(
        context = context,
        draftStore = DraftStore(EmptyDraftPersistence),
        accountIdHexResolver = { ACTIVE_ID },
        accounts =
            listOf(account(ACTIVE_REF, ACTIVE_ID)) + otherAccounts,
        activeAccountRef = ACTIVE_REF,
        profileReader = { accountId -> profile(accountId, includeActiveProfile) },
        profileDisplayNameReader = { accountId -> profileName(accountId, includeActiveProfile) },
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

    private fun profile(
        accountIdHex: String,
        includeActiveProfile: Boolean,
    ) = when (accountIdHex) {
        ACTIVE_ID -> userProfile(ACTIVE_NAME, ACTIVE_AVATAR).takeIf { includeActiveProfile }
        STUDIO_ID -> userProfile(STUDIO_NAME, STUDIO_AVATAR)
        WORK_ID -> userProfile(WORK_NAME, WORK_AVATAR)
        else -> null
    }

    private fun profileName(
        accountIdHex: String,
        includeActiveProfile: Boolean,
    ) = when (accountIdHex) {
        ACTIVE_ID -> ACTIVE_NAME.takeIf { includeActiveProfile }
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
        const val ACTIVE_NAME = "Personal profile"
        const val STUDIO_NAME = "Studio profile"
        const val WORK_NAME = "Work profile"
        const val ACTIVE_AVATAR = "https://profiles.example/personal.png"
        const val STUDIO_AVATAR = "https://profiles.example/studio.png"
        const val WORK_AVATAR = "https://profiles.example/work.png"
        val ACTIVE_ID = "11".repeat(32)
        val STUDIO_ID = "22".repeat(32)
        val WORK_ID = "33".repeat(32)
    }
}
