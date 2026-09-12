package dev.ipf.whitenoise.android.ui.qr

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.unit.dp
import dev.ipf.whitenoise.android.ui.theme.WhiteNoiseTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Presentation actions route to their distinct production owners without generating scan results. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class QrScannerContentTest {
    @get:Rule val composeRule = createComposeRule()
    private var requests = 0
    private var settings = 0
    private var toggles = 0

    /** Ordinary denial offers the Android permission contract, with no camera mounted. */
    @Test fun deniedRequestsPermission() {
        show(granted = false)
        composeRule.onNodeWithTag("qr_scanner.recovery").performClick()
        composeRule.onNodeWithTag("preview").assertDoesNotExist()
        composeRule.runOnIdle {
            assertEquals(1, requests)
            assertEquals(0, settings)
        }
    }

    /** Permanent denial invokes settings rather than repeating a permission prompt Android will suppress. */
    @Test fun permanentDenialOpensSettings() {
        show(granted = false, permanent = true)
        composeRule.onNodeWithTag("qr_scanner.recovery").performClick()
        composeRule.runOnIdle {
            assertEquals(0, requests)
            assertEquals(1, settings)
        }
    }

    /** Waiting for Android's permission response keeps Close visible and offers no duplicate request. */
    @Test fun pendingRequestHasNoDuplicateAction() {
        show(granted = false, pending = true)
        composeRule.onNodeWithTag("qr_scanner.permission_pending").assertExists()
        composeRule.onNodeWithTag("qr_scanner.close").assertExists()
        composeRule.onNodeWithTag("qr_scanner.recovery").assertDoesNotExist()
    }

    /** A checked torch dispatches its own callback and cannot be toggled again while its request is pending. */
    @Test fun torchUsesObservedStateAndBusyGate() {
        show(granted = true, flash = true, torchPending = true)
        composeRule.onNodeWithTag("qr_scanner.torch").assertIsOn().assertIsNotEnabled()
        composeRule.runOnIdle { assertEquals(0, toggles) }
    }

    /** Missing flash hardware omits the control without substituting a pretend toggle. */
    @Test fun noFlashHasNoTorchAction() {
        show(granted = true)
        composeRule.onNodeWithTag("qr_scanner.target").assertExists()
        composeRule.onNodeWithTag("qr_scanner.torch").assertDoesNotExist()
    }

    /** At 200dp/200% text, scroll reaches a complete minimum-size permission action below the header. */
    @Test fun deniedShortPaneLargeTextKeepsRecoveryReachable() {
        show(granted = false, heightDp = 200, fontScale = 2f)
        val action = composeRule.onNodeWithTag("qr_scanner.recovery")
        action.performScrollTo().assertIsDisplayed().assertHeightIsAtLeast(48.dp)
        val bounds = action.getUnclippedBoundsInRoot()
        assertTrue(bounds.top >= 112.dp)
        assertTrue(bounds.bottom <= 200.dp)
        action.performClick()
        composeRule.runOnIdle { assertEquals(1, requests) }
    }

    /** Permanent-denial settings recovery also remains reachable in a short landscape pane. */
    @Test fun settingsShortPaneLargeTextKeepsRecoveryReachable() {
        show(granted = false, permanent = true, heightDp = 224, fontScale = 2f)
        composeRule
            .onNodeWithTag("qr_scanner.recovery")
            .performScrollTo()
            .assertIsDisplayed()
            .assertHeightIsAtLeast(48.dp)
            .performClick()
        composeRule.runOnIdle {
            assertEquals(1, settings)
            assertEquals(0, requests)
        }
    }

    /** An impossibly small target is omitted while the real camera preview remains mounted. */
    @Test fun tinyTargetIsNotDrawn() {
        show(granted = true, heightDp = 224)
        composeRule.onNodeWithTag("preview").assertExists()
        composeRule.onNodeWithTag("qr_scanner.target").assertDoesNotExist()
    }

    private fun show(
        granted: Boolean,
        permanent: Boolean = false,
        pending: Boolean = false,
        flash: Boolean = false,
        torchPending: Boolean = false,
        heightDp: Int? = null,
        fontScale: Float = 1f,
    ) {
        composeRule.setContent {
            WhiteNoiseTheme(fontScale = fontScale) {
                QrScannerSheetContent(
                    modifier = heightDp?.let { Modifier.height(it.dp) } ?: Modifier,
                    permissionGranted = granted,
                    scannerError = null,
                    onDismiss = {},
                    onRequestPermission = { requests++ },
                    cameraPreview = { Box(Modifier.fillMaxSize().testTag("preview")) },
                    permissionPending = pending,
                    openSettings = permanent,
                    onOpenSettings = { settings++ },
                    hasFlashUnit = flash,
                    torchEnabled = true,
                    torchPending = torchPending,
                    onToggleTorch = { toggles++ },
                )
            }
        }
    }
}
