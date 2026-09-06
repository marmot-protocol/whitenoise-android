package dev.ipf.whitenoise.android.ui.conversation

import dev.ipf.whitenoise.android.R
import dev.ipf.whitenoise.android.media.MediaCacheDirs
import dev.ipf.whitenoise.android.state.awaitConversationCondition
import dev.ipf.whitenoise.android.ui.conversation.media.cachedVoiceAttachmentFile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/** Real-screen regressions for voice state disposal and phase-bound viewport evidence. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "en-w360dp-h780dp-mdpi")
internal class ConversationVoiceOwnerReplacementTest : ConversationVoiceDownloadAnchorTestBase() {
    /**
     * Replaces account and controller in place while both owners use identical
     * chat, message, attachment, and runtime keys. Each controller must start
     * independent source work and only the current owner may publish row state.
     */
    @Test
    fun inPlaceOwnerReplacementRejectsTheOldMaterializationCompletion() {
        val scenario = ownerReplacementScenario()
        try {
            val host = startOldOwnerDownload(scenario)
            val newOwnerAnchor = replaceOwnerAndStartIndependentDownload(scenario, host)
            assertOldOwnerCompletionIsRejected(scenario, newOwnerAnchor)
            assertCurrentOwnerCompletionSucceeds(scenario, newOwnerAnchor)
        } finally {
            closeFixture(scenario.oldFixture, scenario.runtime)
            closeFixture(scenario.newFixture, scenario.runtime)
        }
    }

    /** Proves unchanged geometry needs a newly requested sample and cannot self-compare old evidence. */
    @Test
    fun phaseBoundViewportEvidenceRejectsAnOmittedPostTransitionSample() {
        val fixture = conversationFixture(setOf(HISTORY_VOICE_INDEX), idOffset = 450)
        val voiceId = fixture.voiceMessageIds.single()
        val control = VoiceControl(voiceId, materializationAttemptCount = 1)
        val runtime = ControlledVoicePresentationRuntime(mapOf(voiceId to control))
        val evidence = RecordingConversationScrollEvidenceSink()
        try {
            awaitConversationCondition { fixture.controller.timeline.size == fixture.records.size }
            showConversation(fixture, runtime, evidence, voiceId)
            val unchanged = evidence.awaitAnchor(voiceId)
            val checkpoint = evidence.checkpoint()
            val missingRevision = unchanged.captureRevision + 1L

            assertThrows(IllegalStateException::class.java) {
                evidence.requirePhaseBoundViewports(checkpoint, missingRevision)
            }
            val phaseBound = evidence.requestPhaseBoundViewports(checkpoint)

            assertTrue(phaseBound.any { it.captureRevision >= missingRevision })
            phaseBound.forEach { snapshot -> assertSameViewport("phase-bound unchanged", unchanged, snapshot) }
        } finally {
            closeFixture(fixture, runtime)
        }
    }

    /** Constructs two controllers with deliberately identical chat and attachment identity. */
    private fun ownerReplacementScenario(): OwnerReplacementScenario {
        val oldFixture = conversationFixture(setOf(HISTORY_VOICE_INDEX), idOffset = 400, accountRef = "account-a")
        val newFixture = conversationFixture(setOf(HISTORY_VOICE_INDEX), idOffset = 400, accountRef = "account-b")
        val voiceId = oldFixture.voiceMessageIds.single()
        assertEquals(voiceId, newFixture.voiceMessageIds.single())
        val reference = oldFixture.references.getValue(voiceId)
        File(
            File(context.cacheDir, MediaCacheDirs.VOICE),
            "$voiceId-0-${reference.sourceEpoch}.wav",
        ).delete()
        val oldControl = VoiceControl(voiceId, materializationAttemptCount = 1)
        val newControl = VoiceControl(voiceId, materializationAttemptCount = 1)
        val runtime =
            ControlledVoicePresentationRuntime(
                controls = emptyMap(),
                controllerControls = mapOf(oldFixture.controller to oldControl, newFixture.controller to newControl),
            )
        return OwnerReplacementScenario(
            oldFixture = oldFixture,
            newFixture = newFixture,
            voiceId = voiceId,
            oldControl = oldControl,
            newControl = newControl,
            runtime = runtime,
            oldEvidence = RecordingConversationScrollEvidenceSink(),
            newEvidence = RecordingConversationScrollEvidenceSink(),
        )
    }

    /** Starts the first controller's source work while the real screen remains mounted. */
    private fun startOldOwnerDownload(scenario: OwnerReplacementScenario): ConversationTestHost {
        awaitConversationCondition { scenario.oldFixture.controller.timeline.size == scenario.oldFixture.records.size }
        awaitConversationCondition { scenario.newFixture.controller.timeline.size == scenario.newFixture.records.size }
        return showConversation(
            scenario.oldFixture,
            scenario.runtime,
            scenario.oldEvidence,
            scenario.voiceId,
        ).also {
            clickVoiceAction(scenario.voiceId, R.string.media_tap_to_download)
            awaitAttachmentOpenIntent(scenario.oldFixture.controller, scenario.voiceId)
            scenario.oldControl.awaitMaterializationAttempt(0)
        }
    }

    /** Rebinds ConversationScreen in place and proves the second controller starts a distinct flight. */
    private fun replaceOwnerAndStartIndependentDownload(
        scenario: OwnerReplacementScenario,
        host: ConversationTestHost,
    ): ConversationViewportEvidence {
        host.replaceOwnerInPlace(
            scenario.newFixture,
            scenario.runtime,
            scenario.newEvidence,
            historySnapshot(scenario.newFixture, scenario.voiceId),
        )
        return scenario.newEvidence.awaitAnchor(scenario.voiceId).also { newOwnerAnchor ->
            assertEquals("account-b", newOwnerAnchor.accountRef)
            assertVoiceActionTarget(scenario.voiceId, R.string.media_tap_to_download)
            clickVoiceAction(scenario.voiceId, R.string.media_tap_to_download)
            awaitAttachmentOpenIntent(scenario.newFixture.controller, scenario.voiceId)
            scenario.newControl.awaitMaterializationAttempt(0)
            assertEquals(1, scenario.oldControl.materializationAttempts)
            assertEquals(1, scenario.newControl.materializationAttempts)
        }
    }

    /** Releases only stale work and requires the current row to remain downloading and unhydrated. */
    private fun assertOldOwnerCompletionIsRejected(
        scenario: OwnerReplacementScenario,
        newOwnerAnchor: ConversationViewportEvidence,
    ) {
        scenario.newEvidence.clearWrites()
        val checkpoint = scenario.newEvidence.checkpoint()
        scenario.oldControl.succeedMaterialization(0)
        awaitMountedConversationCondition("old owner cache publication") {
            cachedVoiceAttachmentFile(
                context = context,
                messageIdHex = scenario.voiceId,
                attachmentIndex = 0,
                reference = scenario.oldFixture.references.getValue(scenario.voiceId),
            ) != null
        }
        composeRule.waitForIdle()

        assertVoiceActionTarget(scenario.voiceId, R.string.media_downloading)
        assertFalse(scenario.oldControl.waveformStarted.isCompleted)
        assertFalse(scenario.oldControl.durationStarted.isCompleted)
        assertFalse(scenario.newControl.waveformStarted.isCompleted)
        assertFalse(scenario.newControl.durationStarted.isCompleted)
        assertViewportStayedFixed("old owner completion", newOwnerAnchor, scenario.newEvidence, checkpoint)
        assertNoScrollWrites("old owner completion", scenario.newEvidence)
    }

    /** Releases the current owner and requires its real row to hydrate without moving the viewport. */
    private fun assertCurrentOwnerCompletionSucceeds(
        scenario: OwnerReplacementScenario,
        newOwnerAnchor: ConversationViewportEvidence,
    ) {
        val checkpoint = scenario.newEvidence.checkpoint()
        scenario.newControl.succeedMaterialization(0)
        scenario.newControl.awaitSuccessfulMaterializationReturn()
        scenario.newControl.awaitHydrationStarted()
        scenario.newControl.releaseHydration()
        scenario.newControl.awaitHydrationCompleted()
        awaitVoiceAction(scenario.voiceId, R.string.voice_message_pause)
        assertViewportStayedFixed("current owner completion", newOwnerAnchor, scenario.newEvidence, checkpoint)
        assertNoScrollWrites("current owner completion", scenario.newEvidence)
        assertEquals("account-b", scenario.newEvidence.latestViewport().accountRef)
    }

    /** Owners, controls, and evidence for one exact same-identity replacement race. */
    private inner class OwnerReplacementScenario(
        val oldFixture: VoiceConversationFixture,
        val newFixture: VoiceConversationFixture,
        val voiceId: String,
        val oldControl: VoiceControl,
        val newControl: VoiceControl,
        val runtime: ControlledVoicePresentationRuntime,
        val oldEvidence: RecordingConversationScrollEvidenceSink,
        val newEvidence: RecordingConversationScrollEvidenceSink,
    )
}
