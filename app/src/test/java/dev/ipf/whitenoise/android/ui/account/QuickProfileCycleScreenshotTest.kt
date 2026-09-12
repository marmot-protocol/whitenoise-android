package dev.ipf.whitenoise.android.ui.account

import android.content.Context
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.LayoutDirection
import androidx.test.core.app.ApplicationProvider
import com.github.takahirom.roborazzi.captureRoboImage
import dev.ipf.marmotkit.AccountSummaryFfi
import dev.ipf.whitenoise.android.state.DraftStore
import dev.ipf.whitenoise.android.state.WhiteNoiseAppState
import dev.ipf.whitenoise.android.state.updateQuickProfileCycling
import dev.ipf.whitenoise.android.ui.chats.ChatListTopBar
import dev.ipf.whitenoise.android.ui.settings.AppearanceScreen
import dev.ipf.whitenoise.android.ui.theme.WhiteNoiseTheme
import dev.ipf.whitenoise.android.ui.updateTestInfo
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Native cycle target, retained stack/update emblem and Appearance toggle using local test identities. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "en-w360dp-h780dp-mdpi")
class QuickProfileCycleScreenshotTest {
    @get:Rule val composeRule = createComposeRule()

    @Test fun light() = capture("profile_cycle_light")

    @Test fun dark() = capture("profile_cycle_dark", dark = true)

    @Test fun amoled() = capture("profile_cycle_amoled", dark = true, amoled = true)

    @Test
    @Config(sdk = [36], qualifiers = "en-w360dp-h780dp-xxhdpi")
    fun highDensity() = capture("profile_cycle_amoled_xxhdpi", dark = true, amoled = true)

    @Test fun largeRtl() = capture("profile_cycle_rtl_200", dark = true, rtl = true)

    @Test fun off() = capture("profile_cycle_off", enabled = false)

    @Test fun appearance() = capture("profile_cycle_appearance_light", settings = true, enabled = false)

    @Test
    fun appearanceAmoledRtl() {
        capture("profile_cycle_appearance_amoled_rtl", dark = true, amoled = true, rtl = true, settings = true)
    }

    @Suppress("LongParameterList")
    private fun capture(
        name: String,
        dark: Boolean = false,
        amoled: Boolean = false,
        rtl: Boolean = false,
        settings: Boolean = false,
        enabled: Boolean = true,
    ) {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val app =
            WhiteNoiseAppState(
                context,
                DraftStore.forContext(context),
                { null },
                listOf(
                    AccountSummaryFfi("a", "aa".repeat(32), true, false, false, true),
                    AccountSummaryFfi("b", "bb".repeat(32), true, false, false, true),
                ),
                "a",
                profileReader = { null },
                profileRefreshRequest = {},
            )
        app.updateQuickProfileCycling(enabled)
        app.updateAccountUnreadCount("b", 3uL)
        composeRule.setContent {
            CompositionLocalProvider(
                LocalLayoutDirection provides if (rtl) LayoutDirection.Rtl else LayoutDirection.Ltr,
            ) {
                WhiteNoiseTheme(darkTheme = dark, amoled = amoled, fontScale = if (rtl) 2f else 1f) {
                    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
                        if (settings) {
                            AppearanceScreen(app, {}, {}, {}, {})
                        } else {
                            Column {
                                ChatListTopBar(
                                    app,
                                    false,
                                    "",
                                    remember { FocusRequester() },
                                    {},
                                    {},
                                    {},
                                    {},
                                    {},
                                    {},
                                    onCycleAccount = {},
                                    updateInfo = updateTestInfo(),
                                    selfUpdateEnabled = true,
                                )
                            }
                        }
                    }
                }
            }
        }
        composeRule.onRoot().captureRoboImage("src/test/snapshots/$name.png")
    }
}
