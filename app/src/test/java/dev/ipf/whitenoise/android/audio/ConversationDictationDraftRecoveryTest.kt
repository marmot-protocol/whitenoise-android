package dev.ipf.whitenoise.android.audio

import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

internal class ConversationDictationDraftRecoveryTest {
    @Test
    fun repeatedFailureAndNewTailKeepOneTranscript() {
        val f = Fixture()
        assertTrue(f.recovery.recover(1, f.target, "first"))
        assertTrue(f.recovery.recover(1, f.target, "first"))
        assertEquals(1, f.writes)
        assertTrue(f.recovery.recover(1, f.target, "first second"))
        assertEquals("Draft first second", f.draft.value.text)
        val admission = f.recovery.sendTarget(1, f.target)
        assertNotNull(admission)
        assertEquals(f.draft.revision, admission?.capturedDraftRevision)
        assertEquals("Draft first second", conversationDictationSendRequest(f.target, "first second")?.payload)
    }

    @Test
    fun newerEditsReceiveOnlyTheNewSuffixAndAreNotAbsorbedByRetrySend() {
        val f = Fixture()
        f.recovery.recover(1, f.target, "first")
        f.edit("Draft first edited")
        f.recovery.recover(1, f.target, "first second")
        f.recovery.recover(1, f.target, "first second third")
        assertEquals("Draft first edited second third", f.draft.value.text)
        assertNull(f.recovery.sendTarget(1, f.target))
    }

    @Test
    fun editsBeforeTheFailureRemainThroughFurtherRecognition() {
        val f = Fixture()
        f.edit("New draft")
        f.recovery.recover(1, f.target, "first")
        f.recovery.recover(1, f.target, "first second")
        assertEquals("New draft first second", f.draft.value.text)
        assertNull(f.recovery.sendTarget(1, f.target))
    }

    @Test
    fun ourOwnClearAndRestoreKeepTheRecoveredSendFence() {
        val f = Fixture()
        f.recovery.recover(1, f.target, "first")
        val saved = f.draft.value.text
        f.edit("")
        f.recovery.markCleared(1, f.target, f.draft.revision)
        f.edit(saved)
        f.recovery.markRestored(1, f.target, f.draft.revision)
        f.recovery.recover(1, f.target, "first")
        assertEquals(saved, f.draft.value.text)
        assertEquals(f.draft.revision, f.recovery.sendTarget(1, f.target)?.capturedDraftRevision)
    }

    @Test
    fun anEditToOurEmptiedDraftPreservesTheEntireUnsentPayloadOnce() {
        val f = Fixture()
        f.recovery.recover(1, f.target, "first")
        f.edit("")
        f.recovery.markCleared(1, f.target, f.draft.revision)
        f.edit("New draft")
        f.recovery.recover(1, f.target, "first")
        f.recovery.recover(1, f.target, "first")
        assertEquals("New draft Draft first", f.draft.value.text)
        assertNull(f.recovery.sendTarget(1, f.target))
    }

    @Test
    fun aFailedFirstSendPreservesItsClearedCapturedPrefix() {
        val f = Fixture()
        f.edit("")
        val empty = f.draft.revision
        assertTrue(f.recovery.recover(1, f.target, "first", options = ConversationDictationDraftRecovery.Options(restoreCapturedPrefix = true, ownedEmptyRevision = empty)))
        assertEquals("Draft first", f.draft.value.text)
        assertNotNull(f.recovery.sendTarget(1, f.target))
    }

    @Test
    fun rejectedWritesRetainTheTextForAutomaticRecoveryLater() {
        val f = Fixture()
        f.reject = true
        assertFalse(f.recovery.recover(1, f.target, "first"))
        assertEquals("Draft", f.draft.value.text)
        f.reject = false
        assertTrue(f.recovery.recover(1, f.target, "first"))
        assertEquals("Draft first", f.draft.value.text)
    }

    @Test
    fun replayWordingChangesDoNotRepeatAnAcknowledgedPrefixAfterEditing() {
        val f = Fixture()
        f.recovery.recover(1, f.target, "first second", options = ConversationDictationDraftRecovery.Options(acknowledgedPrefix = "first"))
        f.edit("Draft first second edited")
        assertTrue(f.recovery.recover(1, f.target, "First, SECOND third", options = ConversationDictationDraftRecovery.Options(acknowledgedPrefix = "First, SECOND third")))
        assertEquals("Draft first second edited third", f.draft.value.text)
        assertNull(f.recovery.sendTarget(1, f.target))
    }

    @Test
    fun aDifferentUnacknowledgedResultIsNotBlindlyAppendedAfterEditing() {
        val f = Fixture()
        f.recovery.recover(1, f.target, "first preview", options = ConversationDictationDraftRecovery.Options(acknowledgedPrefix = "first"))
        f.edit("Draft first preview edited")
        assertTrue(f.recovery.recover(1, f.target, "first corrected result", options = ConversationDictationDraftRecovery.Options(acknowledgedPrefix = "first corrected result")))
        assertEquals("Draft first preview edited corrected result", f.draft.value.text)
        assertNull(f.recovery.sendTarget(1, f.target))
    }

    @Test
    fun aFullyRewrittenProviderResultStillAppearsInAnEditedDraft() {
        val f = Fixture()
        f.recovery.recover(1, f.target, "blue sky", options = ConversationDictationDraftRecovery.Options(acknowledgedPrefix = ""))
        f.edit("Draft blue sky edited")
        assertTrue(f.recovery.recover(1, f.target, "green fields"))
        assertEquals("Draft blue sky edited green fields", f.draft.value.text)
        assertNull(f.recovery.sendTarget(1, f.target))
    }

    private class Fixture {
        var draft = ConversationDictationDraftSnapshot(TextFieldValue("Draft", TextRange(5)), 0)
        val target = ConversationDictationTarget("account", "group", draft.value, 0, ConversationDictationMode.InApp)
        var writes = 0
        var reject = false
        val recovery = ConversationDictationDraftRecovery(
            read = { account, group ->
                assertEquals("account", account)
                assertEquals("group", group)
                draft
            },
            write = { _, _, expected, value ->
                if (reject || expected != draft.revision) {
                    null
                } else {
                    writes++
                    draft = ConversationDictationDraftSnapshot(value, expected + 1)
                    draft.revision
                }
            },
        )

        fun edit(text: String) {
            draft = ConversationDictationDraftSnapshot(TextFieldValue(text, TextRange(text.length)), draft.revision + 1)
        }
    }
}
