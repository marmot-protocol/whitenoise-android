package dev.ipf.whitenoise.android.ui.settings

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import dev.ipf.whitenoise.android.ui.theme.WhiteNoiseTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Behaviour contract of Share & Connect: back, share, copy and the scanner entry each reach their caller. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], qualifiers = "en-rUS-w360dp-h780dp-mdpi")
class ShareConnectScreenBehaviorTest {
    @get:Rule
    val composeRule = createComposeRule()

    private var backCount = 0
    private var shareCount = 0
    private var copyCount = 0
    private var scannerCount = 0

    /** Back and the top-bar share action invoke their callers once per tap. */
    @Test
    fun backAndShareFireOncePerTap() {
        mount()
        composeRule.onNodeWithContentDescription("Back").performClick()
        composeRule.onNodeWithTag("share_connect.share").performClick()
        composeRule.runOnIdle {
            assertEquals(1, backCount)
            assertEquals(1, shareCount)
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
        composeRule.onNodeWithText("Scan to connect.").assertIsDisplayed()
    }

    private fun mount(
        copied: Boolean = false,
        scanInvalid: Boolean = false,
    ) {
        composeRule.setContent {
            WhiteNoiseTheme(darkTheme = false) {
                ShareConnectContent(
                    profile = shareConnectFixture,
                    qrContent = "marmot://profile/${shareConnectFixture.npub}?from=qr",
                    copied = copied,
                    scanInvalid = scanInvalid,
                    onBack = { backCount++ },
                    onShare = { shareCount++ },
                    onCopy = { copyCount++ },
                    onOpenScanner = { scannerCount++ },
                )
            }
        }
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
