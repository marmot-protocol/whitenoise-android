package dev.ipf.whitenoise.android.ui.onboarding

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.LayoutDirection
import com.github.takahirom.roborazzi.captureRoboImage
import dev.ipf.whitenoise.android.R
import dev.ipf.whitenoise.android.ui.theme.WhiteNoiseTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Welcome, retained-account recovery and masked Sign In across theme and adaptive states. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "en-rUS-w360dp-h780dp-mdpi")
class WelcomeScreenScreenshotTest {
    @get:Rule val composeRule = createComposeRule()

    /** Initial full-screen mark and outlined/filled actions. */
    @Test fun welcomeLight() = render("welcome_light")

    /** Dark theme mark tint. */
    @Test fun welcomeDark() = render("welcome_dark", dark = true)

    /** AMOLED primary outline. */
    @Test fun welcomeAmoled() = render("welcome_amoled", dark = true, amoled = true)

    /** Retained identities expose Continue and Choose profile. */
    @Test fun retainedProfilesLight() = render("welcome_retained_profiles_light", retained = true)

    /** Active retained-account continuation names the real in-flight operation. */
    @Test fun retainedBusyLight() = render("welcome_retained_busy_light", retained = true, busy = true)

    /** Offline setup retry remains in the scrolling action column. */
    @Test fun welcomeOfflineLight() = render("welcome_offline_light", offline = true)

    /** Explicit chooser uses the shared sheet and 48 dp identity rows. */
    @Test fun retainedProfilesSheetLight() {
        render("welcome_retained_before_sheet", retained = true)
        composeRule.onNodeWithText("Choose profile").performClick()
        composeRule.onNode(hasText("Bob") and hasAnyAncestor(isDialog())).assertIsDisplayed()
        composeRule
            .onNode(isDialog())
            .captureRoboImage("src/test/snapshots/welcome_retained_profiles_sheet_light.png")
    }

    /** 520 dp content cap in a tablet-width window. */
    @Config(qualifiers = "en-rUS-w1000dp-h780dp-mdpi")
    @Test
    fun welcomeTablet() = render("welcome_tablet")

    /** Welcome with retained profiles at 200% text and RTL. */
    @Test fun welcomeRtlLargeText() = render("welcome_retained_rtl_200", retained = true, rtl = true, scale = 2f)

    /** Private key form and pinned action. */
    @Test fun signInLight() = render("sign_in_light", signIn = true)

    /** Actual second Amber prompt label. */
    @Test fun signInAmberBusyDark() {
        render(
            "sign_in_amber_busy_dark",
            signIn = true,
            amber = true,
            busy = true,
            dark = true,
        )
    }

    /** Inline invalid key and outlined actions on true black. */
    @Test fun signInInvalidAmoled() {
        render(
            "sign_in_invalid_amoled",
            signIn = true,
            error = true,
            dark = true,
            amoled = true,
        )
    }

    /** Form wrapping at 200% with the optional signer action. */
    @Test fun signInRtlLargeText() = render("sign_in_rtl_200", signIn = true, amber = true, rtl = true, scale = 2f)

    private fun render(
        name: String,
        dark: Boolean = false,
        amoled: Boolean = false,
        retained: Boolean = false,
        busy: Boolean = false,
        offline: Boolean = false,
        signIn: Boolean = false,
        amber: Boolean = false,
        error: Boolean = false,
        rtl: Boolean = false,
        scale: Float = 1f,
    ) {
        composeRule.setContent {
            CompositionLocalProvider(
                LocalLayoutDirection provides if (rtl) LayoutDirection.Rtl else LayoutDirection.Ltr,
            ) {
                WhiteNoiseTheme(darkTheme = dark, amoled = amoled, fontScale = scale) {
                    if (signIn) {
                        SignInContent(
                            identity = if (error) "invalid" else "",
                            busy = busy,
                            errorRes = if (error) R.string.identity_entry_error_invalid_key else null,
                            onIdentityChange = {},
                            onErrorChange = {},
                            onBack = {},
                            onSignIn = {},
                            amberSignerAvailable = amber,
                            loggingInWithAmber = amber && busy,
                            amberSignInStage = 2,
                        )
                    } else {
                        OnboardingContent(
                            "",
                            false,
                            false,
                            {},
                            {},
                            {},
                            offlineErrorVisible = offline,
                            savedAccounts = if (retained) listOf(saved("Alice"), saved("Bob")) else emptyList(),
                            reactivatingAccountLabel = if (busy) "Alice" else null,
                        )
                    }
                }
            }
        }
        composeRule.onRoot().captureRoboImage("src/test/snapshots/$name.png")
    }

    private fun saved(name: String) = OnboardingSavedAccountUi(name, name, name, "npub1…$name", null)
}
