package dev.ipf.whitenoise.android.ui.screenshot

import android.content.Context
import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
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
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.printToString
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

    /** Captures the interrupted document save notice in the light theme. */
    @Test
    fun documentLight() = capture(R.string.document_save_interrupted, "document_light", dark = false)

    /** Captures the interrupted document save notice in the dark theme. */
    @Test
    fun documentDark() = capture(R.string.document_save_interrupted, "document_dark", dark = true)

    /** Captures the interrupted document save notice at a large font scale in RTL. */
    @Test
    fun documentLargeRtl() {
        capture(R.string.document_save_interrupted, "document_large_rtl", dark = true, largeRtl = true)
    }

    /** Captures the interrupted avatar preparation notice in the light theme. */
    @Test
    fun avatarLight() = capture(R.string.avatar_preparation_interrupted, "avatar_light", dark = false)

    /** Captures the interrupted avatar preparation notice in the dark theme. */
    @Test
    fun avatarDark() = capture(R.string.avatar_preparation_interrupted, "avatar_dark", dark = true)

    /** Captures the interrupted avatar preparation notice at a large font scale in RTL. */
    @Test
    fun avatarLargeRtl() {
        capture(R.string.avatar_preparation_interrupted, "avatar_large_rtl", dark = true, largeRtl = true)
    }

    /** Records a baseline of the notice for [messageRes] with the requested theme, font scale and direction. */
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
                    Surface(modifier = Modifier.width(if (largeRtl) 320.dp else 360.dp).height(780.dp)) {
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
        composeRule.mainClock.advanceTimeBy(1_000)
        composeRule.waitForIdle()
        composeRule.onNodeWithTag(TAG).captureRoboImage("src/test/snapshots/recovery_interruption_$suffix.png")
        try {
            composeRule.onNodeWithText(message).assertIsDisplayed()
            val dismiss =
                hasAnyAncestor(hasTestTag(TAG)) and hasClickAction() and
                    SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Button)
            composeRule.onAllNodes(dismiss).assertCountEquals(1)
            composeRule.onNode(dismiss).assertIsDisplayed()
            composeRule
                .onNodeWithContentDescription(context.getString(R.string.copy), substring = true)
                .assertDoesNotExist()
            composeRule.onNode(dismiss).performClick()
            composeRule.waitForIdle()
            composeRule.onNodeWithText(message).assertDoesNotExist()
        } catch (failure: AssertionError) {
            println("Merged recovery notice semantics:\n${composeRule.onRoot().printToString()}")
            println(
                "Unmerged recovery notice semantics:\n${composeRule.onRoot(useUnmergedTree = true).printToString()}",
            )
            throw failure
        }
    }

    private companion object {
        const val TAG = "recovery_interruption_notice"
    }
}
