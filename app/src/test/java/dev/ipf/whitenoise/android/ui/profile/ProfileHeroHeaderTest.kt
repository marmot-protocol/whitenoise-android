package dev.ipf.whitenoise.android.ui.profile

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
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
@Config(sdk = [36], qualifiers = "en")
class ProfileHeroHeaderTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun avatarOverlapsTheWideBannerAndNpubRemainsCopyable() {
        var avatarOpened = false
        var npubCopied = false

        composeRule.setContent {
            WhiteNoiseTheme {
                Box(Modifier.width(360.dp)) {
                    ProfileHeroHeader(
                        title = "Clever Pony",
                        seed = "seed",
                        npub = "npub1ajm54...pswgq06f",
                        pictureUrl = null,
                        bannerUrl = null,
                        bannerValid = true,
                        bannerUploading = false,
                        avatarImageAvailable = false,
                        pictureInvalid = false,
                        onEditBanner = {},
                        onOpenPicture = { avatarOpened = true },
                        onEditPicture = {},
                        onCopyNpub = { npubCopied = true },
                    )
                }
            }
        }

        val bannerBounds =
            composeRule.onNodeWithTag(PROFILE_BANNER_CONTROL_TAG).fetchSemanticsNode().boundsInRoot
        val avatarBounds =
            composeRule.onNodeWithTag(PROFILE_HEADER_AVATAR_TAG).fetchSemanticsNode().boundsInRoot
        val nameBounds =
            composeRule.onNodeWithTag(PROFILE_HEADER_NAME_TAG).fetchSemanticsNode().boundsInRoot

        assertEquals(2f, bannerBounds.width / bannerBounds.height, 0.05f)
        assertTrue(avatarBounds.top < bannerBounds.bottom)
        assertTrue(avatarBounds.bottom > bannerBounds.bottom)
        assertTrue(nameBounds.top > bannerBounds.bottom)

        composeRule.onNodeWithTag(PROFILE_HEADER_AVATAR_TAG).performClick()
        composeRule.onNodeWithText("npub1ajm54...pswgq06f").performClick()
        assertTrue(avatarOpened)
        assertTrue(npubCopied)
    }

    @Test
    fun unresolvedAssetsKeepTheWholeHeroHiddenAndNonInteractive() {
        var bannerOpened = false
        var avatarOpened = false

        composeRule.setContent {
            WhiteNoiseTheme {
                Box(Modifier.width(360.dp)) {
                    ProfileHeroHeader(
                        title = "Clever Pony",
                        seed = "seed",
                        npub = "npub1ajm54...pswgq06f",
                        pictureUrl = "https://example.com/avatar.jpg",
                        bannerUrl = "https://example.com/banner.jpg",
                        bannerValid = true,
                        bannerUploading = false,
                        contentReady = false,
                        avatarImageAvailable = false,
                        pictureInvalid = false,
                        onEditBanner = { bannerOpened = true },
                        onOpenPicture = { avatarOpened = true },
                        onEditPicture = {},
                        onCopyNpub = {},
                    )
                }
            }
        }

        composeRule.onNodeWithTag(PROFILE_HERO_LOADING_TAG).assertIsDisplayed()
        composeRule.onNodeWithTag(PROFILE_BANNER_CONTROL_TAG).assertIsNotEnabled().performClick()
        composeRule.onNodeWithTag(PROFILE_HEADER_AVATAR_TAG).assertIsNotEnabled().performClick()
        assertTrue(!bannerOpened)
        assertTrue(!avatarOpened)
    }
}
