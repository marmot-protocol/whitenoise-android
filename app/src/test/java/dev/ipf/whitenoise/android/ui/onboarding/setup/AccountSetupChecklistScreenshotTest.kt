package dev.ipf.whitenoise.android.ui.onboarding.setup

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.LayoutDirection
import com.github.takahirom.roborazzi.captureRoboImage
import dev.ipf.marmotkit.OnboardingActionFfi
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

/** Prototype checklist presentation uses only explicit local native snapshot fixtures. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "en-w360dp-h780dp-mdpi")
class AccountSetupChecklistScreenshotTest {
    @get:Rule val composeRule = createComposeRule()

    /** Full native checklist with the current profile decision. */
    @Test fun light() = capture("light")

    /** Dark status contrast and inactive Open Chats. */
    @Test fun dark() = capture("dark", dark = true)

    /** Black surfaces preserve outlined primary controls. */
    @Test fun amoled() = capture("amoled", dark = true, amoled = true)

    /** The form stays 520 dp wide on a tablet. */
    @Test
    @Config(qualifiers = "en-w1000dp-h780dp-mdpi")
    fun tablet() = capture("tablet")

    /** RTL double-size status text can grow without hiding the pinned primary. */
    @Test fun rtlLargeText() = capture("rtl_200", rtl = true, scale = 2f)

    /** A short viewport preserves a scrollable checklist and separate action region. */
    @Test
    @Config(qualifiers = "en-w780dp-h360dp-mdpi")
    fun landscape() = capture("landscape", scale = 2f)

    /** A real native ready snapshot enables only the guarded Open Chats callback. */
    @Test fun ready() = capture("ready", state = AccountSetupState(snapshot = setupSnapshot(ready = true)))

    /** Every distinct native status remains visible, without converting skipped into completed. */
    @Test fun mixedNativeStatuses() {
        val snapshot = setupSnapshot(OnboardingStepFfi.RELAYS, listOf(OnboardingActionFfi.RETRY))
        snapshot.steps[0].status = OnboardingStatusFfi.PASSED
        snapshot.steps[1].status = OnboardingStatusFfi.SKIPPED
        snapshot.steps[2].status = OnboardingStatusFfi.RETRYABLE_FAILURE
        snapshot.steps[3].status = OnboardingStatusFfi.WAITING_FOR_SIGNER
        snapshot.steps[4].status = OnboardingStatusFfi.CHECKING
        capture("mixed_statuses", state = AccountSetupState(snapshot = snapshot))
    }

    /** No offered action can hide the retained approved-operation explanation on cold entry. */
    @Test fun noActionApprovedRepair() {
        val snapshot =
            setupSnapshot(OnboardingStepFfi.RELAYS, emptyList()).copy(
                proposal =
                    OnboardingRepairProposalFfi(
                        OnboardingStepFfi.RELAYS,
                        3uL,
                        "existing",
                        listOf("wss://read.example"),
                        listOf("wss://write.example"),
                        null,
                        null,
                    ),
            )
        capture("no_action_approved", state = AccountSetupState(snapshot))
    }

    /** A saved cancellation remains visibly distinct even before the engine offers Retry or Cancel. */
    @Test fun noActionCancellation() {
        val snapshot = setupSnapshot(OnboardingStepFfi.INBOX_RELAYS, emptyList()).copy(cancellationPending = true)
        capture("no_action_cancellation", state = AccountSetupState(snapshot))
    }

    /** Theme owns font scale, including any composed windows; no source calls or clocks are simulated. */
    private fun capture(
        name: String,
        dark: Boolean = false,
        amoled: Boolean = false,
        rtl: Boolean = false,
        scale: Float = 1f,
        state: AccountSetupState = AccountSetupState(snapshot = setupSnapshot()),
    ) {
        composeRule.mainClock.autoAdvance = false
        composeRule.setContent {
            CompositionLocalProvider(
                LocalLayoutDirection provides if (rtl) LayoutDirection.Rtl else LayoutDirection.Ltr,
            ) {
                WhiteNoiseTheme(darkTheme = dark, amoled = amoled, fontScale = scale) {
                    AccountSetupContent(state, {}, { _, _, _ -> }, {}, {}, {}, {}, {}, {})
                }
            }
        }
        composeRule.mainClock.advanceTimeByFrame()
        composeRule.onRoot().captureRoboImage("src/test/snapshots/account_setup_checklist_$name.png")
    }
}
