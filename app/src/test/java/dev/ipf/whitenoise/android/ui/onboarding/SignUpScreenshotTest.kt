package dev.ipf.whitenoise.android.ui.onboarding

import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Text
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.LayoutDirection
import com.github.takahirom.roborazzi.captureRoboImage
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

    /** Dark theme neutral avatar and tonal fields. */
    @Test fun dark() = render("signup_dark", dark = true)

    /** True black form and photo action outlines. */
    @Test fun amoled() = render("signup_amoled", dark = true, amoled = true)

    /** Bounded content column at tablet width. */
    @Config(qualifiers = "en-rUS-w1000dp-h780dp-mdpi")
    @Test
    fun tablet() = render("signup_tablet")

    /** Wrapped form and retained pinned action at 200 percent RTL. */
    @Test fun rtlLargeText() = render("signup_rtl_200", rtl = true, scale = 2f)

    /** Failed profile stage describes the accepted identity and same-account retry. */
    @Test fun publicationFailure() = render("signup_publication_failed", stage = SignUpStage.PublishFailed)

    /** Offline retains fields and the explicit retry action. */
    @Test fun offline() = render("signup_offline", offline = true)

    /** Native upload stage blocks editing and names actual work. */
    @Test fun uploadingPhoto() = render("signup_uploading", stage = SignUpStage.UploadingPhoto)

    private fun render(
        name: String,
        dark: Boolean = false,
        amoled: Boolean = false,
        rtl: Boolean = false,
        scale: Float = 1f,
        offline: Boolean = false,
        stage: SignUpStage = SignUpStage.Editing,
    ) {
        composeRule.mainClock.autoAdvance = false
        composeRule.setContent {
            CompositionLocalProvider(
                LocalLayoutDirection provides if (rtl) LayoutDirection.Rtl else LayoutDirection.Ltr,
            ) {
                WhiteNoiseTheme(darkTheme = dark, amoled = amoled, fontScale = scale) {
                    SignUpContent(
                        TextFieldState("Alice"),
                        TextFieldState("A local profile description"),
                        null,
                        stage,
                        editable = stage == SignUpStage.Editing,
                        preparingPhoto = false,
                        offline = offline,
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
        composeRule.mainClock.advanceTimeBy(160)
        composeRule.onRoot().captureRoboImage("src/test/snapshots/$name.png")
    }
}
