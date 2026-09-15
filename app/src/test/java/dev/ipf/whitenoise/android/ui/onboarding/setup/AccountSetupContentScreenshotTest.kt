package dev.ipf.whitenoise.android.ui.onboarding.setup

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.unit.LayoutDirection
import com.github.takahirom.roborazzi.captureRoboImage
import dev.ipf.marmotkit.OnboardingActionFfi
import dev.ipf.marmotkit.OnboardingFindingFfi
import dev.ipf.marmotkit.OnboardingIssueFfi
import dev.ipf.marmotkit.OnboardingRepairProposalFfi
import dev.ipf.marmotkit.OnboardingStatusFfi
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

    /** Captures the busy preflight state with the current decision and checklist visible. */
    @Test fun progress() = capture("progress", AccountSetupState(snapshot = setupSnapshot(), busy = true))

    /** Missing profile metadata offers a concise edit-or-skip decision. */
    @Test fun profileInput() = capture("profile", AccountSetupState(snapshot = setupSnapshot()))

    /** Records the profile form with existing draft values and its review action. */
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

    /** Records the inbox-specific publication field without a misleading read/write relay label. */
    @Test fun inboxEditor() =
        capture(
            "inbox_editor",
            AccountSetupState(
                snapshot = setupSnapshot(OnboardingStepFfi.INBOX_RELAYS),
                editor =
                    SetupEditor(
                        3uL,
                        OnboardingStepFfi.INBOX_RELAYS,
                        OnboardingActionFfi.EDIT_RELAYS,
                        reads = "wss://inbox.example",
                    ),
            ),
        )

    /** Checks the bounded content width on a tablet-sized surface. */
    @Test
    @Config(qualifiers = "en-w720dp-h1024dp-mdpi")
    fun tabletDevice() = capture("tablet_device", AccountSetupState(snapshot = deviceSnapshot()))

    /** Records the first visible decision in a short landscape viewport. */
    @Test
    @Config(qualifiers = "en-w780dp-h360dp-mdpi")
    fun landscapeDevice() = capture("landscape_device", AccountSetupState(snapshot = deviceSnapshot()))

    /** Records exact relay capabilities and the replacement warning before approval. */
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
                "existing-relay-record",
                listOf("wss://read.example"),
                listOf("wss://write.example"),
                null,
                null,
            )
        capture("relay_proposal", AccountSetupState(snapshot = snapshot))
    }

    /** Covers the signer-recovery decision using the dark application theme. */
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

    /** Expanded device diagnostics explain an inconclusive result without asserting another device is absent. */
    @Test fun deviceDiscoveryDetails() =
        capture(
            "device_discovery_details",
            AccountSetupState(
                snapshot = setupSnapshot(OnboardingStepFfi.SINGLE_DEVICE, listOf(OnboardingActionFfi.CONTINUE_ANYWAY)),
                detailsExpanded = true,
            ),
        )

    /** Records the recoverable error presentation without losing the current decision. */
    @Test fun failure() = capture("failure", AccountSetupState(snapshot = setupSnapshot(), error = true))

    /** Captures the notice for a publication that was approved before interruption. */
    @Test fun approvedRepairRetry() {
        val snapshot = setupSnapshot(OnboardingStepFfi.RELAYS, listOf(OnboardingActionFfi.RETRY))
        snapshot.proposal =
            OnboardingRepairProposalFfi(
                OnboardingStepFfi.RELAYS,
                3uL,
                "existing-relay-record",
                listOf("wss://read.example"),
                listOf("wss://write.example"),
                null,
                null,
            )
        capture("approved_repair_retry", AccountSetupState(snapshot = snapshot))
    }

    /** Captures pending cancellation separately from approved-repair recovery. */
    @Test fun cancellationRetry() {
        val snapshot =
            setupSnapshot(OnboardingStepFfi.INBOX_RELAYS, listOf(OnboardingActionFfi.CANCEL_ONBOARDING))
        snapshot.cancellationPending = true
        capture("cancellation_retry", AccountSetupState(snapshot = snapshot))
    }

    /** Records the acknowledgment text and evidence about another possible installation. */
    @Test fun singleDevice() =
        capture(
            "single_device",
            AccountSetupState(
                snapshot =
                    setupSnapshot(
                        OnboardingStepFfi.SINGLE_DEVICE,
                        listOf(
                            OnboardingActionFfi.CONTINUE_ANYWAY,
                            OnboardingActionFfi.RETRY,
                            OnboardingActionFfi.CANCEL_ONBOARDING,
                        ),
                    ),
            ),
        )

    /** Keeps completion visible when the native stream has already disconnected. */
    @Test fun ready() = capture("ready", AccountSetupState(snapshot = setupSnapshot(ready = true), disconnected = true))

    /** Checks readable decisions and reachable controls at double font scale in RTL. */
    @Test fun largeRtl() =
        capture(
            "large_rtl",
            AccountSetupState(snapshot = deviceSnapshot()),
            dark = true,
            rtl = true,
            fontScale = 2f,
        )

    /** Represents the only routine decision after automatic metadata and relay checks. */
    private fun deviceSnapshot() =
        setupSnapshot(
            OnboardingStepFfi.SINGLE_DEVICE,
            listOf(
                OnboardingActionFfi.CONTINUE_ANYWAY,
                OnboardingActionFfi.RETRY,
                OnboardingActionFfi.CANCEL_ONBOARDING,
            ),
        )

    /** Records diagnostics only after the user requests them. */
    @Test fun expandedDetails() =
        capture(
            "details",
            AccountSetupState(snapshot = deviceSnapshot(), detailsExpanded = true),
        )

    /** Identifies the retired endpoint beside the actionable finding before Details is expanded. */
    @Test fun retiredRelay() {
        val snapshot =
            setupSnapshot(
                OnboardingStepFfi.RELAYS,
                listOf(OnboardingActionFfi.USE_RECOMMENDED_RELAYS, OnboardingActionFfi.RETRY),
            )
        snapshot.steps.first { it.step == OnboardingStepFfi.RELAYS }.findings =
            listOf(OnboardingFindingFfi(OnboardingIssueFfi.RETIRED_RELAY, "wss://retired.example"))
        capture("retired_relay", AccountSetupState(snapshot = snapshot))
    }

    /** Keeps a failed device check distinct from the routine one-action consent screen. */
    @Test fun failedDeviceCheck() {
        val snapshot = setupSnapshot(OnboardingStepFfi.SINGLE_DEVICE, listOf(OnboardingActionFfi.RETRY))
        snapshot.steps.first { it.step == OnboardingStepFfi.SINGLE_DEVICE }.apply {
            status = OnboardingStatusFfi.RETRYABLE_FAILURE
            findings = listOf(OnboardingFindingFfi(OnboardingIssueFfi.TIMED_OUT, null))
        }
        capture("device_retry", AccountSetupState(snapshot = snapshot))
    }

    /** Freezes animation time and records the supplied state with explicit theme, direction, and density. */
    private fun capture(
        name: String,
        state: AccountSetupState,
        dark: Boolean = false,
        rtl: Boolean = false,
        fontScale: Float = 1f,
    ) {
        composeRule.mainClock.autoAdvance = false
        composeRule.setContent {
            CompositionLocalProvider(
                LocalLayoutDirection provides if (rtl) LayoutDirection.Rtl else LayoutDirection.Ltr,
            ) {
                WhiteNoiseTheme(darkTheme = dark, fontScale = fontScale) {
                    AccountSetupContent(state, {}, { _, _, _ -> }, {}, {}, {}, {}, {}, {})
                }
            }
        }
        composeRule.mainClock.autoAdvance = true
        state.checklistInspectionStep?.takeIf { state.editor == null }?.let {
            composeRule.onNodeWithTag("setup-step-${it.name}").performScrollTo().performClick()
        }
        composeRule.mainClock.autoAdvance = false
        composeRule.mainClock.advanceTimeByFrame()
        composeRule.onRoot().captureRoboImage("src/test/snapshots/account_setup_$name.png")
        if (name == "large_rtl") {
            composeRule.mainClock.autoAdvance = true
            composeRule.onNodeWithTag("setup-action-CONTINUE_ANYWAY").performScrollTo()
            composeRule.onRoot().captureRoboImage("src/test/snapshots/account_setup_large_rtl_actions.png")
        }
    }
}
