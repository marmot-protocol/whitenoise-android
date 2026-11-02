package dev.ipf.whitenoise.android.media

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Which draft each pick decodes in, and when an ended draft's worker retires (#3373). A pick keeps the draft it
 * joined for good, a draft gains no pick once it has been sent or emptied, and it retires only when no running send
 * and no staged pick can still decode in it.
 */
class OutgoingDraftsTest {
    private val retired = mutableListOf<String>()
    private var created = 0

    /** A policy whose drafts are numbered in creation order and whose retirements are recorded. */
    private fun drafts() = OutgoingDrafts(newDraft = { "draft-${++created}" }, retire = { retired += it })

    /** Every pick of one draft shares it, and removing some of them neither ends nor retires it. */
    @Test
    fun picksOfOneDraftShareItUntilItIsEmptied() {
        val drafts = drafts()
        assertEquals("draft-1", drafts.draftOf("a"))
        assertEquals("draft-1", drafts.draftOf("b"))
        drafts.retain(setOf("b"))
        assertEquals("draft-1", drafts.draftOf("c"))
        assertEquals(emptyList<String>(), retired)
    }

    /** Emptying the composer ends the draft and retires it, so the next pick starts a new one. */
    @Test
    fun emptyingTheComposerEndsAndRetiresTheDraft() {
        val drafts = drafts()
        drafts.retain(emptySet())
        assertEquals("an empty composer before any pick ends nothing", "draft-1", drafts.draftOf("a"))
        drafts.retain(emptySet())
        assertEquals(listOf("draft-1"), retired)
        assertEquals("draft-2", drafts.draftOf("b"))
    }

    /**
     * Work still running for a pick of an emptied draft, such as a preparation that resumes after the composer was
     * emptied and another pick was made, decodes in the pick's own draft, never in the one picked into since.
     */
    @Test
    fun aPickThatLeftKeepsItsDraftForItsLateWork() {
        val drafts = drafts()
        drafts.retain(setOf("a"))
        assertEquals("draft-1", drafts.draftOf("a"))
        drafts.retain(emptySet())
        assertEquals(listOf("draft-1"), retired)
        drafts.retain(setOf("b"))
        assertEquals("draft-2", drafts.draftOf("b"))
        assertEquals("the resumed preparation keeps its draft", "draft-1", drafts.draftOf("a"))
    }

    /**
     * A sent pick keeps its draft through the send, a pick made during the send starts the next draft, and the sent
     * draft retires once the send has ended and its picks have left the composer, whichever comes last.
     */
    @Test
    fun aSentDraftRetiresAfterItsSendAndGainsNoLaterPick() {
        val drafts = drafts()
        drafts.draftOf("a")
        drafts.draftOf("b")
        val send = drafts.beginSend(listOf("a", "b"))
        assertEquals(setOf("draft-1"), send)
        assertEquals("draft-1", drafts.draftOf("a"))
        assertEquals("draft-2", drafts.draftOf("c"))
        drafts.retain(setOf("c"))
        assertEquals("the send still decodes its thumbnails", emptyList<String>(), retired)
        drafts.endSend(send)
        assertEquals(listOf("draft-1"), retired)
        assertEquals("draft-2", drafts.draftOf("d"))
    }

    /**
     * A send that leaves its picks staged, as a rejected one does, keeps them on their draft, while new picks join the
     * next draft. Each draft retires once its picks leave and its sends end.
     */
    @Test
    fun picksLeftStagedKeepTheirDraftUntilTheyLeave() {
        val drafts = drafts()
        drafts.retain(setOf("a"))
        drafts.endSend(drafts.beginSend(listOf("a")))
        assertEquals("a staged pick holds its draft", emptyList<String>(), retired)
        assertEquals("draft-1", drafts.draftOf("a"))
        assertEquals("draft-2", drafts.draftOf("b"))
        val again = drafts.beginSend(listOf("a", "b"))
        assertEquals(setOf("draft-1", "draft-2"), again)
        drafts.retain(emptySet())
        drafts.endSend(again)
        assertEquals(setOf("draft-1", "draft-2"), retired.toSet())
        assertEquals("draft-3", drafts.draftOf("c"))
    }

    /** A document sent before and picked again later decodes in the current draft, never the one it was sent from. */
    @Test
    fun aPickSentBeforeJoinsTheCurrentDraftWhenPickedAgain() {
        val drafts = drafts()
        drafts.retain(setOf(DOCUMENT))
        val send = drafts.beginSend(listOf(DOCUMENT))
        drafts.retain(emptySet())
        drafts.endSend(send)
        assertEquals(listOf("draft-1"), retired)
        drafts.retain(setOf(DOCUMENT))
        assertEquals("draft-2", drafts.draftOf(DOCUMENT))
    }

    /** A pick sent before it was ever recorded in the composer keeps its sent draft when it is recorded late. */
    @Test
    fun aSentPickRecordedLateKeepsItsSentDraft() {
        val drafts = drafts()
        drafts.draftOf("a")
        drafts.beginSend(listOf("a"))
        drafts.retain(setOf("a"))
        assertEquals("draft-1", drafts.draftOf("a"))
        assertEquals("draft-2", drafts.draftOf("b"))
    }

    /**
     * A send resumed after a restart puts picks that joined no draft into a draft of its own, leaving the composer's
     * draft as it is, and that draft retires when the send ends.
     */
    @Test
    fun aResumedSendJoinsADraftOfItsOwn() {
        val drafts = drafts()
        drafts.retain(setOf("composer"))
        assertEquals("draft-1", drafts.draftOf("composer"))
        val send = drafts.beginSend(listOf("resumed"), ownDraft = true)
        assertEquals(setOf("draft-2"), send)
        assertEquals("draft-2", drafts.draftOf("resumed"))
        assertEquals("the composer's draft goes on", "draft-1", drafts.draftOf("composer"))
        drafts.endSend(send)
        assertEquals(listOf("draft-2"), retired)
    }

    private companion object {
        const val DOCUMENT = "content://picker/document"
    }
}
