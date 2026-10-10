package dev.ipf.whitenoise.android.ui.onboarding.setup

import dev.ipf.marmotkit.OnboardingActionFfi
import dev.ipf.marmotkit.OnboardingRelayRepairFfi
import dev.ipf.marmotkit.OnboardingRelayRepairModeFfi
import dev.ipf.marmotkit.OnboardingRelayTagFfi
import dev.ipf.marmotkit.OnboardingRelayTagRoleFfi
import dev.ipf.marmotkit.OnboardingRepairProposalFfi
import dev.ipf.marmotkit.OnboardingStepFfi
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Exercises lossless prefill and account/revision fencing with controllable native decisions. */
class AccountSetupRelayRepairTest {
    /** Native ordered endpoints prefill both capabilities without creating an empty replacement. */
    @Test fun prefillPreservesStringsAndRejectsUntouchedOrEmptyReplacement() {
        val editor =
            SetupEditor(4uL, OnboardingStepFfi.RELAYS, OnboardingActionFfi.EDIT_RELAYS)
                .withRelayDeclaration(relayRepairFixture())
        assertEquals("wss://Custom.example/path/", editor.reads)
        assertEquals(editor.reads, editor.writes)
        assertFalse(editor.canReviewRelayEdit)
        assertTrue(editor.copy(writes = "wss://another.example").canReviewRelayEdit)
        assertFalse(editor.copy(reads = "", writes = "").canReviewRelayEdit)
    }

    /** Field formatting that request() discards cannot enable an unchanged repair. */
    @Test fun whitespaceAndBlankLinesDoNotEnableReview() {
        val editor =
            SetupEditor(4uL, OnboardingStepFfi.RELAYS, OnboardingActionFfi.EDIT_RELAYS)
                .withRelayDeclaration(relayRepairFixture())
        val formatted = editor.copy(reads = "\n ${editor.reads} \n\n", writes = "\t${editor.writes}\t\n")
        assertEquals(editor.request(), formatted.request())
        assertFalse(formatted.canReviewRelayEdit)
        assertTrue(formatted.copy(writes = "\n wss://changed.example \n").canReviewRelayEdit)
    }

    /** Inbox edits use the same submitted-line comparison and still require a nonempty list. */
    @Test fun inboxWhitespaceAndEmptyListsCannotBeReviewed() {
        val repair = relayRepairFixture()
        val inboxTags = listOf(repair.beforeTags.single().copy(role = OnboardingRelayTagRoleFfi.INBOX))
        val editor =
            SetupEditor(4uL, OnboardingStepFfi.INBOX_RELAYS, OnboardingActionFfi.EDIT_RELAYS)
                .withRelayDeclaration(
                    repair.copy(beforeTags = inboxTags),
                )
        assertFalse(editor.copy(reads = "\n ${editor.reads} \n").canReviewRelayEdit)
        assertFalse(editor.copy(reads = " \n\t").canReviewRelayEdit)
        assertTrue(editor.copy(reads = " wss://changed.example\n").canReviewRelayEdit)
    }

    /** The published lossless setter accepts mixed roles, duplicate occurrences and opaque extension fields. */
    @Test fun complexDeclarationsCanBeReviewedWithoutChangingTheirPrefill() {
        val repair = relayRepairFixture()
        val tag = repair.beforeTags.single()
        val examples =
            listOf(
                listOf(
                    tag,
                    tag.copy(
                        fields = listOf("r", "wss://other.example", "write"),
                        endpoint = "wss://other.example",
                        role = OnboardingRelayTagRoleFfi.WRITE,
                    ),
                ),
                listOf(tag.copy(fields = tag.fields + "custom")),
                listOf(tag, tag),
                listOf(tag.copy(fields = listOf("r", tag.endpoint!!, "WRITE"), role = OnboardingRelayTagRoleFfi.WRITE)),
            )
        examples.forEach { tags ->
            val editor =
                SetupEditor(4uL, OnboardingStepFfi.RELAYS, OnboardingActionFfi.EDIT_RELAYS)
                    .withRelayDeclaration(repair.copy(beforeTags = tags))
            assertTrue(editor.canEditRelayDeclaration)
            assertTrue(editor.copy(writes = "wss://changed.example").canReviewRelayEdit)
        }
    }

    /** Missing source data and endpoint strings that line fields would alter remain non-submittable. */
    @Test fun unavailableOrUnrepresentableDeclarationsStayReadOnly() {
        val draft = SetupEditor(4uL, OnboardingStepFfi.RELAYS, OnboardingActionFfi.EDIT_RELAYS)
        assertFalse(draft.canEditRelayDeclaration)
        val repair = relayRepairFixture()
        val endpoints =
            listOf(null, "", " wss://relay.example", "wss://relay.example\npath", "wss://relay.example\rpath")
        for (endpoint in endpoints) {
            val tag = repair.beforeTags.single().copy(endpoint = endpoint)
            val editor = draft.withRelayDeclaration(repair.copy(beforeTags = listOf(tag)))
            assertFalse(editor.canEditRelayDeclaration)
            assertFalse(editor.copy(writes = "wss://changed.example").canReviewRelayEdit)
        }
    }

    /** A native preview failure cannot expose editable empty lists. */
    @Test fun failedLoadDoesNotOpenEditor() =
        runTest {
            val client = FakeSetupClient(relayDecision()).apply { fail = true }
            val controller = AccountSetupController(SETUP_TEST_ACCOUNT, client, backgroundScope, { true }, {}, {})
            controller.reconnect()
            runCurrent()
            controller.edit(OnboardingStepFfi.RELAYS, OnboardingActionFfi.EDIT_RELAYS, 3uL)
            runCurrent()
            assertNull(controller.state.value.editor)
            assertTrue(controller.state.value.error)
            controller.close()
        }

