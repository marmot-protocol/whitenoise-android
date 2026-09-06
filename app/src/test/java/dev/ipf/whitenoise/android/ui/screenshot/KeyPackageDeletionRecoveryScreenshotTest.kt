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
import dev.ipf.whitenoise.android.state.AppText
import dev.ipf.whitenoise.android.state.DraftStore
import dev.ipf.whitenoise.android.state.KeyPackageDeletionResult
import dev.ipf.whitenoise.android.state.ToastMessage
import dev.ipf.whitenoise.android.state.WhiteNoiseAppState
import dev.ipf.whitenoise.android.state.presentKeyPackageDeletionResult
import dev.ipf.whitenoise.android.ui.common.ToastSnackbarVisuals
import dev.ipf.whitenoise.android.ui.common.WhiteNoiseSnackbarHost
import dev.ipf.whitenoise.android.ui.theme.WhiteNoiseTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Verifies the new cause-specific deletion recovery copy in the production snackbar. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "en-w360dp-h780dp-mdpi")
class KeyPackageDeletionRecoveryScreenshotTest {
    @get:Rule
    val composeRule = createComposeRule()

    /** Shows the no-safe-source explanation without a diagnostic Copy action at ordinary size. */
    @Test
    fun noUsableRelayLight() = capture(darkTheme = false, largeRtl = false, suffix = "light")

    /** Keeps the explanation readable at narrow width, doubled text size, and RTL direction. */
    @Test
    fun noUsableRelayDarkLargeRtl() = capture(darkTheme = true, largeRtl = true, suffix = "dark_large_rtl")

    /** Exercises localized DNS recovery in a long-language, narrow, doubled-font RTL snackbar. */
    @Test
    @Config(qualifiers = "de-w360dp-h780dp-mdpi")
    fun unavailableHostVerificationGermanDarkLargeRtl() =
        capture(
            darkTheme = true,
            largeRtl = true,
            suffix = "dns_unavailable_german_dark_large_rtl",
            result = KeyPackageDeletionResult.HostVerificationUnavailable,
            detailResource = R.string.error_couldnt_verify_relay_hosts,
        )

    /** Renders the actual AppState result through the same snackbar visuals used by the app shell. */
    private fun capture(
        darkTheme: Boolean,
        largeRtl: Boolean,
        suffix: String,
        result: KeyPackageDeletionResult = KeyPackageDeletionResult.NoUsableRelay,
        @StringRes detailResource: Int = R.string.error_no_safe_key_package_source_relay,
    ) {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val toast = presentRecovery(context, result, detailResource)
        val message = listOfNotNull(toast.title.resolve(context), toast.detail?.resolve(context)).joinToString("\n")
        val expectedMessage =
            context.getString(R.string.toast_couldnt_delete_key_package) + "\n" +
                context.getString(detailResource)
        assertEquals(expectedMessage, message)

        composeRule.setContent {
            val hostState = remember { SnackbarHostState() }
            val density = LocalDensity.current
            LaunchedEffect(hostState) {
                hostState.showSnackbar(
                    ToastSnackbarVisuals(
                        message = message,
                        copyable = toast.copyable,
                        tier = toast.tier,
                        copyText = toast.diagnosticReport,
                    ),
                )
            }
            CompositionLocalProvider(
                LocalDensity provides Density(density.density, if (largeRtl) 2f else 1f),
                LocalLayoutDirection provides if (largeRtl) LayoutDirection.Rtl else LayoutDirection.Ltr,
            ) {
                WhiteNoiseTheme(darkTheme = darkTheme) {
                    Surface(modifier = Modifier.width(if (largeRtl) 320.dp else 360.dp)) {
                        Box(Modifier.fillMaxSize()) {
                            WhiteNoiseSnackbarHost(
                                hostState = hostState,
                                modifier = Modifier.align(Alignment.BottomCenter).testTag(SNACKBAR_TAG),
                            )
                        }
                    }
                }
            }
        }

        composeRule.onNodeWithText(expectedMessage).assertIsDisplayed()
        composeRule.onNodeWithContentDescription(context.getString(R.string.copy)).assertDoesNotExist()
        composeRule
            .onNodeWithTag(SNACKBAR_TAG)
            .captureRoboImage("src/test/snapshots/key_package_deletion_recovery_$suffix.png")
    }

    /** Asserts the cause-specific resource contract before any snackbar fixture constructs display text. */
    private fun presentRecovery(
        context: Context,
        result: KeyPackageDeletionResult,
        @StringRes detailResource: Int,
    ): ToastMessage {
        val appState =
            WhiteNoiseAppState(
                context = context,
                draftStore = DraftStore.forContext(context),
                accountIdHexResolver = { null },
                accounts = emptyList(),
                activeAccountRef = "test-account",
            )
        assertFalse(
            appState.presentKeyPackageDeletionResult(
                result = result,
                hostVerificationDetail = AppText.Resource(R.string.error_couldnt_verify_relay_hosts),
            ),
        )
        return requireNotNull(appState.toast).also { toast ->
            assertEquals(AppText.Resource(R.string.toast_couldnt_delete_key_package), toast.title)
            assertEquals(AppText.Resource(detailResource), toast.detail)
        }
    }

    private companion object {
        const val SNACKBAR_TAG = "key-package-deletion-recovery"
    }
}
