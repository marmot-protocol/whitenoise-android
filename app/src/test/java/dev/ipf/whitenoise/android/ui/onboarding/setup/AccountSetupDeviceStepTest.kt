package dev.ipf.whitenoise.android.ui.onboarding.setup

import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performScrollTo
import dev.ipf.marmotkit.OnboardingActionFfi
import dev.ipf.marmotkit.OnboardingDeviceDiscoveryFfi
import dev.ipf.marmotkit.OnboardingIssueFfi
import dev.ipf.marmotkit.OnboardingStepFfi
import dev.ipf.whitenoise.android.ui.theme.WhiteNoiseTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** The "Chats on this device" step states what MDK's device check found once, then what it means for chats. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], qualifiers = "en-w360dp-h780dp-mdpi")
class AccountSetupDeviceStepTest {
    @get:Rule val composeRule = createComposeRule()

    /** A check that could not run says so, then explains that another app's chats would stay there. */
    @Test fun uncheckedDeviceCheckSaysSoAndExplainsSeparateChats() {
        val snapshot = setupSnapshot(OnboardingStepFfi.SINGLE_DEVICE)
        snapshot.singleDeviceNotice = setupDeviceNotice(OnboardingDeviceDiscoveryFfi.UNKNOWN)
        show(AccountSetupState(snapshot = snapshot))
        composeRule.onNodeWithText("Chats on this device").assertIsDisplayed()
        composeRule.onNodeWithText(UNCHECKED_NOTE).performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText(IF_OTHER_APP_HELP).performScrollTo().assertIsDisplayed()
    }

    /** Before MDK has checked, the step claims no finding and still explains what another app would mean. */
    @Test fun aPendingDeviceCheckClaimsNoFinding() {
        show(AccountSetupState(snapshot = setupSnapshot(OnboardingStepFfi.SINGLE_DEVICE)))
        composeRule.onNodeWithText(IF_OTHER_APP_HELP).performScrollTo().assertIsDisplayed()
        listOf(UNCHECKED_NOTE, NONE_FOUND_NOTE, FOUND_NOTE).forEach {
            composeRule.onAllNodesWithText(it).assertCountEquals(0)
        }
    }

    /** Finding no messaging keys elsewhere is stated as a finding, with no explanation and no note in Details. */
    @Test fun noKeysFoundIsStatedWithoutAnExplanation() {
        val snapshot = setupSnapshot(OnboardingStepFfi.SINGLE_DEVICE)
        snapshot.singleDeviceNotice = setupDeviceNotice(OnboardingDeviceDiscoveryFfi.NONE_FOUND)
        show(AccountSetupState(snapshot = snapshot, detailsExpanded = true))
        composeRule.onAllNodesWithText(NONE_FOUND_NOTE).assertCountEquals(1)
        listOf(UNCHECKED_NOTE, FOUND_NOTE, SEPARATE_CHATS_HELP, IF_OTHER_APP_HELP).forEach {
            composeRule.onAllNodesWithText(it).assertCountEquals(0)
        }
    }

    /** Opening Details does not repeat the unchecked finding the step already shows. */
    @Test fun uncheckedDeviceCheckIsStatedOnceWithDetailsOpen() {
        val snapshot = setupSnapshot(OnboardingStepFfi.SINGLE_DEVICE)
        snapshot.singleDeviceNotice = setupDeviceNotice(OnboardingDeviceDiscoveryFfi.UNKNOWN)
        show(AccountSetupState(snapshot = snapshot, detailsExpanded = true))
        composeRule.onNodeWithText("Setup checks").performScrollTo().assertIsDisplayed()
        composeRule.onAllNodesWithText(UNCHECKED_NOTE).assertCountEquals(1)
    }

    /** On the routine step, found keys are stated once and explained as a device that can be linked after sign-in. */
    @Test fun foundKeysAreStatedOnceOnTheRoutineStep() {
        show(AccountSetupState(snapshot = foundSignInsSnapshot()))
        composeRule.onAllNodesWithText(FOUND_NOTE).assertCountEquals(1)
        composeRule.onNodeWithText(LINK_NEXT_HELP).performScrollTo().assertIsDisplayed()
    }