    /** The editor uses the returned proposal revision and can submit only a changed draft. */
    @Test fun nativePreviewPrefillsThenEditedRequestUsesNewRevision() =
        runTest {
            val client = FakeSetupClient(relayDecision())
            val preview = relayPreviewSnapshot()
            client.executeResult = { request ->
                if (request.action ==
                    OnboardingActionFfi.USE_RECOMMENDED_RELAYS
                ) {
                    preview.also { client.current = it }
                } else {
                    client.current
                }
            }
            val controller = AccountSetupController(SETUP_TEST_ACCOUNT, client, backgroundScope, { true }, {}, {})
            controller.reconnect()
            runCurrent()
            controller.edit(OnboardingStepFfi.RELAYS, OnboardingActionFfi.EDIT_RELAYS, 3uL)
            runCurrent()
            val editor = controller.state.value.editor!!
            assertEquals(4uL, editor.revision)
            controller.submit(editor.request())
            runCurrent()
            assertEquals(1, client.requests.size)
            controller.updateEditor(editor.copy(writes = "wss://changed.example"))
            controller.submit(
                controller.state.value.editor!!
                    .request(),
            )
            runCurrent()
            assertEquals(OnboardingActionFfi.EDIT_RELAYS, client.requests.last().action)
            assertEquals(4uL, client.requests.last().revision)
            controller.close()
        }

    /** A result completing after an account/runtime change cannot install an editor. */
    @Test fun latePreviewCannotCrossRuntimeOwnership() =
        runTest {
            val client =
                FakeSetupClient(relayDecision()).apply {
                    hold = CompletableDeferred()
                    executeResult = { relayPreviewSnapshot() }
                }
            var current = true
            val controller = AccountSetupController(SETUP_TEST_ACCOUNT, client, backgroundScope, { current }, {}, {})
            controller.reconnect()
            runCurrent()
            controller.edit(OnboardingStepFfi.RELAYS, OnboardingActionFfi.EDIT_RELAYS, 3uL)
            runCurrent()
            current = false
            client.hold!!.complete(Unit)
            runCurrent()
            assertNull(controller.state.value.editor)
            controller.close()
        }

    /** Later checkpoint updates invalidate the old draft rather than silently moving its revision. */
    @Test fun changedCheckpointInvalidatesOpenEditor() =
        runTest {
            val client = FakeSetupClient(relayDecision()).apply { executeResult = { relayPreviewSnapshot() } }
            val controller = AccountSetupController(SETUP_TEST_ACCOUNT, client, backgroundScope, { true }, {}, {})
            controller.reconnect()
            runCurrent()
            controller.edit(OnboardingStepFfi.RELAYS, OnboardingActionFfi.EDIT_RELAYS, 3uL)
            runCurrent()
            assertTrue(controller.state.value.editor != null)
            client.updates.send(relayPreviewSnapshot().copy(revision = 5uL))
            runCurrent()
            assertNull(controller.state.value.editor)
            controller.close()
        }

    /** Recovery cannot reuse a draft from an unrecovered attempt with the same revision. */
    @Test fun recoveryEpochInvalidatesOpenEditorAtSameRevision() =
        runTest {
            val client = FakeSetupClient(relayDecision()).apply { executeResult = { relayPreviewSnapshot() } }
            val controller = AccountSetupController(SETUP_TEST_ACCOUNT, client, backgroundScope, { true }, {}, {})
            controller.reconnect()
            runCurrent()
            controller.edit(OnboardingStepFfi.RELAYS, OnboardingActionFfi.EDIT_RELAYS, 3uL)
            runCurrent()
            assertTrue(controller.state.value.editor != null)
            client.updates.send(relayPreviewSnapshot().copy(recoveryEpoch = "new-recovery-epoch"))
            runCurrent()
            assertNull(controller.state.value.editor)
            controller.close()
        }
}

/** A native decision with both explicit repair choices available. */
internal fun relayDecision() =
    setupSnapshot(
        OnboardingStepFfi.RELAYS,
        listOf(OnboardingActionFfi.USE_RECOMMENDED_RELAYS, OnboardingActionFfi.EDIT_RELAYS, OnboardingActionFfi.RETRY),
    )

/** An exact native declaration used by controller and rendering regressions. */
internal fun relayRepairFixture(): OnboardingRelayRepairFfi {
    val tag =
        OnboardingRelayTagFfi(
            listOf("r", "wss://Custom.example/path/"),
            "wss://Custom.example/path/",
            OnboardingRelayTagRoleFfi.UNMARKED,
        )
    return OnboardingRelayRepairFfi(
        OnboardingRelayRepairModeFfi.ADDITIVE,
        "source",
        "preserved content",
        "preserved content",
        listOf(tag),
        listOf(tag),
        emptyList(),
    )
}

/** Advances the checkpoint as the native proposal command does, without approving it. */
internal fun relayPreviewSnapshot() =
    setupSnapshot(
        OnboardingStepFfi.RELAYS,
        listOf(OnboardingActionFfi.APPROVE_REPAIR, OnboardingActionFfi.CANCEL_REPAIR),
        revision = 4uL,
    ).copy(
        proposal =
            OnboardingRepairProposalFfi(
                OnboardingStepFfi.RELAYS,
                4uL,
                "source",
                emptyList(),
                emptyList(),
                null,
                null,
                relayRepairFixture(),
            ),
    )
