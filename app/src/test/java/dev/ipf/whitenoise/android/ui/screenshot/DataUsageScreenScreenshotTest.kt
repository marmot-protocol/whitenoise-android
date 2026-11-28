package dev.ipf.whitenoise.android.ui.screenshot

import android.content.Context
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.unit.LayoutDirection
import androidx.test.core.app.ApplicationProvider
import com.github.takahirom.roborazzi.captureRoboImage
import dev.ipf.marmotkit.AccountSummaryFfi
import dev.ipf.whitenoise.android.R
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

    @Test
    fun dataUsagePreviewLight() = capture("data_usage_preview_light", darkTheme = false, preview = true)

    @Test
    fun dataUsagePreviewDark() = capture("data_usage_preview_dark", darkTheme = true, preview = true)

    @Test
    fun dataUsagePreviewAmoled() = capture("data_usage_preview_amoled", darkTheme = true, amoled = true, preview = true)

    @Test
    fun dataUsagePreviewRtlLargeFont() =
        capture(
            "data_usage_preview_rtl_large",
            darkTheme = false,
            fontScale = 2f,
            layoutDirection = LayoutDirection.Rtl,
            preview = true,
        )

    /** Videos' network dialog at the defaults: Wi-Fi on with a filled row, the other networks off. */
    @Test
    fun dataUsageNetworkDialogLight() {
        capture("data_usage_network_dialog_light", darkTheme = false, networkDialog = true)
    }

    /** The network dialog in AMOLED. */
    @Test
    fun dataUsageNetworkDialogAmoled() {
        capture("data_usage_network_dialog_amoled", darkTheme = true, amoled = true, networkDialog = true)
    }

    /** The network dialog in RTL at 200 %, where switch rows grow with their titles. */
    @Test
    fun dataUsageNetworkDialogRtlLargeFont() =
        capture(
            "data_usage_network_dialog_rtl_large",
            darkTheme = false,
            fontScale = 2f,
            layoutDirection = LayoutDirection.Rtl,
            networkDialog = true,
        )

    /**
     * Renders the screen for one account on cleared preferences and records the window, or, with [networkDialog],
     * opens Videos' network dialog and records the dialog.
     */
    @Suppress("LongParameterList")
    private fun capture(
        name: String,
        darkTheme: Boolean,
        amoled: Boolean = false,
        fontScale: Float = 1f,
        layoutDirection: LayoutDirection = LayoutDirection.Ltr,
        preview: Boolean = false,
        networkDialog: Boolean = false,
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
        if (preview) {
            composeRule
                .onNodeWithTag("settings.list")
                .performScrollToNode(hasTestTag("data_usage.public_jpeg_previews"))
        }
        if (networkDialog) {
            composeRule.onNodeWithText(context.getString(R.string.download_videos)).performClick()
            composeRule.waitForIdle()
            composeRule.onNode(isDialog()).captureRoboImage("src/test/snapshots/$name.png")
            return
        }
        composeRule.onRoot().captureRoboImage("src/test/snapshots/$name.png")
    }

    /** One signed-in account on its own cleared preference file, so the matrix is the shipped default. */
    private fun testAppState(name: String): WhiteNoiseAppState {
        context
            .getSharedPreferences("whitenoise.public_jpeg_previews", Context.MODE_PRIVATE)
            .edit()
            .clear()
            .commit()
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
                        unlinked = false,
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
