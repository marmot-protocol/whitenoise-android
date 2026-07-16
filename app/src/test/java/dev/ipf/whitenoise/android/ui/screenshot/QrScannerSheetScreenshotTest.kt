package dev.ipf.whitenoise.android.ui.screenshot

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import com.github.takahirom.roborazzi.captureRoboImage
import dev.ipf.whitenoise.android.ui.qr.QR_SCANNER_SHEET_CONTENT_TAG
import dev.ipf.whitenoise.android.ui.qr.QrScannerSheetContent
import dev.ipf.whitenoise.android.ui.theme.WhiteNoiseTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Baseline for the QR scanner sheet's idle state with camera permission granted
 * and a stubbed preview (no CameraX / ML Kit). The content composable is
 * captured inside a fixed compact-phone frame so the committed PNG stays
 * deterministic.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "w360dp-h640dp-mdpi")
class QrScannerSheetScreenshotTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun qrScannerSheetIdleDark() {
        composeRule.setContent {
            WhiteNoiseTheme(darkTheme = true) {
                Surface(modifier = Modifier.fillMaxSize()) {
                    QrScannerSheetContent(
                        permissionGranted = true,
                        scannerError = null,
                        onDismiss = {},
                        onRequestPermission = {},
                        cameraPreview = {
                            Box(Modifier.fillMaxSize().background(Color.DarkGray))
                        },
                    )
                }
            }
        }
        composeRule
            .onNodeWithTag(QR_SCANNER_SHEET_CONTENT_TAG)
            .captureRoboImage("src/test/snapshots/qr_scanner_sheet_idle_dark.png")
    }
}
