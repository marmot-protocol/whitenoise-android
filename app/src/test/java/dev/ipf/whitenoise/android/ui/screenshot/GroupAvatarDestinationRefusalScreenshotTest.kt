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
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import com.github.takahirom.roborazzi.captureRoboImage
import dev.ipf.marmotkit.MarmotKitException
import dev.ipf.whitenoise.android.R
import dev.ipf.whitenoise.android.ui.common.LocalSnackbarBottomInset
import dev.ipf.whitenoise.android.ui.common.LocalSnackbarContentInset
import dev.ipf.whitenoise.android.ui.common.ToastSnackbarVisuals
import dev.ipf.whitenoise.android.ui.common.WhiteNoiseSnackbarHost
import dev.ipf.whitenoise.android.ui.group.GroupAvatarUploadFailure
import dev.ipf.whitenoise.android.ui.group.GroupAvatarUploadStage
import dev.ipf.whitenoise.android.ui.group.groupImageFailureDetail
import dev.ipf.whitenoise.android.ui.theme.WhiteNoiseTheme
import org.junit.Assert.assertFalse
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "en-w360dp-h780dp-mdpi")
class GroupAvatarDestinationRefusalScreenshotTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun light() = capture("light", dark = false)

    @Test
    fun dark() = capture("dark", dark = true)

    @Test
    fun largeRtl() = capture("large_rtl", dark = true, largeRtl = true)

    private fun capture(
        suffix: String,
        dark: Boolean,
        largeRtl: Boolean = false,
    ) {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val failure =
            GroupAvatarUploadFailure(
                GroupAvatarUploadStage.Upload,
                3L,
                MarmotKitException.InvalidMediaReference("https://private.invalid/?auth=secret"),
            )
        val message =
            context.getString(R.string.toast_couldnt_upload_group_image) + "\n" +
                groupImageFailureDetail(failure).resolve(context)
        assertFalse(message.contains("private.invalid"))
        assertFalse(message.contains("secret"))
        composeRule.setContent {
            val host = remember { SnackbarHostState() }
            val density = LocalDensity.current
            LaunchedEffect(host) {
                host.showSnackbar(
                    ToastSnackbarVisuals(
                        message = message,
                        copyable = true,
                        copyText = "operation=GROUP_IMAGE_UPLOAD",
                    ),
                )
            }
            CompositionLocalProvider(
                LocalDensity provides Density(density.density, if (largeRtl) 2f else 1f),
                LocalLayoutDirection provides if (largeRtl) LayoutDirection.Rtl else LayoutDirection.Ltr,
                LocalSnackbarBottomInset provides remember { mutableStateOf(0.dp) },
                LocalSnackbarContentInset provides remember { mutableStateOf(0.dp) },
            ) {
                WhiteNoiseTheme(darkTheme = dark) {
                    Surface(modifier = Modifier.width(if (largeRtl) 320.dp else 360.dp)) {
                        Box(Modifier.fillMaxSize()) {
                            WhiteNoiseSnackbarHost(
                                hostState = host,
                                modifier = Modifier.align(Alignment.BottomCenter).testTag(TAG),
                            )
                        }
                    }
                }
            }
        }
        composeRule.onNodeWithText(message).assertIsDisplayed()
        composeRule.onNodeWithContentDescription("Copy").assertIsDisplayed()
        composeRule.onNodeWithContentDescription("Dismiss").assertIsDisplayed()
        composeRule.onNodeWithTag(TAG).captureRoboImage(
            "src/test/snapshots/group_avatar_destination_refusal_$suffix.png",
        )
    }

    private companion object {
        const val TAG = "group_avatar_destination_refusal"
    }
}
