package dev.ipf.whitenoise.android.ui.screenshot

import android.content.Context
import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.width
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import com.github.takahirom.roborazzi.captureRoboImage
import dev.ipf.whitenoise.android.R
import dev.ipf.whitenoise.android.state.AppText
import dev.ipf.whitenoise.android.state.ToastMessage
import dev.ipf.whitenoise.android.ui.common.ToastSnackbarVisuals
import dev.ipf.whitenoise.android.ui.common.WhiteNoiseSnackbarHost
import dev.ipf.whitenoise.android.ui.theme.WhiteNoiseTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** New recovery copy uses the same non-copyable actionable notice policy as its production emitters. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "en-w360dp-h780dp-mdpi")
class RecoveryInterruptionNoticeScreenshotTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun documentLight() = capture(R.string.document_save_interrupted, "document_light", dark = false)

    @Test
    fun documentDark() = capture(R.string.document_save_interrupted, "document_dark", dark = true)

    @Test
    fun documentLargeRtl() {
        capture(R.string.document_save_interrupted, "document_large_rtl", dark = true, largeRtl = true)
    }

    @Test
    fun avatarLight() = capture(R.string.avatar_preparation_interrupted, "avatar_light", dark = false)

    @Test
    fun avatarDark() = capture(R.string.avatar_preparation_interrupted, "avatar_dark", dark = true)

    @Test
    fun avatarLargeRtl() {
        capture(R.string.avatar_preparation_interrupted, "avatar_large_rtl", dark = true, largeRtl = true)
    }

    private fun capture(
        @StringRes messageRes: Int,
        suffix: String,
        dark: Boolean,
        largeRtl: Boolean = false,
    ) {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val notice = ToastMessage(title = AppText.Resource(messageRes))
        val message = notice.title.resolve(context)
        composeRule.setContent {
            val host = remember { SnackbarHostState() }
            val density = LocalDensity.current
            LaunchedEffect(host) {
                host.showSnackbar(
                    ToastSnackbarVisuals(
                        message = message,
                        copyable = notice.copyable,
                        tier = notice.tier,
                        copyText = notice.diagnosticReport,
                    ),
                )
            }
            CompositionLocalProvider(
                LocalDensity provides Density(density.density, if (largeRtl) 2f else 1f),
                LocalLayoutDirection provides if (largeRtl) LayoutDirection.Rtl else LayoutDirection.Ltr,
            ) {
                WhiteNoiseTheme(darkTheme = dark) {
                    Surface(modifier = Modifier.width(if (largeRtl) 320.dp else 360.dp)) {
                        Box(Modifier.fillMaxSize()) {
                            WhiteNoiseSnackbarHost(
                                hostState = host,
                                modifier = Modifier.align(Alignment.TopCenter).testTag(TAG),
                            )
                        }
                    }
                }
            }
        }
        composeRule.onNodeWithText(message).assertIsDisplayed()
        composeRule.onNodeWithContentDescription(context.getString(R.string.dismiss)).assertIsDisplayed()
        composeRule.onNodeWithContentDescription(context.getString(R.string.copy)).assertDoesNotExist()
        composeRule.onNodeWithTag(TAG).captureRoboImage("src/test/snapshots/recovery_interruption_$suffix.png")
    }

    private companion object {
        const val TAG = "recovery_interruption_notice"
    }
}
