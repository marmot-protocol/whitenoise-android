package dev.ipf.whitenoise.android.ui.onboarding.setup

import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performScrollTo
import dev.ipf.marmotkit.OnboardingActionFfi
import dev.ipf.marmotkit.OnboardingDeviceDiscoveryFfi
import dev.ipf.marmotkit.OnboardingIssueFfi
import dev.ipf.marmotkit.OnboardingStepFfi
import dev.ipf.whitenoise.android.ui.theme.WhiteNoiseTheme
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.TimeZone

/** The "Chats on this device" step states what MDK's device check found once, then what it means for chats. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], qualifiers = "en-w360dp-h780dp-mdpi")
class AccountSetupDeviceStepTest {
    @get:Rule val composeRule = createComposeRule()
    private val originalTimeZone: TimeZone = TimeZone.getDefault()

    /** Pins UTC, so a noon-UTC publication reads as the same calendar day on every host. */
    @Before
    fun pinUtc() {
        TimeZone.setDefault(TimeZone.getTimeZone("UTC"))
    }

    /** Restores the host's zone for the next suite. */
    @After
    fun restoreTimeZone() {
        TimeZone.setDefault(originalTimeZone)
    }

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

    /** On the routine step, found keys are stated once and explained as a separate installation with its own chats. */
    @Test fun foundKeysAreStatedOnceOnTheRoutineStep() {
        show(AccountSetupState(snapshot = foundSignInsSnapshot()))
        composeRule.onAllNodesWithText(FOUND_NOTE).assertCountEquals(1)
        composeRule.onNodeWithText(SEPARATE_CHATS_HELP).performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText(EITHER_APP_HELP).performScrollTo().assertIsDisplayed()
    }

    /** When the step shows its native finding, the notice does not repeat the same sentence. */
    @Test fun foundKeysAreStatedOnceBesideTheFinding() {
        show(AccountSetupState(snapshot = foundSignInsSnapshot(), error = true))
        composeRule.onAllNodesWithText(FOUND_NOTE).assertCountEquals(1)
    }

    /** Found keys name the day another installation last published them. */
    @Test fun foundKeysNameTheirLatestPublicationDate() {
        val snapshot = setupSnapshot(OnboardingStepFfi.SINGLE_DEVICE, listOf(OnboardingActionFfi.CONTINUE_ANYWAY))
        snapshot.singleDeviceNotice =
            setupDeviceNotice(
                OnboardingDeviceDiscoveryFfi.OTHER_INSTALLATION_POSSIBLE,
                publishedAt = listOf(NOON_UTC_2025_11_02, NOON_UTC_2026_03_15),
            )
        show(AccountSetupState(snapshot = snapshot))
        composeRule.onNodeWithText("$FOUND_ON_DATE_PREFIX March 15, 2026.").performScrollTo().assertIsDisplayed()
        composeRule.onAllNodesWithText(FOUND_NOTE).assertCountEquals(0)
    }

    /** Beside MDK's own finding in an error state, a dated notice still names the day, and says it only once. */
    @Test
    fun aDatedNoticeKeepsItsDateBesideTheFinding() {
        val snapshot = foundSignInsSnapshot()
        snapshot.singleDeviceNotice =
            setupDeviceNotice(OnboardingDeviceDiscoveryFfi.OTHER_INSTALLATION_POSSIBLE, listOf(NOON_UTC_2026_03_15))
        show(AccountSetupState(snapshot = snapshot, error = true))
        composeRule.onNodeWithText("$FOUND_ON_DATE_PREFIX March 15, 2026.").performScrollTo().assertIsDisplayed()
        composeRule.onAllNodesWithText(FOUND_NOTE).assertCountEquals(0)
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
        const val NONE_FOUND_NOTE = "We didn’t find messaging keys from another app or device."
        const val UNCHECKED_NOTE = "We couldn’t check whether this account uses another app or device for messaging."
        const val FOUND_NOTE = "We found messaging keys this account published from another app or device."
        const val FOUND_ON_DATE_PREFIX =
            "We found messaging keys this account published from another app or device, most recently on"
        const val SEPARATE_CHATS_HELP =
            "This device will have its own separate chats. Chats in the other app stay there and won’t appear here."
        const val EITHER_APP_HELP = "When someone starts a new chat with you, it may arrive in either app."
        const val IF_OTHER_APP_HELP =
            "If you use another app for messaging, this device will have its own separate chats, " +
                "and chats in that app won’t appear here."
        const val NOON_UTC_2025_11_02 = 1_762_084_800uL
        const val NOON_UTC_2026_03_15 = 1_773_576_000uL
    }
}