    /** When the step shows its native finding, the notice does not repeat the same sentence. */
    @Test fun foundKeysAreStatedOnceBesideTheFinding() {
        show(AccountSetupState(snapshot = foundSignInsSnapshot(), error = true))
        composeRule.onAllNodesWithText(FOUND_NOTE).assertCountEquals(1)
    }

    /** A dated record gives the same sign-in finding as an undated record. */
    @Test fun datedRecordsDoNotDateTheSignIn() {
        val snapshot = setupSnapshot(OnboardingStepFfi.SINGLE_DEVICE, listOf(OnboardingActionFfi.CONTINUE_ANYWAY))
        snapshot.singleDeviceNotice =
            setupDeviceNotice(
                OnboardingDeviceDiscoveryFfi.OTHER_INSTALLATION_POSSIBLE,
                publishedAt = listOf(NOON_UTC_2025_11_02, NOON_UTC_2026_03_15),
            )
        show(AccountSetupState(snapshot = snapshot))
        composeRule.onAllNodesWithText("2026", substring = true).assertCountEquals(0)
        composeRule.onAllNodesWithText(FOUND_NOTE).assertCountEquals(1)
    }

    /** The native row preserves its endpoint and Details does not repeat the sign-in notice. */
    @Test
    fun aDatedRecordKeepsItsEndpointAndShowsOneFinding() {
        val snapshot = foundSignInsSnapshot()
        snapshot.singleDeviceNotice =
            setupDeviceNotice(OnboardingDeviceDiscoveryFfi.OTHER_INSTALLATION_POSSIBLE, listOf(NOON_UTC_2026_03_15))
        snapshot.steps.first { it.step == OnboardingStepFfi.SINGLE_DEVICE }.findings =
            listOf(setupFinding(OnboardingIssueFfi.OTHER_INSTALLATION_POSSIBLE, DEVICE_ENDPOINT))
        show(AccountSetupState(snapshot = snapshot, error = true, detailsExpanded = true))
        composeRule.onAllNodesWithText("2026", substring = true).assertCountEquals(0)
        composeRule
            .onAllNodesWithText(DEVICE_ENDPOINT)
            .onFirst()
            .performScrollTo()
            .assertIsDisplayed()
        composeRule.onAllNodesWithText(FOUND_NOTE).assertCountEquals(1)
    }

    /** Shows the setup content for [state] with inert callbacks. */
    private fun show(state: AccountSetupState) {
        composeRule.setContent {
            WhiteNoiseTheme {
                AccountSetupContent(state, {}, { _, _, _ -> }, {}, {}, {}, {}, {}, {}, { "" })
            }
        }
    }

    /** A device step whose discovery and native finding both report other sign-ins. */
    private fun foundSignInsSnapshot() =
        setupSnapshot(OnboardingStepFfi.SINGLE_DEVICE, listOf(OnboardingActionFfi.CONTINUE_ANYWAY)).also { snapshot ->
            snapshot.singleDeviceNotice = setupDeviceNotice(OnboardingDeviceDiscoveryFfi.OTHER_INSTALLATION_POSSIBLE)
            snapshot.steps.first { it.step == OnboardingStepFfi.SINGLE_DEVICE }.findings =
                listOf(setupFinding(OnboardingIssueFfi.OTHER_INSTALLATION_POSSIBLE, null))
        }

    private companion object {
        const val NONE_FOUND_NOTE =
            "We didn’t find signs you’ve signed in on another device or in another app."
        const val UNCHECKED_NOTE =
            "We couldn’t finish checking for other sign-ins."
        const val FOUND_NOTE =
            "It looks like you’ve signed in to this account before, on another device or in another app."
        const val SEPARATE_CHATS_HELP =
            "This device will have its own separate chats. Chats in the other app stay there and won’t appear here."
        const val LINK_NEXT_HELP =
            "After signing in, you can link this device to get chats from your other devices."
        const val IF_OTHER_APP_HELP =
            "If you have chats in another app, they won’t appear here automatically."
        const val NOON_UTC_2025_11_02 = 1_762_084_800uL
        const val NOON_UTC_2026_03_15 = 1_773_576_000uL
        const val DEVICE_ENDPOINT = "wss://device.example"
    }
}
