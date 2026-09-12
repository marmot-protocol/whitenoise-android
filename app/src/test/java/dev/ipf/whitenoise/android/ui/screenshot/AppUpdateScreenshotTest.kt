package dev.ipf.whitenoise.android.ui.screenshot

import android.content.Context
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.LayoutDirection
import androidx.test.core.app.ApplicationProvider
import com.github.takahirom.roborazzi.captureRoboImage
import dev.ipf.whitenoise.android.R
import dev.ipf.whitenoise.android.ui.APP_UPDATE_DIALOG_TAG
import dev.ipf.whitenoise.android.ui.AppSelfUpdateContent
import dev.ipf.whitenoise.android.ui.chats.ChatListTopBar
import dev.ipf.whitenoise.android.ui.theme.WhiteNoiseTheme
import dev.ipf.whitenoise.android.ui.updateTestAsset
import dev.ipf.whitenoise.android.ui.updateTestFile
import dev.ipf.whitenoise.android.ui.updateTestInfo
import dev.ipf.whitenoise.android.ui.updates.updateEntryTestAppState
import dev.ipf.whitenoise.android.updates.AppSelfUpdateState
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Frozen synthetic update states pin layout only, never cryptographic or Android installer success. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "en-rUS-w360dp-h780dp-mdpi")
class AppUpdateScreenshotTest {
    @get:Rule val composeRule = createComposeRule()

    /** Confirmation uses the shared light dialog and all ordinary-release choices. */
    @Test fun confirmationLight() =
        capture(
            "app_update_confirm_light",
            AppSelfUpdateState.Confirming(updateTestAsset()),
        )

    /** AMOLED retains the native alert outline on black. */
    @Test fun confirmationAmoled() =
        capture(
            "app_update_confirm_amoled",
            AppSelfUpdateState.Confirming(updateTestAsset()),
            dark = true,
            amoled = true,
        )

    /** Multiple confirmation actions wrap at twice-sized text in RTL. */
    @Test
    @Config(qualifiers = "ar-rEG-ldrtl-w360dp-h780dp-mdpi")
    fun confirmationRtlLarge() =
        capture(
            "app_update_confirm_rtl_large",
            AppSelfUpdateState.Confirming(updateTestAsset()),
            largeRtl = true,
        )

    /** Known byte totals use a bounded determinate progress bar. */
    @Test fun downloadingDark() =
        capture(
            "app_update_downloading_dark",
            AppSelfUpdateState.Downloading(updateTestAsset(), 25, 100),
            dark = true,
        )

    /** Unknown byte totals retain an indeterminate operation. */
    @Test fun downloadingUnknownTotal() =
        capture(
            "app_update_downloading_unknown",
            AppSelfUpdateState.Downloading(updateTestAsset(), 25, null),
        )

    /** Resolution does not claim download or verification has completed. */
    @Test fun resolvingLight() =
        capture(
            "app_update_resolving_light",
            AppSelfUpdateState.Resolving,
        )

    /** The distinct verification phase announces real trust checks and keeps cancellation available. */
    @Test fun verifyingLight() =
        capture(
            "app_update_verifying_light",
            AppSelfUpdateState.Verifying(updateTestAsset()),
        )

    /** The Ready phase is supplied only by the authoritative verified state. */
    @Test fun verifiedLight() =
        capture(
            "app_update_verified_light",
            AppSelfUpdateState.Verified(updateTestAsset(), updateTestFile()),
        )

    /** Permission review remains a separate named Android settings action. */
    @Test fun permissionDark() =
        capture(
            "app_update_permission_dark",
            AppSelfUpdateState.PermissionRequired(updateTestAsset(), updateTestFile()),
            dark = true,
        )

    /** Hash failure retains its specific production recovery copy and retry. */
    @Test fun hashFailureAmoled() =
        capture(
            "app_update_hash_failure_amoled",
            AppSelfUpdateState.Error(R.string.app_self_update_hash_mismatch, true),
            dark = true,
            amoled = true,
        )

    /** The target emblem appears immediately before Search in the actual top bar. */
    @Test fun chatsEntryLight() = captureHeader("app_update_chats_light")

    /** The explicitly scoped update accent also remains visible on AMOLED. */
    @Test fun chatsEntryAmoled() = captureHeader("app_update_chats_amoled", dark = true, amoled = true)

    /** The action keeps a native touch target and logical order in large RTL layouts. */
    @Test
    @Config(qualifiers = "ar-rEG-ldrtl-w360dp-h780dp-mdpi")
    fun chatsEntryRtlLarge() = captureHeader("app_update_chats_rtl_large", largeRtl = true)

    /** Renders one modal with fixed progress animation time and deterministic version/file facts. */
    private fun capture(
        name: String,
        state: AppSelfUpdateState,
        dark: Boolean = false,
        amoled: Boolean = false,
        largeRtl: Boolean = false,
    ) {
        composeRule.mainClock.autoAdvance = false
        composeRule.setContent {
            CompositionLocalProvider(
                LocalLayoutDirection provides if (largeRtl) LayoutDirection.Rtl else LayoutDirection.Ltr,
            ) {
                WhiteNoiseTheme(darkTheme = dark, amoled = amoled, fontScale = if (largeRtl) 2f else 1f) {
                    AppSelfUpdateContent(state, true, {}, {}, {}, {}, {}, canRemindLater = true)
                }
            }
        }
        composeRule.mainClock.advanceTimeBy(250)
        composeRule.onNodeWithTag(APP_UPDATE_DIALOG_TAG).captureRoboImage("src/test/snapshots/$name.png")
    }

    /** Uses the actual header with an empty local account fixture and explicit self-managed presentation capability. */
    private fun captureHeader(
        name: String,
        dark: Boolean = false,
        amoled: Boolean = false,
        largeRtl: Boolean = false,
    ) {
        val appState = updateEntryTestAppState(ApplicationProvider.getApplicationContext<Context>())
        composeRule.setContent {
            CompositionLocalProvider(
                LocalLayoutDirection provides if (largeRtl) LayoutDirection.Rtl else LayoutDirection.Ltr,
            ) {
                WhiteNoiseTheme(darkTheme = dark, amoled = amoled, fontScale = if (largeRtl) 2f else 1f) {
                    Box(Modifier.fillMaxWidth().testTag("update.header")) {
                        ChatListTopBar(
                            appState,
                            false,
                            "",
                            remember { FocusRequester() },
                            {},
                            {},
                            {},
                            {},
                            {},
                            {},
                            updateInfo = updateTestInfo(),
                            selfUpdateEnabled = true,
                        )
                    }
                }
            }
        }
        composeRule.onNodeWithTag("update.header").captureRoboImage("src/test/snapshots/$name.png")
    }
}
