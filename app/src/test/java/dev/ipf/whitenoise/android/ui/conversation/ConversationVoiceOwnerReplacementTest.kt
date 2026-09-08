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
     * chat, message, attachment, and runtime keys. The current owner must reuse
     * the serialized publication without accepting the stale owner's row state.
     */
    @Test
    fun inPlaceOwnerReplacementReusesTheFileButRejectsTheOldPresentation() {
        val scenario = ownerReplacementScenario()
        try {
            val host = startOldOwnerDownload(scenario)
            val newOwnerAnchor = replaceOwnerAndAwaitSerializedDownload(scenario, host)
            assertCompletedPublicationTransfersToCurrentOwner(scenario, newOwnerAnchor)
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

    /** Rebinds ConversationScreen and holds its new source request behind the active path owner. */
    private fun replaceOwnerAndAwaitSerializedDownload(
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
            assertVoiceActionTarget(scenario.voiceId, R.string.media_downloading)
            assertEquals(1, scenario.oldControl.materializationAttempts)
            assertEquals(0, scenario.newControl.materializationAttempts)
        }
    }

    /** Lets the current owner reuse complete bytes without reviving the stale owner's presentation. */
    private fun assertCompletedPublicationTransfersToCurrentOwner(
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
        scenario.oldControl.awaitSuccessfulMaterializationReturn()
        scenario.newControl.awaitSuccessfulMaterializationReturn()
        scenario.newControl.awaitHydrationStarted()

        assertEquals(0, scenario.newControl.materializationAttempts)
        assertFalse(scenario.oldControl.waveformStarted.isCompleted)
        assertFalse(scenario.oldControl.durationStarted.isCompleted)
        scenario.newControl.releaseHydration()
        scenario.newControl.awaitHydrationCompleted()
        awaitVoiceAction(scenario.voiceId, R.string.voice_message_pause)
        assertViewportStayedFixed("serialized owner replacement", newOwnerAnchor, scenario.newEvidence, checkpoint)
        assertNoScrollWrites("serialized owner replacement", scenario.newEvidence)
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
