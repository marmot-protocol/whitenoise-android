package dev.ipf.whitenoise.android.ui.conversation

import dev.ipf.whitenoise.android.media.MediaTrustDomain
import dev.ipf.whitenoise.android.media.OutgoingDraftRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

/**
 * A collection resumed after a restart drafts its items under its own picks (#3373), so it never shares or ends the
 * composer's draft, even when the composer holds the same document.
 */
class ResumedCollectionDraftTest {
    private val retired = mutableListOf<MediaTrustDomain>()
    private val registry = OutgoingDraftRegistry { retired += it }

    /**
     * The same document staged in a populated composer and in a resumed collection, each beside another pick, decodes
     * in two separate domains, and the composer's draft goes on unchanged for its later picks.
     */
    @Test
    fun aResumedCollectionNeverSharesOrEndsTheComposersDraft() {
        registry.retain(ACCOUNT, GROUP, setOf(DOCUMENT, "composer-photo"))
        val composer = registry.domain(ACCOUNT, GROUP, DOCUMENT)
        val run = CollectionRun("operation", ACCOUNT, GROUP, sessionEpoch = 0L, caption = null, pickScope = "operation")
        val picks = listOf(run.pickKey(DOCUMENT), run.pickKey("resumed-photo"))
        val send = registry.beginSend(ACCOUNT, GROUP, picks, ownDraft = true)
        val resumed = registry.domain(ACCOUNT, GROUP, run.pickKey(DOCUMENT))
        assertNotEquals(composer, resumed)
        assertEquals(resumed, registry.domain(ACCOUNT, GROUP, run.pickKey("resumed-photo")))
        assertEquals(composer, registry.domain(ACCOUNT, GROUP, "composer-photo"))
        assertEquals("the composer's draft goes on", composer, registry.domain(ACCOUNT, GROUP, "composer-later"))
        registry.endSend(send)
        assertEquals(listOf(resumed), retired)
    }

    /** A collection sent from the composer keeps the composer's picks, so its items stay in the draft they joined. */
    @Test
    fun aComposerCollectionKeepsItsComposerPicks() {
        val run = CollectionRun("operation", ACCOUNT, GROUP, sessionEpoch = 0L, caption = null)
        assertEquals(DOCUMENT, run.pickKey(DOCUMENT))
        val resumed = CollectionRun("operation", ACCOUNT, GROUP, 0L, null, pickScope = "operation")
        assertNotEquals(DOCUMENT, resumed.pickKey(DOCUMENT))
    }

    private companion object {
        const val ACCOUNT = "account"
        const val GROUP = "group"
        const val DOCUMENT = "content://picker/document"
    }
}
