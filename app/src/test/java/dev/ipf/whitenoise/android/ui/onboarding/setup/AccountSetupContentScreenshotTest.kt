package dev.ipf.whitenoise.android.ui.onboarding.setup

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import com.github.takahirom.roborazzi.captureRoboImage
import dev.ipf.marmotkit.OnboardingActionFfi
import dev.ipf.marmotkit.OnboardingRepairProposalFfi
import dev.ipf.marmotkit.OnboardingStepFfi
import dev.ipf.whitenoise.android.ui.theme.WhiteNoiseTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Deterministic baselines for the materially different imported-account setup states. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "en-w360dp-h1000dp-mdpi")
class AccountSetupContentScreenshotTest {
    @get:Rule val composeRule = createComposeRule()

    @Test fun progress() = capture("progress", AccountSetupState(snapshot = setupSnapshot(), busy = true))

    @Test fun profileInput() = capture("profile", AccountSetupState(snapshot = setupSnapshot()))

    @Test fun profileEditor() =
        capture(
            "profile_editor",
            AccountSetupState(
                snapshot = setupSnapshot(),
                editor =
                    SetupEditor(
                        3uL,
                        OnboardingStepFfi.PROFILE,
                        OnboardingActionFfi.EDIT_PROFILE,
                        displayName = "Alex",
                        about = "Here for private conversations.",
                    ),
            ),
        )

    @Test
    @Config(qualifiers = "en-w720dp-h1024dp-mdpi")
    fun tabletProfile() = capture("tablet_profile", AccountSetupState(snapshot = setupSnapshot()))

    @Test
    @Config(qualifiers = "en-w780dp-h360dp-mdpi")
    fun landscapeProfile() = capture("landscape_profile", AccountSetupState(snapshot = setupSnapshot()))

    @Test fun relayProposal() {
        val snapshot =
            setupSnapshot(
                OnboardingStepFfi.RELAYS,
                listOf(
                    OnboardingActionFfi.APPROVE_REPAIR,
                    OnboardingActionFfi.CANCEL_REPAIR,
                ),
            )
        snapshot.proposal =
            OnboardingRepairProposalFfi(
                OnboardingStepFfi.RELAYS,
                3uL,
                null,
                listOf("wss://read.example"),
                listOf("wss://write.example"),
                null,
                null,
            )
        capture("relay_proposal", AccountSetupState(snapshot = snapshot))
    }

    @Test fun signerDark() =
        capture(
            "signer_dark",
            AccountSetupState(
                snapshot =
                    setupSnapshot(
                        OnboardingStepFfi.KEY_PACKAGE,
                        listOf(OnboardingActionFfi.RECONNECT_SIGNER, OnboardingActionFfi.RETRY),
                    ),
            ),
            dark = true,
        )

    @Test fun failure() = capture("failure", AccountSetupState(snapshot = setupSnapshot(), error = true))

    @Test fun approvedRepairRetry() {
        val snapshot = setupSnapshot(OnboardingStepFfi.RELAYS, listOf(OnboardingActionFfi.RETRY))
        snapshot.proposal =
            OnboardingRepairProposalFfi(
                OnboardingStepFfi.RELAYS,
                3uL,
                null,
                listOf("wss://read.example"),
                listOf("wss://write.example"),
                null,
                null,
            )
        capture("approved_repair_retry", AccountSetupState(snapshot = snapshot))
    }

    @Test fun cancellationRetry() {
        val snapshot = setupSnapshot(actions = listOf(OnboardingActionFfi.CANCEL_ONBOARDING))
        snapshot.cancellationPending = true
        capture("cancellation_retry", AccountSetupState(snapshot = snapshot))
    }

    @Test fun singleDevice() =
        capture(
            "single_device",
            AccountSetupState(
                snapshot =
                    setupSnapshot(
                        OnboardingStepFfi.SINGLE_DEVICE,
                        listOf(OnboardingActionFfi.CONTINUE_ANYWAY, OnboardingActionFfi.CANCEL_ONBOARDING),
                    ),
            ),
        )

    @Test fun ready() = capture("ready", AccountSetupState(snapshot = setupSnapshot(ready = true), disconnected = true))

    @Test fun largeRtl() =
        capture(
            "large_rtl",
            AccountSetupState(snapshot = setupSnapshot()),
            dark = true,
            rtl = true,
            fontScale = 2f,
        )

    private fun capture(
        name: String,
        state: AccountSetupState,
        dark: Boolean = false,
        rtl: Boolean = false,
        fontScale: Float = 1f,
    ) {
        composeRule.mainClock.autoAdvance = false
        composeRule.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(
                LocalDensity provides Density(density.density, fontScale),
                LocalLayoutDirection provides if (rtl) LayoutDirection.Rtl else LayoutDirection.Ltr,
            ) {
                WhiteNoiseTheme(darkTheme = dark) {
                    AccountSetupContent(state, {}, { _, _, _ -> }, {}, {}, {}, {}, {}, {})
                }
            }
        }
        composeRule.mainClock.advanceTimeByFrame()
        composeRule.onRoot().captureRoboImage("src/test/snapshots/account_setup_$name.png")
        if (name == "large_rtl") {
            composeRule.mainClock.autoAdvance = true
            composeRule.onNodeWithTag("setup-action-CONTINUE_WITHOUT").performScrollTo()
            composeRule.onRoot().captureRoboImage("src/test/snapshots/account_setup_large_rtl_actions.png")
        }
    }
}
