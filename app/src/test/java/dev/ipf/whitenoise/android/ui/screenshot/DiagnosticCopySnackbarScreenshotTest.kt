package dev.ipf.whitenoise.android.ui.screenshot

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeLeft
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import com.github.takahirom.roborazzi.captureRoboImage
import dev.ipf.whitenoise.android.R
import dev.ipf.whitenoise.android.ui.common.LocalSnackbarBottomInset
import dev.ipf.whitenoise.android.ui.common.LocalSnackbarContentInset
import dev.ipf.whitenoise.android.ui.common.ToastSnackbarVisuals
import dev.ipf.whitenoise.android.ui.common.WhiteNoiseSnackbarHost
import dev.ipf.whitenoise.android.ui.theme.WhiteNoiseTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Visual and accessibility contract for privacy-safe diagnostic Copy (#1892). */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "en-w360dp-h780dp-mdpi")
class DiagnosticCopySnackbarScreenshotTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun lightNormalText() = capture(darkTheme = false, fontScale = 1f, fileSuffix = "light")

    @Test
    fun darkNormalText() = capture(darkTheme = true, fontScale = 1f, fileSuffix = "dark")

    @Test
    fun lightLargeText() = capture(darkTheme = false, fontScale = 1.5f, fileSuffix = "light_large")

    /** Dark large text. */
    @Test
    fun darkLargeText() = capture(darkTheme = true, fontScale = 1.5f, fileSuffix = "dark_large")

    /** Both diagnostic actions remain reachable at 200% text in RTL. */
    @Test
    fun largeRtl() = capture(darkTheme = false, fontScale = 2f, fileSuffix = "large_rtl", rtl = true)

    /** Copy leaves the diagnostic visible, while the explicit Dismiss control closes it. */
    @Test
    fun copyAndDismissAreIndependent() {
        capture(darkTheme = false, fontScale = 1f, fileSuffix = "light")
        composeRule.onNodeWithContentDescription("Copy").performClick()
        composeRule.onNodeWithContentDescription("Dismiss").assertIsDisplayed().performClick()
        composeRule.onNodeWithContentDescription("Copy").assertDoesNotExist()
    }

    /** The new control keeps the existing horizontal swipe dismissal. */
    @Test
    fun swipeStillDismisses() {
        capture(darkTheme = false, fontScale = 1f, fileSuffix = "light")
        composeRule.onNodeWithContentDescription("Copy").performTouchInput { swipeLeft() }
        composeRule.onNodeWithContentDescription("Dismiss").assertDoesNotExist()
    }

    /** Renders the fixture and records its screenshot baseline. */
    private fun capture(
        darkTheme: Boolean,
        fontScale: Float,
        fileSuffix: String,
        rtl: Boolean = false,
    ) {
        composeRule.setContent {
            val hostState = remember { SnackbarHostState() }
            val density = LocalDensity.current
            val message =
                listOf(
                    stringResource(R.string.media_couldnt_open),
                    stringResource(R.string.error_try_again),
                ).joinToString("\n")
            LaunchedEffect(hostState) {
                hostState.showSnackbar(
                    ToastSnackbarVisuals(
                        message = message,
                        copyable = true,
                        copyText =
                            "White Noise error report\n" +
                                "operation=MEDIA_LIBRARY_FILE_OPEN\n" +
                                "error=IO",
                    ),
                )
            }
            CompositionLocalProvider(
                LocalDensity provides Density(density.density, fontScale),
                LocalLayoutDirection provides if (rtl) LayoutDirection.Rtl else LayoutDirection.Ltr,
                LocalSnackbarBottomInset provides remember { mutableStateOf(0.dp) },
                LocalSnackbarContentInset provides remember { mutableStateOf(0.dp) },
            ) {
                WhiteNoiseTheme(darkTheme = darkTheme) {
                    Surface(modifier = Modifier.fillMaxSize().testTag(SCREEN_TAG)) {
                        Box(modifier = Modifier.fillMaxSize()) {
                            WhiteNoiseSnackbarHost(
                                hostState = hostState,
                                modifier = Modifier.align(Alignment.BottomCenter),
                            )
                        }
                    }
                }
            }
        }

        composeRule.onNodeWithContentDescription("Copy").assertIsDisplayed()
        composeRule.onNodeWithContentDescription("Dismiss").assertIsDisplayed()
        composeRule
            .onNodeWithTag(SCREEN_TAG)
            .captureRoboImage("src/test/snapshots/diagnostic_copy_snackbar_$fileSuffix.png")
    }

    private companion object {
        const val SCREEN_TAG = "diagnostic-copy-snackbar-screen"
    }
}
