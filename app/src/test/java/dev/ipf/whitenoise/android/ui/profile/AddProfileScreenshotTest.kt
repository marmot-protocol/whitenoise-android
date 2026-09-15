package dev.ipf.whitenoise.android.ui.profile

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.LayoutDirection
import com.github.takahirom.roborazzi.captureRoboImage
import dev.ipf.whitenoise.android.R
import dev.ipf.whitenoise.android.state.AppText
import dev.ipf.whitenoise.android.state.ToastMessage
import dev.ipf.whitenoise.android.ui.onboarding.OnboardingAction
import dev.ipf.whitenoise.android.ui.onboarding.assertWelcomeActionsAtBottom
import dev.ipf.whitenoise.android.ui.theme.WhiteNoiseTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Local synthetic presentation records exercise Add Profile chrome without generating identities or keys. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "en-w360dp-h780dp-mdpi")
class AddProfileScreenshotTest {
    @get:Rule val composeRule = createComposeRule()

    /** Add Profile title and exact Welcome geometry. */
    @Test fun welcomeLight() = capture("welcome_light")

    /** Dark Welcome. */
    @Test fun welcomeDark() = capture("welcome_dark", dark = true)

    /** AMOLED Welcome and outlined controls. */
    @Test fun welcomeAmoled() = capture("welcome_amoled", dark = true, amoled = true)

    /** Bounded mark and action width on a tablet. */
    @Test
    @Config(qualifiers = "en-w1000dp-h780dp-mdpi")
    fun tablet() = capture("tablet")

    /** RTL 200% controls remain scrollable in a short window. */
    @Test
    @Config(qualifiers = "en-w780dp-h360dp-mdpi")
    fun shortLargeRtl() = capture("short_rtl_200", rtl = true, scale = 2f)

    /** Shared secure Sign In and Amber alternative. */
    @Test fun signIn() = capture("sign_in", openSignIn = true)

    /** Real Amber stage two label, with no invented completion state. */
    @Test fun amberBusy() = capture("amber_busy", action = OnboardingAction.AmberLogin)

    /** Existing public-key rejection remains inline. */
    @Test fun importError() = capture("import_error", openSignIn = true, error = R.string.sign_in_error_public_key)

    /** Native-safe failure is visible above the secure full-screen flow. */
    @Test fun amberFailure() {
        composeRule.setContent {
            WhiteNoiseTheme {
                AddProfileFeedbackDialog(
                    ToastMessage(
                        AppText.Resource(R.string.toast_couldnt_login_amber),
                        AppText.Resource(R.string.error_try_again),
                        copyable = true,
                        diagnosticReport = "operation=AMBER_LOGIN",
                    ),
                ) {}
            }
        }
        composeRule.onRoot().captureRoboImage("src/test/snapshots/add_profile_amber_failure.png")
    }

    /** Renders state only; private key input remains empty and every native callback is a no-op. */
    @Suppress("LongParameterList")
    private fun capture(
        name: String,
        dark: Boolean = false,
        amoled: Boolean = false,
        rtl: Boolean = false,
        scale: Float = 1f,
        openSignIn: Boolean = false,
        action: OnboardingAction = OnboardingAction.Idle,
        error: Int? = null,
    ) {
        composeRule.setContent {
            CompositionLocalProvider(
                LocalLayoutDirection provides if (rtl) LayoutDirection.Rtl else LayoutDirection.Ltr,
            ) {
                WhiteNoiseTheme(darkTheme = dark, amoled = amoled, fontScale = scale) {
                    AddAccountSheetContent(
                        amberSignerAvailable = true,
                        inFlightAction = action,
                        identity = "",
                        importErrorRes = error,
                        onCreate = {},
                        onLoginWithAmber = {},
                        onIdentityChange = {},
                        onErrorChange = {},
                        onImport = {},
                        amberSignInStage = 2,
                    )
                }
            }
        }
        if (openSignIn) {
            composeRule.onNodeWithTag("onboarding.welcome.sign_in").performClick()
            composeRule.onNodeWithTag("onboarding.sign_in.private_key").assertIsDisplayed()
            composeRule.waitForIdle()
        }
        if (!openSignIn && action == OnboardingAction.Idle) composeRule.assertWelcomeActionsAtBottom()
        composeRule.mainClock.autoAdvance = false
        composeRule.mainClock.advanceTimeByFrame()
        composeRule.onRoot().captureRoboImage("src/test/snapshots/add_profile_$name.png")
    }
}
