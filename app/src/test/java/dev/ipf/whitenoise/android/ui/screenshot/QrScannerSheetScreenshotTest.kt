package dev.ipf.whitenoise.android.ui.screenshot

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.unit.LayoutDirection
import com.github.takahirom.roborazzi.captureRoboImage
import dev.ipf.whitenoise.android.R
import dev.ipf.whitenoise.android.ui.qr.QR_SCANNER_SHEET_CONTENT_TAG
import dev.ipf.whitenoise.android.ui.qr.QrScannerSheetContent
import dev.ipf.whitenoise.android.ui.theme.WhiteNoiseTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Deterministic chrome only; the flat preview does not qualify real camera, permission or decoding behavior. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "en-rUS-w360dp-h640dp-mdpi")
class QrScannerSheetScreenshotTest {
    @get:Rule val composeRule = createComposeRule()

    /** Retain the existing idle baseline name while updating the full scanner composition. */
    @Test fun qrScannerSheetIdleDark() =
        capture(
            "qr_scanner_sheet_idle_dark",
            granted = true,
        )

    /** Retain the existing denied baseline with the prototype centered permission card. */
    @Test fun qrScannerSheetPermissionDeniedDark() =
        capture(
            "qr_scanner_sheet_permission_denied_dark",
        )

    /** Permission text and action follow the ordinary light theme. */
    @Test fun permissionLight() =
        capture(
            "qr_scanner_permission_light",
            dark = false,
        )

    /** AMOLED preserves the scanner's black chrome with a legible permission card. */
    @Test fun permissionAmoled() =
        capture(
            "qr_scanner_permission_amoled",
            amoled = true,
        )

    /** Permanent denial names Android Settings as the actual recovery. */
    @Test fun permissionSettings() =
        capture(
            "qr_scanner_permission_settings",
            settings = true,
        )

    /** A pending Android dialog retains a fixed-time progress indicator and Close. */
    @Test fun permissionPending() =
        capture(
            "qr_scanner_permission_pending",
            pending = true,
        )

    /** Unavailable hardware shows recovery instead of a misleading scan target. */
    @Test fun cameraUnavailable() =
        capture(
            "qr_scanner_unavailable",
            granted = true,
            error = "Camera is unavailable.",
        )

    /** Flash icon and its checked treatment use the prototype asset. */
    @Test fun torchOn() =
        capture(
            "qr_scanner_torch_on",
            granted = true,
            flash = true,
        )

    /** Large RTL text remains inside the card with a scrollable recovery action. */
    @Test
    @Config(qualifiers = "ar-rEG-ldrtl-w360dp-h640dp-mdpi")
    fun permissionRtlLarge() =
        capture(
            "qr_scanner_permission_rtl_large",
            rtlLarge = true,
        )

    /** A short landscape sheet scales its target without negative or off-screen constraints. */
    @Test
    @Config(qualifiers = "en-rUS-w640dp-h360dp-land-mdpi")
    fun landscapeTarget() =
        capture(
            "qr_scanner_landscape",
            granted = true,
        )

    /** Denied permission uses the actual remaining landscape area below the scanner header. */
    @Test
    @Config(qualifiers = "en-rUS-w640dp-h300dp-land-mdpi")
    fun deniedShortLandscape() =
        capture(
            "qr_scanner_denied_short_landscape",
            recoveryVisible = true,
        )

    /** At 200% text the permission card scrolls to a full recovery action in a short landscape pane. */
    @Test
    @Config(qualifiers = "en-rUS-w640dp-h300dp-land-mdpi")
    fun deniedShortLandscapeLargeText() =
        capture(
            "qr_scanner_denied_short_landscape_large",
            largeText = true,
            recoveryVisible = true,
        )

    /** Freeze presentation animation; camera work is excluded only from deterministic screenshot capture. */
    private fun capture(
        name: String,
        granted: Boolean = false,
        dark: Boolean = true,
        amoled: Boolean = false,
        settings: Boolean = false,
        pending: Boolean = false,
        error: String? = null,
        flash: Boolean = false,
        rtlLarge: Boolean = false,
        largeText: Boolean = false,
        recoveryVisible: Boolean = false,
    ) {
        composeRule.mainClock.autoAdvance = false
        composeRule.setContent {
            CompositionLocalProvider(
                LocalLayoutDirection provides if (rtlLarge) LayoutDirection.Rtl else LayoutDirection.Ltr,
            ) {
                WhiteNoiseTheme(darkTheme = dark, amoled = amoled, fontScale = if (rtlLarge || largeText) 2f else 1f) {
                    QrScannerSheetContent(
                        permissionGranted = granted,
                        scannerError = error,
                        onDismiss = {},
                        onRequestPermission = {},
                        cameraPreview = { Box(Modifier.fillMaxSize().background(Color.DarkGray)) },
                        permissionPending = pending,
                        openSettings = settings,
                        permissionDetailRes = R.string.qr_private_key_permission_detail,
                        hasFlashUnit = flash,
                        torchEnabled = flash,
                    )
                }
            }
        }
        composeRule.mainClock.advanceTimeBy(250)
        if (recoveryVisible) {
            composeRule.mainClock.autoAdvance = true
            composeRule.onNodeWithTag("qr_scanner.recovery").performScrollTo()
            composeRule.waitForIdle()
            composeRule.mainClock.autoAdvance = false
        }
        val tag = if (error != null) "qr_scanner.error_dialog" else QR_SCANNER_SHEET_CONTENT_TAG
        composeRule.onNodeWithTag(tag).captureRoboImage("src/test/snapshots/$name.png")
    }
}
