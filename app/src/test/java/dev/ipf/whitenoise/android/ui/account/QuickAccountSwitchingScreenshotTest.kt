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
import dev.ipf.whitenoise.android.state.updateQuickAccountSwitching
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

/** The other-account avatars, the retained update emblem and the Appearance toggle, on local test identities. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "en-w360dp-h780dp-mdpi")
class QuickAccountSwitchingScreenshotTest {
    @get:Rule val composeRule = createComposeRule()

    /** Screenshot: light theme. */
    @Test fun light() = capture("quick_account_switching_light")

    /** Screenshot: dark theme. */
    @Test fun dark() = capture("quick_account_switching_dark", dark = true)

    /** Screenshot: AMOLED. */
    @Test fun amoled() = capture("quick_account_switching_amoled", dark = true, amoled = true)

    /** Density fixture for a high-density render. */
    @Test
    @Config(sdk = [36], qualifiers = "en-w360dp-h780dp-xxhdpi")
    fun highDensity() = capture("quick_account_switching_amoled_xxhdpi", dark = true, amoled = true)

    /** Right-to-left at double type: the stack mirrors and stays clear of the actions. */
    @Test fun largeRtl() = capture("quick_account_switching_rtl_200", dark = true, rtl = true)

    /** Off: the active avatar stands alone with no other accounts beside it. */
    @Test fun off() = capture("quick_account_switching_off", enabled = false)

    /** The Appearance row after the user turns off quick switching. */
    @Test fun appearance() = capture("quick_account_switching_appearance_light", settings = true, enabled = false)

    /** The default-on Appearance preference in AMOLED right-to-left. */
    @Test
    fun appearanceAmoledRtl() {
        capture(
            "quick_account_switching_appearance_amoled_rtl",
            dark = true,
            amoled = true,
            rtl = true,
            settings = true,
        )
    }

    /** Renders the fixture and records its screenshot baseline. */
    @Suppress("LongParameterList")
    private fun capture(
        name: String,
        dark: Boolean = false,
        amoled: Boolean = false,
        rtl: Boolean = false,
        settings: Boolean = false,
        enabled: Boolean? = null,
    ) {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val preferences =
            context.getSharedPreferences("quick-account-switching-screenshot-$name", Context.MODE_PRIVATE).also {
                it.edit().clear().commit()
            }
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
                preferences = preferences,
                profileReader = { null },
                profileRefreshRequest = {},
            )
        enabled?.let(app::updateQuickAccountSwitching)
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
                                    onSwitchToAccount = {},
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
