package dev.ipf.whitenoise.android.ui.onboarding.setup

import androidx.compose.foundation.layout.Column
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import dev.ipf.marmotkit.OnboardingActionFfi
import dev.ipf.marmotkit.OnboardingStepFfi
import dev.ipf.whitenoise.android.ui.theme.WhiteNoiseTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], qualifiers = "en-w360dp-h1000dp-mdpi")
class AccountSetupRelayRowsTest {
    @get:Rule val rule = createComposeRule()

    /** Starts with ordered read/write duplicates so removal and Undo can be checked exactly. */
    private fun editor() =
        SetupEditor(3uL, OnboardingStepFfi.RELAYS, OnboardingActionFfi.EDIT_RELAYS)
            .withRelayDeclaration(relayRepairFixture())
            .copy(
                reads = "wss://keep.example\nwss://remove.example\nwss://remove.example",
                writes = "wss://remove.example\nwss://keep.example",
                originalReads = "wss://keep.example\nwss://remove.example\nwss://remove.example",
                originalWrites = "wss://remove.example\nwss://keep.example",
            )

    /** One address selection removes both roles while Undo restores exact duplicate order. */
    @Test fun removalStagesBothRolesAndUndoRestoresExactOrderAndDuplicates() {
        val editor = editor()
        val fields = SetupEditorFields(editor)
        rule.setContent { WhiteNoiseTheme { Column { SetupRelayEditorRows(editor, fields, false) } } }
        rule.onNodeWithTag("setup.relay.remove.1").performClick()
        rule.runOnIdle {
            assertEquals("wss://keep.example", fields.reads.text.toString())
            assertEquals("wss://keep.example", fields.writes.text.toString())
        }
        rule.onNodeWithTag("setup.relay.undo").performClick()
        rule.runOnIdle { assertEquals(editor, fields.current(editor)) }
    }

    /** Undo expires when the user changes the draft after removal. */
    @Test fun unrelatedAddressEditsRetireUndoRatherThanOverwriteNewInput() {
        val editor = editor()
        val fields = SetupEditorFields(editor)
        rule.setContent { WhiteNoiseTheme { Column { SetupRelayEditorRows(editor, fields, false) } } }
        rule.onNodeWithTag("setup.relay.remove.1").performClick()
        rule.runOnIdle { fields.reads.edit { append("\nwss://new.example") } }
        rule.onNodeWithTag("setup.relay.undo").assertDoesNotExist()
        rule.runOnIdle { assertTrue(fields.reads.text.endsWith("wss://new.example")) }
    }

    /** A required publication route cannot be removed into a reviewable empty proposal. */
    @Test fun removingLastPublicationRouteCannotCreateAReviewableDraft() {
        val editor = editor().copy(reads = "wss://keep.example", writes = "wss://last.example")
        val after = editor.withoutRelayEndpoint("wss://last.example")
        assertTrue(!after.canReviewRelayEdit)
        assertEquals("wss://keep.example", after.reads)
    }

    /** Inbox capabilities use invitation language while preserving the underlying native role. */
    @Test fun inboxRowsShowInvitationPurpose() {
        val editor = editor().copy(step = OnboardingStepFfi.INBOX_RELAYS, writes = "")
        val fields = SetupEditorFields(editor)
        rule.setContent { WhiteNoiseTheme { Column { SetupRelayEditorRows(editor, fields, false) } } }
        rule.onAllNodesWithText("Receive invitations").assertCountEquals(2)
    }

    /** An unsafe native declaration remains visible and read-only. */
    @Test fun malformedDeclarationDisablesRemovalWithoutDiscardingItsData() {
        val broken =
            relayRepairFixture().copy(
                beforeTags =
                    listOf(
                        dev.ipf.marmotkit.OnboardingRelayTagFfi(
                            listOf("r", " bad "),
                            " bad ",
                            dev.ipf.marmotkit.OnboardingRelayTagRoleFfi.READ,
                        ),
                    ),
            )
        val editor =
            SetupEditor(3uL, OnboardingStepFfi.RELAYS, OnboardingActionFfi.EDIT_RELAYS)
                .withRelayDeclaration(broken)
        val fields = SetupEditorFields(editor)
        rule.setContent { WhiteNoiseTheme { Column { SetupRelayEditorRows(editor, fields, false) } } }
        rule.onNodeWithTag("setup.relay.remove.0").assertIsNotEnabled()
        rule.runOnIdle { assertEquals(" bad ", fields.reads.text.toString()) }
    }

    /** Whitespace variants share one row and one Remove gesture while Undo keeps raw draft text. */
    @Test fun typedWhitespaceVariantsRemoveTogetherAndUndoRestoresRawInput() {
        val editor =
            editor().copy(
                reads = "wss://keep.example\nwss://remove.example\nwss://remove.example ",
                writes = " wss://remove.example \nwss://keep.example",
            )
        val fields = SetupEditorFields(editor)
        rule.setContent { WhiteNoiseTheme { Column { SetupRelayEditorRows(editor, fields, false) } } }
        rule.onAllNodesWithText("wss://remove.example").assertCountEquals(1)
        rule.onNodeWithTag("setup.relay.remove.1").performClick()
        rule.runOnIdle {
            assertEquals(listOf("wss://keep.example"), fields.current(editor).request().readRelays)
            assertEquals(listOf("wss://keep.example"), fields.current(editor).request().writeRelays)
        }
        rule.onNodeWithTag("setup.relay.undo").performClick()
        rule.runOnIdle { assertEquals(editor, fields.current(editor)) }
    }

    /** The raw-field disclosure reports its state without changing visible copy. */
    @Test fun editAddressesReportsExpandedState() {
        val editor = editor()
        val fields = SetupEditorFields(editor)
        rule.setContent { WhiteNoiseTheme { Column { SetupEditorContent(editor, fields, false, { "Quiet Otter" }) } } }
        rule
            .onNodeWithTag("setup.relay.edit_addresses")
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "Collapsed"))
            .performClick()
        rule
            .onNodeWithTag("setup.relay.edit_addresses")
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "Expanded"))
        rule.onNodeWithTag("setup.editor.reads").assertExists()
    }

    /** An in-flight native command disables draft removal. */
    @Test fun busyEditorCannotRemove() {
        val editor = editor()
        val fields = SetupEditorFields(editor)
        rule.setContent { WhiteNoiseTheme { Column { SetupRelayEditorRows(editor, fields, true) } } }
        rule.onNodeWithTag("setup.relay.remove.1").assertIsNotEnabled()
    }
}
