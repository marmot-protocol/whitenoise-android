package dev.ipf.whitenoise.android.ui.account

import android.content.Context
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.LayoutDirection
import androidx.test.core.app.ApplicationProvider
import com.github.takahirom.roborazzi.captureRoboImage
import dev.ipf.marmotkit.AccountSummaryFfi
import dev.ipf.whitenoise.android.state.DraftStore
import dev.ipf.whitenoise.android.state.WhiteNoiseAppState
import dev.ipf.whitenoise.android.state.updateQuickProfileCycling
import dev.ipf.whitenoise.android.ui.chats.ChatListTopBar
import dev.ipf.whitenoise.android.ui.common.WhiteNoiseModalBottomSheet
import dev.ipf.whitenoise.android.ui.theme.WhiteNoiseTheme
import dev.ipf.whitenoise.android.ui.updateTestInfo
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Shared sheet and literal header geometry with actual theme text scaling and local-only synthetic fixtures. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "en-w360dp-h780dp-mdpi")
class ProfileSwitcherScreenshotTest {
    @get:Rule val composeRule = createComposeRule()

    @Test fun light() = capture("profile_switcher_light")

    @Test fun dark() = capture("profile_switcher_dark", dark = true)

    @Test fun amoled() = capture("profile_switcher_amoled", dark = true, amoled = true)

    @Test fun largeRtl() = capture("profile_switcher_rtl_200", dark = true, rtl = true)

    @Test fun loading() = capture("profile_switcher_loading", empty = true)

    @Test fun settingsOrigin() = capture("profile_switcher_settings_origin", settings = false)

    @Test fun headerOff() = capture("profile_header_cycle_off", header = true)

    @Test fun headerOn() = capture("profile_header_cycle_on", header = true, cycle = true)

    @Test
    @Config(sdk = [36], qualifiers = "en-w360dp-h780dp-xxhdpi")
    fun highDensity() {
        capture("profile_switcher_amoled_xxhdpi", dark = true, amoled = true)
    }

    @OptIn(ExperimentalMaterial3Api::class)
    @Suppress("LongParameterList", "LongMethod")
    private fun capture(
        name: String,
        dark: Boolean = false,
        amoled: Boolean = false,
        rtl: Boolean = false,
        empty: Boolean = false,
        settings: Boolean = true,
        header: Boolean = false,
        cycle: Boolean = false,
    ) {
        val accounts = listOf("a", "b", "c").map { AccountSummaryFfi(it, it.repeat(64), true, false, false, true) }
        val context = ApplicationProvider.getApplicationContext<Context>()
        val app =
            WhiteNoiseAppState(
                context,
                DraftStore.forContext(context),
                { null },
                accounts,
                "a",
                profileReader = { null },
                profileRefreshRequest = {},
            )
        app.updateQuickProfileCycling(cycle)
        composeRule.setContent {
            CompositionLocalProvider(
                LocalLayoutDirection provides if (rtl) LayoutDirection.Rtl else LayoutDirection.Ltr,
            ) {
                WhiteNoiseTheme(darkTheme = dark, amoled = amoled, fontScale = if (rtl) 2f else 1f) {
                    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
                        if (header) {
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
                        } else {
                            WhiteNoiseModalBottomSheet(onDismissRequest = {}) {
                                ProfileSwitcherSheet(
                                    state = accountSelectorState(if (empty) emptyList() else accounts, "a", empty),
                                    displayName = {
                                        when (it.first()) {
                                            'a' -> "Personal profile"
                                            'b' -> "A long work profile name that wraps naturally"
                                            else -> "Weekend"
                                        }
                                    },
                                    shortNpub = { "npub1…${it.takeLast(4)}" },
                                    avatarUrl = { null },
                                    unreadCountForAccount = { if (it == "b") 125uL else 0uL },
                                    hasUnreadForAccount = { it == "c" },
                                    onSelectProfile = {},
                                    onAddProfile = {},
                                    onDismiss = {},
                                    onSettings = if (settings) ({}) else null,
                                )
                            }
                        }
                    }
                }
            }
        }
        if (header) {
            composeRule.onRoot().captureRoboImage("src/test/snapshots/$name.png")
        } else {
            composeRule.onNodeWithTag("sheet.surface").captureRoboImage("src/test/snapshots/$name.png")
        }
    }
}
