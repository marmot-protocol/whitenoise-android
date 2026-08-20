package dev.ipf.whitenoise.android.ui.screenshot

import android.content.Context
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.test.core.app.ApplicationProvider
import com.github.takahirom.roborazzi.captureRoboImage
import dev.ipf.marmotkit.AccountSummaryFfi
import dev.ipf.whitenoise.android.R
import dev.ipf.whitenoise.android.state.DraftStore
import dev.ipf.whitenoise.android.state.WhiteNoiseAppState
import dev.ipf.whitenoise.android.ui.settings.AccountKeysScreen
import dev.ipf.whitenoise.android.ui.theme.WhiteNoiseTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "en-w360dp-h780dp-mdpi")
class AccountKeysScreenScreenshotTest {
    @get:Rule
    val composeRule = createComposeRule()

    private val app = ApplicationProvider.getApplicationContext<Context>()

    @Test
    fun terminalActionsDefaultLight() =
        capture(
            snapshotName = "account_keys_terminal_actions_light",
            darkTheme = false,
            fontScale = 1f,
            layoutDirection = LayoutDirection.Ltr,
        )

    @Test
    fun terminalActionsDarkLargeRtl() =
        capture(
            snapshotName = "account_keys_terminal_actions_dark_large_rtl",
            darkTheme = true,
            fontScale = 2f,
            layoutDirection = LayoutDirection.Rtl,
        )

    private fun capture(
        snapshotName: String,
        darkTheme: Boolean,
        fontScale: Float,
        layoutDirection: LayoutDirection,
    ) {
        val appState = accountState()
        composeRule.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(
                LocalDensity provides Density(density.density, fontScale),
                LocalLayoutDirection provides layoutDirection,
            ) {
                WhiteNoiseTheme(darkTheme = darkTheme) {
                    Surface(modifier = Modifier.fillMaxSize()) {
                        AccountKeysScreen(appState = appState, onBack = {})
                    }
                }
            }
        }
        composeRule
            .onNode(hasScrollAction())
            .performScrollToNode(hasText(app.getString(R.string.sign_out_and_wipe)))
        composeRule.onRoot().captureRoboImage("src/test/snapshots/$snapshotName.png")
    }

    private fun accountState() =
        WhiteNoiseAppState(
            context = app,
            draftStore = DraftStore.forContext(app),
            accountIdHexResolver = { null },
            accounts =
                listOf(
                    AccountSummaryFfi(
                        label = "personal",
                        accountIdHex = "01".repeat(32),
                        localSigning = true,
                        externalSigning = false,
                        signedOut = false,
                        running = true,
                    ),
                ),
            activeAccountRef = "personal",
        )
}
