package dev.ipf.whitenoise.android.ui.onboarding

import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Text
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.LayoutDirection
import com.github.takahirom.roborazzi.captureRoboImage
import com.github.takahirom.roborazzi.captureScreenRoboImage
import dev.ipf.whitenoise.android.ui.common.ExplanationHost
import dev.ipf.whitenoise.android.ui.theme.WhiteNoiseTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Profile form geometry and truthful partial-publication states with no native side effects. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "en-rUS-w360dp-h780dp-mdpi")
class SignUpScreenshotTest {
    @get:Rule val composeRule = createComposeRule()

    /** Initial light form. */
    @Test fun light() = render("signup_light")

    /** A fresh account begins without a name but keeps the dice beside the editable field. */
    @Test fun emptyName() = render("signup_empty_name", initialName = "")

    /** Dark theme neutral avatar and tonal fields. */
    @Test fun dark() = render("signup_dark", dark = true)

    /** True black form and photo action outlines. */
    @Test fun amoled() = render("signup_amoled", dark = true, amoled = true, initialAbout = "")

    /** Bounded content column at tablet width. */
    @Config(qualifiers = "en-rUS-w1000dp-h780dp-mdpi")
    @Test
    fun tablet() = render("signup_tablet")

    /** Focused empty About and retained pinned action at 200 percent RTL. */
    @Test fun rtlLargeText() = render("signup_rtl_200", rtl = true, scale = 2f, initialAbout = "", focusAbout = true)

    /** Failed profile stage describes the accepted identity and same-account retry. */
    @Test fun publicationFailure() = render("signup_publication_failed", stage = SignUpStage.PublishFailed)

    /** Offline retains fields and the explicit retry action. */
    @Test fun offline() = render("signup_offline", offline = true)

    /** Native upload stage blocks editing and names actual work. */
    @Test fun uploadingPhoto() = render("signup_uploading", stage = SignUpStage.UploadingPhoto)

    @Test
    fun generatedNameHelp() =
        render(
            "signup_generated_name_help",
            initialName = "Quiet Otter",
            showNameHelp = true,
        )

    @Test
    fun generatedNameHelpRtlLargeText() =
        render(
            "signup_generated_name_help_rtl_200",
            dark = true,
            rtl = true,
            scale = 2f,
            initialName = "Quiet Otter",
            showNameHelp = true,
        )

    private fun render(
        name: String,
        dark: Boolean = false,
        amoled: Boolean = false,
        rtl: Boolean = false,
        scale: Float = 1f,
        offline: Boolean = false,
        stage: SignUpStage = SignUpStage.Editing,
        initialName: String = "Alice",
        initialAbout: String = "A local profile description",
        showNameHelp: Boolean = false,
        focusAbout: Boolean = false,
    ) {
        composeRule.mainClock.autoAdvance = showNameHelp
        composeRule.setContent {
            CompositionLocalProvider(
                LocalLayoutDirection provides if (rtl) LayoutDirection.Rtl else LayoutDirection.Ltr,
            ) {
                WhiteNoiseTheme(darkTheme = dark, amoled = amoled, fontScale = scale) {
                    ExplanationHost {
                        SignUpContent(
                            TextFieldState(initialName),
                            TextFieldState(initialAbout),
                            null,
                            stage,
                            editable = stage == SignUpStage.Editing,
                            preparingPhoto = false,
                            offline = offline,
                            onSuggestName = {},
                            onSubmit = {},
                            onContinueWithoutProfile = {},
                            onBack = {},
                            photoControls = {
                                FilledTonalButton(
                                    onClick = {},
                                    enabled = !stage.busy,
                                    border =
                                        dev.ipf.whitenoise.android.ui.theme
                                            .amoledOutlineBorder(!stage.busy),
                                ) { Text("Add Photo") }
                            },
                        )
                    }
                }
            }
        }
        composeRule.mainClock.advanceTimeBy(160)
        if (focusAbout) {
            composeRule.onNodeWithTag("onboarding.sign_up.about").performSemanticsAction(SemanticsActions.RequestFocus)
            composeRule.mainClock.advanceTimeBy(160)
        }
        if (showNameHelp) {
            composeRule.onNodeWithTag("onboarding.sign_up.suggest_name").performTouchInput { longClick() }
            composeRule.onNodeWithTag("explanation.sheet").assertIsDisplayed()
            captureScreenRoboImage("src/test/snapshots/$name.png")
        } else {
            composeRule.onRoot().captureRoboImage("src/test/snapshots/$name.png")
        }
    }
}
