package dev.ipf.whitenoise.android.ui.screenshot

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import com.github.takahirom.roborazzi.captureRoboImage
import dev.ipf.whitenoise.android.ui.settings.ShareConnectContent
import dev.ipf.whitenoise.android.ui.settings.shareConnectFixture
import dev.ipf.whitenoise.android.ui.theme.WhiteNoiseTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Share & Connect pinned in every theme plus RTL at 200 %, and with the invalid-scan message. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "en-rUS-w360dp-h780dp-mdpi")
class ShareConnectScreenScreenshotTest {
    @get:Rule
    val composeRule = createComposeRule()

    /** Light theme, resting state. */
    @Test
    fun shareConnectLight() {
        render(darkTheme = false)
        capture("light")
    }

    /** Dark theme, resting state. */
    @Test
    fun shareConnectDark() {
        render(darkTheme = true)
        capture("dark")
    }

    /** AMOLED: black canvas, outlined scan button, white QR card. */
    @Test
    fun shareConnectAmoled() {
        render(darkTheme = true, amoled = true)
        capture("amoled")
    }

    /** RTL at a 200 % font scale keeps the column centered and the capsule within its 240 dp visual. */
    @Test
    fun shareConnectRtlLargeFont() {
        render(darkTheme = false, fontScale = 2f, layoutDirection = LayoutDirection.Rtl)
        capture("rtl_large_font")
    }

    /** Copied state and the invalid-scan message together. */
    @Test
    fun shareConnectCopiedWithInvalidScan() {
        render(darkTheme = false, copied = true, scanInvalid = true)
        capture("copied_invalid_scan")
    }

    private fun render(
        darkTheme: Boolean,
        amoled: Boolean = false,
        fontScale: Float = 1f,
        layoutDirection: LayoutDirection = LayoutDirection.Ltr,
        copied: Boolean = false,
        scanInvalid: Boolean = false,
    ) {
        composeRule.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(
                LocalDensity provides Density(density.density, fontScale),
                LocalLayoutDirection provides layoutDirection,
            ) {
                WhiteNoiseTheme(darkTheme = darkTheme, amoled = amoled) {
                    ShareConnectContent(
                        profile = shareConnectFixture,
                        qrContent = "marmot://profile/${shareConnectFixture.npub}?from=qr",
                        copied = copied,
                        scanInvalid = scanInvalid,
                        onBack = {},
                        onShare = {},
                        onCopy = {},
                        onOpenScanner = {},
                    )
                }
            }
        }
    }

    private fun capture(variant: String) {
        composeRule
            .onNodeWithTag("share_connect.screen")
            .captureRoboImage("src/test/snapshots/share_connect_$variant.png")
    }
}
