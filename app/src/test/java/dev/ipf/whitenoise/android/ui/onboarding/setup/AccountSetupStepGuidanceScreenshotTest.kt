package dev.ipf.whitenoise.android.ui.onboarding.setup

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.unit.LayoutDirection
import com.github.takahirom.roborazzi.captureRoboImage
import dev.ipf.whitenoise.android.ui.theme.WhiteNoiseTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Baselines for blocked relay and key steps, built from MDK simulator snapshots of an imported key. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "en-w360dp-h1000dp-mdpi")
class AccountSetupStepGuidanceScreenshotTest {
    @get:Rule val composeRule = createComposeRule()

    /** The reported user's stop: one unreachable relay left the inbox check unfinished. */
    @Test fun inboxDiscoveryIncompleteAmoled() =
        capture(
            "inbox_discovery_incomplete_amoled",
            AccountSetupState(snapshot = inboxDiscoveryIncompleteSnapshot()),
            amoled = true,
        )

    @Test fun inboxDiscoveryIncompleteLight() = capture("inbox_discovery_incomplete", AccountSetupState(snapshot = inboxDiscoveryIncompleteSnapshot()))

    /** The explanation and both pinned actions stay readable mirrored at double text size. */
    @Test
    @Config(qualifiers = "en-w320dp-h640dp-mdpi")
    fun inboxDiscoveryIncompleteNarrowRtlLarge() =
        capture(
            "inbox_discovery_incomplete_narrow_rtl_large_amoled",
            AccountSetupState(snapshot = inboxDiscoveryIncompleteSnapshot()),
            amoled = true,
            rtl = true,
            fontScale = 2f,
        )

    /** Save was refused at the final check: nothing published, Try again is the only pinned action. */
    @Test fun refusedSaveAmoled() = capture("refused_save_amoled", AccountSetupState(snapshot = refusedSaveSnapshot()), amoled = true)

    /** An approved change no relay accepted stays pending with Try again. */
    @Test fun refusedPublicationAmoled() =
        capture(
            "refused_publication_amoled",
            AccountSetupState(snapshot = refusedPublicationSnapshot()),
            amoled = true,
        )

    /** Messaging keys that could not be published explain the step and offer Try again. */
    @Test fun keyPackageFailureAmoled() = capture("key_package_failure_amoled", AccountSetupState(snapshot = keyPackageFailureSnapshot()), amoled = true)

    /** Details lists the unreachable relay under the passed Read and write relays step. */
    @Test fun passedWithWarningsDetailsAmoled() =
        capture(
            "passed_with_warnings_details_amoled",
            AccountSetupState(snapshot = passedWithCleanupSnapshot(), detailsExpanded = true),
            amoled = true,
            scrollTo = "setup-passed-warnings",
        )

    @Test fun passedCleanupPreviewAmoled() =
        capture(
            "passed_cleanup_preview_amoled",
            AccountSetupState(snapshot = passedCleanupPreviewSnapshot()),
            amoled = true,
        )

    /** Freezes animation time and records the state with explicit theme, direction and text size. */
    private fun capture(
        name: String,
        state: AccountSetupState,
        amoled: Boolean = false,
        rtl: Boolean = false,
        fontScale: Float = 1f,
        scrollTo: String? = null,
    ) {
        composeRule.mainClock.autoAdvance = false
        composeRule.setContent {
            CompositionLocalProvider(
                LocalLayoutDirection provides if (rtl) LayoutDirection.Rtl else LayoutDirection.Ltr,
            ) {
                WhiteNoiseTheme(darkTheme = amoled, amoled = amoled, fontScale = fontScale) {
                    AccountSetupContent(state, {}, { _, _, _ -> }, {}, {}, {}, {}, {}, {}, { "Quiet Otter" })
                }
            }
        }
        if (scrollTo != null) {
            composeRule.mainClock.autoAdvance = true
            composeRule.onNodeWithTag(scrollTo).performScrollTo()
            composeRule.mainClock.autoAdvance = false
        }
        composeRule.mainClock.advanceTimeByFrame()
        composeRule.onRoot().captureRoboImage("src/test/snapshots/account_setup_$name.png")
    }
}
