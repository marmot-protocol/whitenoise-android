package dev.ipf.whitenoise.android.ui.conversation.media

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The in-memory transcript cache: automatic default, explicit overrides, account scoping and bounded size. */
class ReceivedVoiceTranscriptsTest {
    private val complete = AudioTranscriptUi(AudioTranscriptPhase.COMPLETE, "On my way")

    /** With no explicit choice a message follows the automatic setting; an explicit choice wins either way. */
    @Test
    fun explicitChoiceOverridesAutomaticSetting() {
        val transcripts = ReceivedVoiceTranscripts()
        val automatic = VoiceTranscriptionScope(transcripts, "alice", automatic = true)
        val manual = VoiceTranscriptionScope(transcripts, "alice", automatic = false)

        assertTrue(automatic.shown("m1"))
        assertFalse(automatic.requestedExplicitly("m1"))
        assertFalse(manual.shown("m1"))

        automatic.setShown("m1", false)
        assertFalse(automatic.shown("m1"))
        assertFalse(automatic.requestedExplicitly("m1"))

        manual.setShown("m2", true)
        assertTrue(manual.shown("m2"))
        assertTrue(manual.requestedExplicitly("m2"))
    }

    /** Two accounts in the same group never see each other's transcript or choice. */
    @Test
    fun accountsDoNotShareTranscriptsOrChoices() {
        val transcripts = ReceivedVoiceTranscripts()
        val alice = VoiceTranscriptionScope(transcripts, "alice", automatic = false)
        val bob = VoiceTranscriptionScope(transcripts, "bob", automatic = false)

        alice.storeResult("m1:0:1", complete)
        alice.setShown("m1", true)

        assertEquals(complete, alice.result("m1:0:1"))
        assertNull(bob.result("m1:0:1"))
        assertFalse(bob.shown("m1"))
    }

    @Test
    fun removedAccountRejectsWritesFromOldScope() {
        val transcripts = ReceivedVoiceTranscripts()
        val old = VoiceTranscriptionScope(transcripts, "alice", automatic = false)
        val other = VoiceTranscriptionScope(transcripts, "bob", automatic = false)
        old.storeResult("attachment", complete)
        old.setShown("message", true)

        transcripts.forgetAccount("alice")
        old.storeResult("attachment", complete)
        old.setShown("message", true)
        other.storeResult("attachment", complete)

        val current = VoiceTranscriptionScope(transcripts, "alice", automatic = false)
        assertNull(current.result("attachment"))
        assertFalse(current.shown("message"))
        assertEquals(complete, other.result("attachment"))
        current.storeResult("attachment", complete)
        assertEquals(complete, current.result("attachment"))
    }

    /** The oldest entries leave first once the cache is full, and a re-stored entry counts as new. */
    @Test
    fun evictsOldestPastCapacity() {
        val transcripts = ReceivedVoiceTranscripts(capacity = 2)
        transcripts.storeResult("a", complete)
        transcripts.storeResult("b", complete)
        transcripts.storeResult("a", complete)
        transcripts.storeResult("c", complete)

        assertEquals(complete, transcripts.result("a"))
        assertNull(transcripts.result("b"))
        assertEquals(complete, transcripts.result("c"))
    }
}
