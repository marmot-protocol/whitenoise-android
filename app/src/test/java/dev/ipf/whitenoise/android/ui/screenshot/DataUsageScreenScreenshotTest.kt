package dev.ipf.whitenoise.android.ui.screenshot

import android.content.Context
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.LayoutDirection
import androidx.test.core.app.ApplicationProvider
import com.github.takahirom.roborazzi.captureRoboImage
import dev.ipf.marmotkit.AccountSummaryFfi
import dev.ipf.whitenoise.android.state.DraftStore
import dev.ipf.whitenoise.android.state.WhiteNoiseAppState
import dev.ipf.whitenoise.android.ui.settings.DataUsageScreen
import dev.ipf.whitenoise.android.ui.theme.WhiteNoiseTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Data Usage at the shipped defaults: media rows with their network summaries, queue action and media quality. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "w360dp-h1000dp-mdpi")
class DataUsageScreenScreenshotTest {
    @get:Rule
    val composeRule = createComposeRule()

    private val context: Context = ApplicationProvider.getApplicationContext()

    /** Light theme. */
    @Test
    fun dataUsageLight() = capture("data_usage_light", darkTheme = false)

    /** Dark theme. */
    @Test
    fun dataUsageDark() = capture("data_usage_dark", darkTheme = true)

    /** AMOLED: outlined groups on black. */
    @Test
    fun dataUsageAmoled() = capture("data_usage_amoled", darkTheme = true, amoled = true)

    /** RTL at 200 %: wrapping summaries and mirrored chevrons. */
    @Test
    fun dataUsageRtlLargeFont() {
        capture("data_usage_rtl_large_font", darkTheme = false, fontScale = 2f, layoutDirection = LayoutDirection.Rtl)
    }

    /** Renders the screen for one account on cleared preferences and records the window. */
    private fun capture(
        name: String,
        darkTheme: Boolean,
        amoled: Boolean = false,
        fontScale: Float = 1f,
        layoutDirection: LayoutDirection = LayoutDirection.Ltr,
    ) {
        val appState = testAppState(name)
        composeRule.setContent {
            CompositionLocalProvider(LocalLayoutDirection provides layoutDirection) {
                WhiteNoiseTheme(darkTheme = darkTheme, amoled = amoled, fontScale = fontScale) {
                    DataUsageScreen(appState = appState, onBack = {})
                }
            }
        }
        composeRule.waitForIdle()
        composeRule.onRoot().captureRoboImage("src/test/snapshots/$name.png")
    }

    /** One signed-in account on its own cleared preference file, so the matrix is the shipped default. */
    private fun testAppState(name: String): WhiteNoiseAppState {
        val preferences = context.getSharedPreferences("data-usage-screenshot-$name", Context.MODE_PRIVATE)
        preferences.edit().clear().commit()
        return WhiteNoiseAppState(
            context = context,
            draftStore = DraftStore.forContext(context),
            accountIdHexResolver = { null },
            accounts =
                listOf(
                    AccountSummaryFfi(
                        label = ACCOUNT_REF,
                        accountIdHex = "ab".repeat(32),
                        localSigning = true,
                        externalSigning = false,
                        signedOut = false,
                        running = true,
                    ),
                ),
            activeAccountRef = ACCOUNT_REF,
            preferences = preferences,
        )
    }

    private companion object {
        const val ACCOUNT_REF = "account-a"
    }
}
