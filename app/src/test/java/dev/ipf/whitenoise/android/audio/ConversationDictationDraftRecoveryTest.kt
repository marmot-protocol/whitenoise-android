package dev.ipf.whitenoise.android.audio

import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
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
    fun repeatedRecoveryReportsOnlyNewAcceptedWrites() {
        val f = Fixture()
        assertEquals(
            ConversationDictationDraftRecovery.Result.Written,
            f.recovery.recoverWithResult(1, f.target, "first"),
        )
        assertEquals(
            ConversationDictationDraftRecovery.Result.AlreadyPresent,
            f.recovery.recoverWithResult(1, f.target, "first"),
        )
        assertEquals(1, f.writes)
        assertEquals(
            ConversationDictationDraftRecovery.Result.Written,
            f.recovery.recoverWithResult(1, f.target, "first second"),
        )
        assertEquals("Draft first second", f.draft.value.text)
        assertEquals(2, f.writes)
    }

    @Test
    fun editedDraftCannotReuseAnOldTranscriptReceipt() {
        listOf("Draft", "New text", "Draft first", "").forEach { edited ->
            val f = Fixture()
            assertTrue(f.recovery.recover(1, f.target, "first"))
            f.edit(edited)
            assertEquals(
                ConversationDictationDraftRecovery.Result.Unavailable,
                f.recovery.recoverWithResult(1, f.target, "first"),
            )
            assertFalse(f.recovery.recover(1, f.target, "first"))
            assertEquals(edited, f.draft.value.text)
            assertEquals(1, f.writes)
            assertNull(f.recovery.sendTarget(1, f.target))
        }
    }

    @Test
    fun newSuffixRespectsRemovalOfThePreviousInsertion() {
        val f = Fixture()
        assertTrue(f.recovery.recover(1, f.target, "first"))
        f.edit("New text")
        assertTrue(f.recovery.recover(1, f.target, "first second"))
        assertEquals("New text second", f.draft.value.text)
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
        f.recovery.updateDispatch(1, f.target, clearedRevision = f.draft.revision)
        f.edit(saved)
        f.recovery.updateDispatch(1, f.target, restoredRevision = f.draft.revision)
        assertEquals(
            ConversationDictationDraftRecovery.Result.AlreadyPresent,
            f.recovery.recoverWithResult(1, f.target, "first"),
        )
        assertEquals(1, f.writes)
        assertEquals(saved, f.draft.value.text)
        assertEquals(f.draft.revision, f.recovery.sendTarget(1, f.target)?.capturedDraftRevision)
    }

    @Test
    fun anEditToOurEmptiedDraftPreservesTheEntireUnsentPayloadOnce() {
        val f = Fixture()
        f.recovery.recover(1, f.target, "first")
        f.edit("")
        f.recovery.updateDispatch(1, f.target, clearedRevision = f.draft.revision)
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
        assertTrue(
            f.recovery.recover(
                1,
                f.target,
                "first",
                options =
                    ConversationDictationDraftRecovery.Options(
                        restoreCapturedPrefix = true,
                        ownedEmptyRevision = empty,
                    ),
            ),
        )
        assertEquals("Draft first", f.draft.value.text)
        assertNotNull(f.recovery.sendTarget(1, f.target))
    }

    @Test
    fun ownedEmptyRecoveryBypassesTheSameTranscriptShortcut() {
        val f = Fixture()
        assertTrue(f.recovery.recover(1, f.target, "first"))
        f.edit("")
        assertEquals(
            ConversationDictationDraftRecovery.Result.Written,
            f.recovery.recoverWithResult(
                1,
                f.target,
                "first",
                ConversationDictationDraftRecovery.Options(
                    restoreCapturedPrefix = true,
                    ownedEmptyRevision = f.draft.revision,
                ),
            ),
        )
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
    fun replayWordingChangesDoNotRepeatARepresentedPrefixAfterEditing() {
        val f = Fixture()
        f.recovery.recover(
            1,
            f.target,
            "first second",
        )
        f.edit("Draft first second edited")
        assertTrue(
            f.recovery.recover(
                1,
                f.target,
                "First, SECOND third",
            ),
        )
        assertEquals("Draft first second edited third", f.draft.value.text)
        assertTrue(f.recovery.recover(1, f.target, "first second third fourth"))
        assertEquals("Draft first second edited third fourth", f.draft.value.text)
        assertTrue(f.recovery.recover(1, f.target, "FIRST, second, third fourth fifth"))
        assertEquals("Draft first second edited third fourth fifth", f.draft.value.text)
        assertNull(f.recovery.sendTarget(1, f.target))
    }

    @Test
    fun aDifferentUnrepresentedResultIsNotBlindlyAppendedAfterEditing() {
        val f = Fixture()
        f.recovery.recover(
            1,
            f.target,
            "first preview",
        )
        f.edit("Draft first preview edited")
        assertFalse(
            f.recovery.recover(
                1,
                f.target,
                "first corrected result",
            ),
        )
        assertEquals("Draft first preview edited", f.draft.value.text)
        assertNull(f.recovery.sendTarget(1, f.target))
    }

    @Test
    fun aFullyRewrittenProviderResultCannotDuplicateAnEditedDraft() {
        val f = Fixture()
        f.recovery.recover(
            1,
            f.target,
            "blue sky",
        )
        f.edit("Draft blue sky edited")
        assertFalse(f.recovery.recover(1, f.target, "green fields"))
        assertEquals("Draft blue sky edited", f.draft.value.text)
        assertNull(f.recovery.sendTarget(1, f.target))
    }

    private class Fixture {
        var draft = ConversationDictationDraftSnapshot(TextFieldValue("Draft", TextRange(5)), 0)
        val target = ConversationDictationTarget("account", "group", draft.value, 0, ConversationDictationMode.InApp)
        var writes = 0
        var reject = false
        val recovery =
            ConversationDictationDraftRecovery(
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
