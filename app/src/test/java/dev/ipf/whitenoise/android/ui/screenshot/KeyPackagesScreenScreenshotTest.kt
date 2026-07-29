package dev.ipf.whitenoise.android.ui.screenshot

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
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

    private fun capture(
        packages: List<AccountKeyPackageFfi>,
        path: String,
    ) {
        val originalTimeZone = TimeZone.getDefault()
        try {
            TimeZone.setDefault(TimeZone.getTimeZone("UTC"))
            composeRule.setContent {
                WhiteNoiseTheme(darkTheme = true) {
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
