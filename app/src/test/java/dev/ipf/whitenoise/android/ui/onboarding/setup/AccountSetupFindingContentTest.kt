package dev.ipf.whitenoise.android.ui.onboarding.setup

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import dev.ipf.marmotkit.OnboardingActionFfi
import dev.ipf.marmotkit.OnboardingCheckPurposeFfi
import dev.ipf.marmotkit.OnboardingFindingContextFfi
import dev.ipf.marmotkit.OnboardingIssueFfi
import dev.ipf.marmotkit.OnboardingRelayCapabilityFfi
import dev.ipf.marmotkit.OnboardingRelayRepairModeFfi
import dev.ipf.marmotkit.OnboardingRelayTagChangeFfi
import dev.ipf.marmotkit.OnboardingRelayTagDispositionFfi
import dev.ipf.marmotkit.OnboardingStepFfi
import dev.ipf.whitenoise.android.ui.theme.WhiteNoiseTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Native finding provenance remains visible on production decision surfaces without opening Details. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], qualifiers = "en-w360dp-h780dp-mdpi")
class AccountSetupFindingContentTest {
    @get:Rule val composeRule = createComposeRule()

    /** The inline Remove shortcut cannot suppress the provenance of the finding that offered it. */
    @Test fun directRemovalRetainsNativeFindingEvidence() {
        val snapshot = setupSnapshot(OnboardingStepFfi.RELAYS, listOf(OnboardingActionFfi.EDIT_RELAYS))
        snapshot.steps.first { it.step == OnboardingStepFfi.RELAYS }.findings =
            listOf(
                setupFinding(
                    OnboardingIssueFfi.RETIRED_RELAY,
                    "wss://retired.example",
                    actions = listOf(OnboardingActionFfi.EDIT_RELAYS),
                    context = OnboardingFindingContextFfi.PUBLISHED_DECLARATION,
                    purpose = OnboardingCheckPurposeFfi.DECLARATION_VALIDATION,
                ),
                setupFinding(
                    OnboardingIssueFfi.RETIRED_RELAY,
                    "wss://retired.example",
                    context = OnboardingFindingContextFfi.DECLARED_ROUTE,
                    purpose = OnboardingCheckPurposeFfi.DIRECTORY_QUERY,
                ),
            )
        show(AccountSetupState(snapshot = snapshot))
        composeRule.onNodeWithTag("setup-remove-relay").assertExists()
        composeRule.onNodeWithText("Published declaration · Declaration validation · Result unknown").assertExists()
        composeRule.onNodeWithText("Declared relay · Directory query · Result unknown").assertExists()
    }

    /** The proposal's endpoint row keeps its check purpose before the user approves any publication. */
    @Test fun relayRepairRetainsNativeFindingEvidence() {
        val snapshot = relayPreviewSnapshot()
        val repair = snapshot.proposal!!.relayRepair!!
        val tag = repair.beforeTags.single()
        val endpoint = tag.endpoint
        repair.mode = OnboardingRelayRepairModeFfi.REMOVAL_ONLY
        repair.afterTags = emptyList()
        repair.changes =
            listOf(
                OnboardingRelayTagChangeFfi(
                    OnboardingRelayTagDispositionFfi.REMOVED,
                    0uL,
                    null,
                    tag.fields,
                    endpoint,
                    tag.role,
                    OnboardingRelayCapabilityFfi.READ_AND_WRITE,
                ),
            )
        snapshot.steps.first { it.step == OnboardingStepFfi.RELAYS }.findings =
            listOf(
                setupFinding(
                    OnboardingIssueFfi.TIMED_OUT,
                    endpoint,
                    context = OnboardingFindingContextFfi.REPAIR_APPROVAL,
                    purpose = OnboardingCheckPurposeFfi.SOURCE_FRESHNESS,
                ),
            )
        show(AccountSetupState(snapshot = snapshot))
        composeRule.onNodeWithText("Repair approval · Source freshness · Result unknown").assertExists()
    }

    /** Mounts the production screen without expanding details or submitting any native action. */
    private fun show(state: AccountSetupState) {
        composeRule.setContent {
            WhiteNoiseTheme {
                AccountSetupContent(
                    state = state,
                    onAction = {},
                    onEdit = { _, _, _ -> },
                    onEditorChange = {},
                    onSaveEditor = {},
                    onDismissEditor = {},
                    onReconnect = {},
                    onOpenChats = {},
                    onLater = {},
                    randomName = { "Quiet Otter" },
                )
            }
        }
    }
}
