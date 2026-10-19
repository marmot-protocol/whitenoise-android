package dev.ipf.whitenoise.android.ui.settings

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import dev.ipf.whitenoise.android.ui.theme.WhiteNoiseTheme
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/** Behaviour contract of Share & Connect: back, share, copy and the scanner entry each reach their caller. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], qualifiers = "en-rUS-w360dp-h780dp-mdpi")
class ShareConnectScreenBehaviorTest {
    @get:Rule
    val composeRule = createComposeRule()

    private var backCount = 0
    private var urlShareCount = 0
    private var pictureShareCount = 0
    private var copyCount = 0
    private var scannerCount = 0
    private var saveCount = 0
    private val savingState = mutableStateOf(false)
    private val sharingState = mutableStateOf(false)

    /** Restores the platform font scale that the large-text case raises. */
    @After
    fun restorePlatformFontScale() {
        RuntimeEnvironment.setFontScale(1f)
    }

    /** Back and both explicit top-bar share choices invoke their callers once per tap. */
    @Test
    fun backAndShareFireOncePerTap() {
        mount()
        composeRule.onNodeWithContentDescription("Back").performClick()
        composeRule.onNodeWithTag("share_connect.share").performClick()
        composeRule.onNodeWithText("Share profile URL").assertIsDisplayed().performClick()
        composeRule.onNodeWithTag("share_connect.share").performClick()
        composeRule.onNodeWithText("Share profile card").assertIsDisplayed().performClick()
        composeRule.runOnIdle {
            assertEquals(1, backCount)
            assertEquals(1, urlShareCount)
            assertEquals(1, pictureShareCount)
        }
    }

    /** The key capsule is one 48 dp button that copies and reports its copied state. */
    @Test
    fun keyCapsuleCopiesAndAnnouncesTheCopiedState() {
        mount(copied = false)
        composeRule.onNodeWithContentDescription("Copy public key").assertIsDisplayed().performClick()
        composeRule.runOnIdle { assertEquals(1, copyCount) }
    }

    /** While copied, the capsule announces the copied state instead of the copy action. */
    @Test
    fun copiedCapsuleShowsTheCopiedDescription() {
        mount(copied = true)
        composeRule.onNodeWithContentDescription("Copied").assertIsDisplayed()
    }

    /** The pinned Scan QR Code button opens the scanner, and an invalid scan shows the error under the caption. */
    @Test
    fun scanButtonOpensTheScannerAndInvalidScansExplain() {
        mount(scanInvalid = true)
        composeRule.onNodeWithText("Scan QR Code").performClick()
        composeRule
            .onNode(hasText("That QR code doesn’t contain a White Noise profile."))
            .performScrollTo()
            .assertIsDisplayed()
        composeRule.runOnIdle { assertEquals(1, scannerCount) }
    }

    /** The identity column shows the name, the address and the scan caption. */
    @Test
    fun identityColumnShowsNameAddressAndCaption() {
        mount()
        composeRule.onNodeWithText("Alice").assertIsDisplayed()
        composeRule.onNodeWithText("alice@example.com").assertIsDisplayed()
        composeRule.onNodeWithText("Scan to connect.").performScrollTo().assertIsDisplayed()
    }

    /** The whole public key shows below the capsule for comparison, read as its contiguous value (#3321). */
    @Test
    fun theWholePublicKeyShowsBelowTheCapsule() {
        mount()
        composeRule
            .onNodeWithTag("share_connect.full_public_key", useUnmergedTree = true)
            .performScrollTo()
            .assertIsDisplayed()
            .assertTextEquals(shareConnectFixture.npub)
    }

    /** Save to Gallery is the Share menu's last entry, and the toolbar carries no separate save action. */
    @Test
    fun saveToGalleryLivesOnlyInsideTheShareMenu() {
        mount()
        composeRule.onNodeWithTag(SAVE_ENTRY).assertDoesNotExist()
        composeRule.onNodeWithContentDescription("Save").assertDoesNotExist()
        composeRule.onNodeWithContentDescription("Download").assertDoesNotExist()
        openShareMenu()
        composeRule.onNodeWithText("Share profile URL").assertIsDisplayed()
        composeRule.onNodeWithText("Share profile card").assertIsDisplayed()
        composeRule.onNodeWithTag(SAVE_ENTRY).assertIsDisplayed().assertIsEnabled()
        composeRule.onNodeWithText("Save to Gallery").assertIsDisplayed()
    }

    /** One tap on Save to Gallery closes the menu and reaches only the save caller. */
    @Test
    fun saveToGalleryClosesTheMenuAndFiresOncePerTap() {
        mount()
        openShareMenu()
        composeRule.onNodeWithTag(SAVE_ENTRY).performClick()
        composeRule.onNodeWithText("Save to Gallery").assertDoesNotExist()
        composeRule.runOnIdle {
            assertEquals(1, saveCount)
            assertEquals(0, pictureShareCount)
            assertEquals(0, urlShareCount)
        }
    }

    /** A saving export disables Share, announces Saving, keeps the menu shut and leaves the scanner usable. */
    @Test
    fun savingDisablesShareAnnouncesSavingAndPreservesScanner() {
        mount(saving = true)
        val trigger = composeRule.onNodeWithTag("share_connect.share").assertIsNotEnabled()
        val config = trigger.fetchSemanticsNode().config
        assertEquals("Saving…", config[SemanticsProperties.StateDescription])
        assertEquals(listOf("Share profile"), config[SemanticsProperties.ContentDescription])
        trigger.performClick()
        composeRule.onNodeWithText("Save to Gallery").assertDoesNotExist()
        composeRule.onNodeWithText("Scan QR Code").performClick()
        composeRule.runOnIdle {
            assertEquals(1, scannerCount)
            assertEquals(0, saveCount)
        }
    }

    /** A picture share in flight also disables the trigger, without claiming that a save is running. */
    @Test
    fun pictureSharingDisablesShareWithoutASavingAnnouncement() {
        sharingState.value = true
        mount()
        val trigger = composeRule.onNodeWithTag("share_connect.share").assertIsNotEnabled()
        val config = trigger.fetchSemanticsNode().config
        assertFalse(config.contains(SemanticsProperties.StateDescription))
        assertEquals(listOf("Share profile"), config[SemanticsProperties.ContentDescription])
    }

    /** A nonblank display value without a parsed profile link keeps the entry visible but disabled. */
    @Test
    fun invalidProfileDisablesTheSaveEntry() {
        mount(canSave = false)
        openShareMenu()
        composeRule
            .onNodeWithTag(SAVE_ENTRY)
            .assertIsDisplayed()
            .assertIsNotEnabled()
            .performClick()
        composeRule.onNodeWithText("Share profile URL").assertIsDisplayed()
        composeRule.runOnIdle { assertEquals(0, saveCount) }
    }

    /** A save that starts behind an open menu, as a captured accessibility action could, closes it for good. */
    @Test
    fun exportStartedWhileTheMenuIsOpenClosesItAndItStaysClosed() {
        mount()
        openShareMenu()
        composeRule.runOnIdle { savingState.value = true }
        composeRule.onNodeWithText("Save to Gallery").assertDoesNotExist()
        composeRule.onNodeWithTag("share_connect.share").assertIsNotEnabled()
        composeRule.runOnIdle { savingState.value = false }
        composeRule.onNodeWithTag("share_connect.share").assertIsEnabled()
        composeRule.onNodeWithText("Save to Gallery").assertDoesNotExist()
    }

    /** At 200% text in RTL the Save to Gallery entry stays visible, enabled and activatable. */
    @Test
    fun saveToGalleryStaysReachableAtLargeTextInRtl() {
        // The popup window reads the platform font scale, not the composition's density override, so set both.
        RuntimeEnvironment.setFontScale(2f)
        mount(largeTextRtl = true)
        openShareMenu()
        composeRule.onNodeWithText("Save to Gallery").assertIsDisplayed()
        composeRule.onNodeWithTag(SAVE_ENTRY).assertIsEnabled().performClick()
        composeRule.runOnIdle { assertEquals(1, saveCount) }
    }

    /** Opens the Share menu from its top-bar trigger. */
    private fun openShareMenu() {
        composeRule.onNodeWithTag("share_connect.share").performClick()
    }

    /** Mounts the composable under test. */
    private fun mount(
        copied: Boolean = false,
        scanInvalid: Boolean = false,
        saving: Boolean = false,
        canSave: Boolean = true,
        largeTextRtl: Boolean = false,
    ) {
        savingState.value = saving
        composeRule.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(
                LocalDensity provides Density(density.density, if (largeTextRtl) 2f else 1f),
                LocalLayoutDirection provides if (largeTextRtl) LayoutDirection.Rtl else LayoutDirection.Ltr,
            ) {
                WhiteNoiseTheme(darkTheme = false) {
                    ShareConnectContent(
                        profile = shareConnectFixture,
                        qrContent = "marmot://profile/${shareConnectFixture.npub}?from=qr",
                        copied = copied,
                        scanInvalid = scanInvalid,
                        pictureShareInProgress = sharingState.value,
                        gallerySaveInProgress = savingState.value,
                        canSaveToGallery = canSave,
                        onSaveToGallery = { saveCount++ },
                        onBack = { backCount++ },
                        onShareUrl = { urlShareCount++ },
                        onSharePicture = { pictureShareCount++ },
                        onCopy = { copyCount++ },
                        onOpenScanner = { scannerCount++ },
                    )
                }
            }
        }
    }

    private companion object {
        const val SAVE_ENTRY = "share_connect.save_to_gallery"
    }
}

/** Alice with a verified-looking address; the key is a syntactically plausible npub of the right length. */
internal val shareConnectFixture =
    ShareConnectProfile(
        name = "Alice",
        npub = "npub1" + "q".repeat(58),
        seed = "alice-account-id",
        pictureUrl = null,
        nostrAddress = "alice@example.com",
    )
