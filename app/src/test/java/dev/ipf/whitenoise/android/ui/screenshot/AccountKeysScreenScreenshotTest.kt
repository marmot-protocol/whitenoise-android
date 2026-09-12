package dev.ipf.whitenoise.android.ui.screenshot

import android.content.Context
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.test.core.app.ApplicationProvider
import com.github.takahirom.roborazzi.captureRoboImage
import dev.ipf.marmotkit.AccountSummaryFfi
import dev.ipf.whitenoise.android.state.BoundedNpubCache
import dev.ipf.whitenoise.android.state.DraftStore
import dev.ipf.whitenoise.android.state.WhiteNoiseAppState
import dev.ipf.whitenoise.android.ui.settings.AccountKeysScreen
import dev.ipf.whitenoise.android.ui.settings.WIPE_ACTION_TAG
import dev.ipf.whitenoise.android.ui.theme.WhiteNoiseTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Profile Keys pinned in every theme, at RTL 200 % scrolled to the wipe action, and for an Amber-signed account. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "en-w360dp-h780dp-mdpi")
class AccountKeysScreenScreenshotTest {
    @get:Rule
    val composeRule = createComposeRule()

    private val app = ApplicationProvider.getApplicationContext<Context>()

    /** Light theme, top of the screen. */
    @Test
    fun profileKeysLight() = capture("profile_keys_light", darkTheme = false)

    /** Dark theme, top of the screen. */
    @Test
    fun profileKeysDark() = capture("profile_keys_dark", darkTheme = true)

    /** AMOLED: outlined groups and the destructive wipe row. */
    @Test
    fun profileKeysAmoled() = capture("profile_keys_amoled", darkTheme = true, amoled = true)

    /** RTL at 200 %, scrolled to the terminal wipe action. */
    @Test
    fun profileKeysDarkLargeRtlAtWipe() =
        capture(
            "profile_keys_dark_large_rtl",
            darkTheme = true,
            fontScale = 2f,
            layoutDirection = LayoutDirection.Rtl,
            scrollToWipe = true,
        )

    /** Amber-signed account: the callout replaces the private key and export groups. */
    @Test
    fun profileKeysAmberLight() = capture("profile_keys_amber_light", darkTheme = false, localSigning = false)

    private fun capture(
        snapshotName: String,
        darkTheme: Boolean,
        amoled: Boolean = false,
        fontScale: Float = 1f,
        layoutDirection: LayoutDirection = LayoutDirection.Ltr,
        scrollToWipe: Boolean = false,
        localSigning: Boolean = true,
    ) {
        val appState = accountState(localSigning)
        composeRule.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(
                LocalDensity provides Density(density.density, fontScale),
                LocalLayoutDirection provides layoutDirection,
            ) {
                WhiteNoiseTheme(darkTheme = darkTheme, amoled = amoled) {
                    AccountKeysScreen(appState = appState, onBack = {})
                }
            }
        }
        if (scrollToWipe) composeRule.onNode(hasScrollAction()).performScrollToNode(hasTestTag(WIPE_ACTION_TAG))
        composeRule.onRoot().captureRoboImage("src/test/snapshots/$snapshotName.png")
    }

    private fun accountState(localSigning: Boolean): WhiteNoiseAppState {
        val appState =
            WhiteNoiseAppState(
                context = app,
                draftStore = DraftStore.forContext(app),
                accountIdHexResolver = { null },
                accounts =
                    listOf(
                        AccountSummaryFfi(
                            label = "personal",
                            accountIdHex = ACCOUNT_HEX,
                            localSigning = localSigning,
                            externalSigning = !localSigning,
                            signedOut = false,
                            running = true,
                        ),
                    ),
                activeAccountRef = "personal",
            )
        val field = WhiteNoiseAppState::class.java.getDeclaredField("npubs")
        field.isAccessible = true
        (field.get(appState) as BoundedNpubCache).put(ACCOUNT_HEX, NPUB)
        return appState
    }

    private companion object {
        const val ACCOUNT_HEX = "0101010101010101010101010101010101010101010101010101010101010101"
        const val NPUB = "npub1qy352hw5xrsq5k6x5t5vnpqx4lhfv3q8jqk9x0h5q6x5t5vnpq"
    }
}
