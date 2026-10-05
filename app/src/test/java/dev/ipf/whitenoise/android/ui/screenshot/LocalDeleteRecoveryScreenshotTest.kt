package dev.ipf.whitenoise.android.ui.screenshot

import android.content.Context
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.width
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
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
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

/** The recovery copy must remain readable without hiding diagnostic actions. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "en-w360dp-h780dp-mdpi")
class LocalDeleteRecoveryScreenshotTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun retryLight() = capture(Notice.Retry, "retry_light", dark = false)

    @Test
    fun retryDark() = capture(Notice.Retry, "retry_dark")

    @Test
    fun retryAmoled() = capture(Notice.Retry, "retry_amoled", amoled = true)

    @Test
    fun retryLargeRtl() = capture(Notice.Retry, "retry_large_rtl", largeRtl = true)

    @Test
    fun cleanupLight() = capture(Notice.Cleanup, "cleanup_light", dark = false)

    @Test
    fun cleanupLargeRtl() = capture(Notice.Cleanup, "cleanup_large_rtl", largeRtl = true)

    @Test
    fun stoppedLight() = capture(Notice.Stopped, "stopped_light", dark = false)

    @Test
    fun stoppedLargeRtl() = capture(Notice.Stopped, "stopped_large_rtl", largeRtl = true)

    private fun capture(
        notice: Notice,
        suffix: String,
        dark: Boolean = true,
        amoled: Boolean = false,
        largeRtl: Boolean = false,
    ) {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val detail =
            if (notice == Notice.Stopped) {
                context.getString(notice.detail, 51, 500)
            } else {
                context.getString(notice.detail)
            }
        val message = context.getString(notice.title) + "\n" + detail
        composeRule.setContent {
            val hostState = remember { SnackbarHostState() }
            val density = LocalDensity.current
            LaunchedEffect(hostState) {
                hostState.showSnackbar(
                    ToastSnackbarVisuals(
                        message = message,
                        copyable = true,
                        copyText =
                            "operation=CHAT_LOCAL_DELETE\n" +
                                "phase=native_delete;attempt=3;exhausted=1;presence=present",
                    ),
                )
            }
            CompositionLocalProvider(
                LocalDensity provides Density(density.density, if (largeRtl) 2f else 1f),
                LocalLayoutDirection provides if (largeRtl) LayoutDirection.Rtl else LayoutDirection.Ltr,
                LocalSnackbarBottomInset provides remember { mutableStateOf(0.dp) },
                LocalSnackbarContentInset provides remember { mutableStateOf(0.dp) },
            ) {
                WhiteNoiseTheme(darkTheme = dark, amoled = amoled) {
                    Surface(Modifier.width(if (largeRtl) 320.dp else 360.dp)) {
                        Box(Modifier.fillMaxSize()) {
                            WhiteNoiseSnackbarHost(
                                hostState,
                                Modifier.align(Alignment.BottomCenter).testTag("local-delete-notice"),
                            )
                        }
                    }
                }
            }
        }
        composeRule.onNodeWithText(message).assertIsDisplayed()
        composeRule.onNodeWithContentDescription(context.getString(R.string.copy)).assertIsDisplayed()
        composeRule.onNodeWithContentDescription(context.getString(R.string.dismiss)).assertIsDisplayed()
        composeRule.onNodeWithTag("local-delete-notice").captureRoboImage("src/test/snapshots/local_delete_$suffix.png")
    }

    private enum class Notice(
        val title: Int,
        val detail: Int,
    ) {
        Retry(R.string.toast_couldnt_delete_chat, R.string.local_delete_retry_detail),
        Cleanup(R.string.toast_chat_deleted_local, R.string.local_delete_cleanup_pending),
        Stopped(R.string.chat_list_delete_stopped, R.string.chat_list_delete_stopped_detail),
    }
}
