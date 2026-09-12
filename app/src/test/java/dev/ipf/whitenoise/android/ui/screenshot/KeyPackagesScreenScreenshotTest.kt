package dev.ipf.whitenoise.android.ui.screenshot

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import com.github.takahirom.roborazzi.captureRoboImage
import dev.ipf.marmotkit.AccountKeyPackageFfi
import dev.ipf.whitenoise.android.ui.settings.KEY_PACKAGES_CONTENT_TAG
import dev.ipf.whitenoise.android.ui.settings.KeyPackagesContent
import dev.ipf.whitenoise.android.ui.settings.keyPackagesState
import dev.ipf.whitenoise.android.ui.theme.WhiteNoiseTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.util.TimeZone

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "w360dp-h780dp-mdpi")
class KeyPackagesScreenScreenshotTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun keyPackagesScreenDefaultDark() {
        capture(packages = emptyList(), path = "src/test/snapshots/key_packages_screen_default_dark.png")
    }

    @Test
    fun keyPackagesScreenWithRetainedLocalMaterialDark() {
        val published =
            keyPackage(
                keyPackageRefHex = "34".repeat(32),
                eventIdHex = "ab".repeat(32),
                relay = true,
            )
        val retained =
            keyPackage(
                keyPackageRefHex = "56".repeat(32),
                eventIdHex = "",
                relay = false,
            )

        capture(
            packages = listOf(retained, published),
            path = "src/test/snapshots/key_packages_screen_retained_local_dark.png",
        )
    }

    /** Empty state with the prototype's publication layout and retained-material section. */
    @Test
    fun keyPackagesEmptyLight() {
        capture(emptyList(), "src/test/snapshots/key_packages_empty_light.png", dark = false)
    }

    /** Monochrome outlines retain the full published provenance and a distinct local-only section. */
    @Test
    @Config(sdk = [36], qualifiers = "w360dp-h1600dp-mdpi")
    fun keyPackagesPublishedAndRetainedAmoled() {
        val published = keyPackage("34".repeat(32), "ab".repeat(32), true)
        val retained = keyPackage("56".repeat(32), "", false)
        capture(
            listOf(published, retained),
            "src/test/snapshots/key_packages_published_retained_amoled.png",
            amoled = true,
        )
    }

    /** Publication labels and helpers wrap at a narrow RTL width with 200 percent text. */
    @Test
    @Config(sdk = [36], qualifiers = "w320dp-h1600dp-mdpi")
    fun keyPackagesEmptyRtlLargeFont() {
        capture(emptyList(), "src/test/snapshots/key_packages_empty_rtl_large_font.png", rtl = true, fontScale = 2f)
    }

    private fun capture(
        packages: List<AccountKeyPackageFfi>,
        path: String,
        dark: Boolean = true,
        amoled: Boolean = false,
        rtl: Boolean = false,
        fontScale: Float = 1f,
    ) {
        val originalTimeZone = TimeZone.getDefault()
        try {
            TimeZone.setDefault(TimeZone.getTimeZone("UTC"))
            composeRule.setContent {
                CompositionLocalProvider(
                    LocalDensity provides Density(1f, fontScale),
                    LocalLayoutDirection provides if (rtl) LayoutDirection.Rtl else LayoutDirection.Ltr,
                ) {
                    WhiteNoiseTheme(darkTheme = dark, amoled = amoled) {
                        Surface(modifier = Modifier.fillMaxSize()) {
                            KeyPackagesContent(
                                state =
                                    keyPackagesState(
                                        hasActiveAccount = true,
                                        loaded = true,
                                        loading = false,
                                        working = false,
                                        packageCount = packages.count { it.relay },
                                    ),
                                packages = packages,
                                onBack = {},
                                onRefresh = {},
                                onRepublish = {},
                                onPublishNew = {},
                                onDelete = {},
                            )
                        }
                    }
                }
            }

            composeRule
                .onNodeWithTag(KEY_PACKAGES_CONTENT_TAG)
                .captureRoboImage(path)
        } finally {
            TimeZone.setDefault(originalTimeZone)
        }
    }

    private fun keyPackage(
        keyPackageRefHex: String,
        eventIdHex: String,
        relay: Boolean,
    ) = AccountKeyPackageFfi(
        accountRef = "account",
        accountIdHex = "12".repeat(32),
        keyPackageId = "stable-package-slot",
        keyPackageRefHex = keyPackageRefHex,
        eventIdHex = eventIdHex,
        publishedAt = 1_700_000_000uL,
        keyPackageBytes = 128uL,
        sourceRelays = if (relay) listOf("wss://relay.example") else emptyList(),
        local = true,
        relay = relay,
    )
}
