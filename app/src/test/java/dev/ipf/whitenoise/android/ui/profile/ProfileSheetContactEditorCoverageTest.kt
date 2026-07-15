package dev.ipf.whitenoise.android.ui.profile

import org.junit.Assert.assertEquals
import org.junit.Test

class ProfileSheetContactEditorCoverageTest {
    private val addLabel = "Add nickname & notes"
    private val notesLabel = "Notes"

    @Test
    fun rowValueShowsNicknameWhenSet() {
        assertEquals(
            "Alice",
            profileSheetContactPrivateDetailsRowValue(
                contactNickname = "Alice",
                contactNotes = null,
                addNicknameAndNotesLabel = addLabel,
                notesLabel = notesLabel,
            ),
        )
    }

    @Test
    fun rowValueShowsNicknameWhenNotesAlsoSet() {
        assertEquals(
            "Alice",
            profileSheetContactPrivateDetailsRowValue(
                contactNickname = "Alice",
                contactNotes = "private note",
                addNicknameAndNotesLabel = addLabel,
                notesLabel = notesLabel,
            ),
        )
    }

    @Test
    fun rowValueShowsEmptyFallbackWhenNeitherNicknameNorNotesSet() {
        assertEquals(
            addLabel,
            profileSheetContactPrivateDetailsRowValue(
                contactNickname = null,
                contactNotes = null,
                addNicknameAndNotesLabel = addLabel,
                notesLabel = notesLabel,
            ),
        )
    }

    @Test
    fun rowValueShowsNotesLabelForNoteOnlyState() {
        assertEquals(
            notesLabel,
            profileSheetContactPrivateDetailsRowValue(
                contactNickname = null,
                contactNotes = "remember to ask about vacation",
                addNicknameAndNotesLabel = addLabel,
                notesLabel = notesLabel,
            ),
        )
    }
}
