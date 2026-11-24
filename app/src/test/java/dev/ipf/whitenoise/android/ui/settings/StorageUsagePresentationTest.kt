package dev.ipf.whitenoise.android.ui.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** File names made safe for a storage row, and taps on the usage bar mapped to its segments. */
class StorageUsagePresentationTest {
    /** A plain name is shown as sent. */
    @Test fun plainNameIsKept() = assertEquals("Q3 roadmap.pdf", storageDisplayName("Q3 roadmap.pdf"))

    /** Only the last path segment shows, so a name cannot pose as a folder. */
    @Test fun pathIsDropped() {
        assertEquals("photo.jpg", storageDisplayName("../../DCIM/photo.jpg"))
        assertEquals("report.docx", storageDisplayName("C:\\Users\\a\\report.docx"))
    }

    /** Bidirectional overrides and other invisible characters cannot disguise the extension. */
    @Test fun invisibleAndOverrideCharactersAreStripped() {
        assertEquals("invoicefdp.exe", storageDisplayName("invoice\u202Efdp.exe"))
        assertEquals("a b.txt", storageDisplayName("a\u200B\nb.txt"))
        assertEquals("tag.pdf", storageDisplayName("tag\uDB40\uDC20.pdf"))
    }

    /** Zero-width joiners stay, since emoji sequences and Persian or Indic names shape with them. */
    @Test fun joinersAreKept() {
        val emoji = "\uD83D\uDC69\u200D\uD83D\uDCBB notes.pdf"
        val persian = "\u0645\u06CC\u200C\u062E\u0648\u0627\u0647\u0645.pdf"
        assertEquals(emoji, storageDisplayName(emoji))
        assertEquals(persian, storageDisplayName(persian))
        assertNull(storageDisplayName("\u200D\u200C"))
    }

    /** A stack of combining marks is capped as in the chat, so a name cannot draw a tall glyph tower over the row. */
    @Test fun combiningMarkStacksAreCapped() {
        assertEquals("a" + "\u0301".repeat(4) + ".pdf", storageDisplayName("a" + "\u0301".repeat(40) + ".pdf"))
        val filled = "a" + ("\u0301".repeat(4) + "\u3164").repeat(10) + ".pdf"
        assertEquals("a" + "\u0301".repeat(4) + ".pdf", storageDisplayName(filled))
    }

    /** Nothing readable left falls back to the attachment's kind. */
    @Test fun blankNamesAreNull() {
        assertNull(storageDisplayName(""))
        assertNull(storageDisplayName("   "))
        assertNull(storageDisplayName("\u202E\u200B"))
        assertNull(storageDisplayName(".."))
        assertNull(storageDisplayName("folder/"))
        assertNull(storageDisplayName("\uDB40\uDC20\uDB40\uDC41"))
        assertNull(storageDisplayName("\u034F"))
        assertNull(storageDisplayName("\u3164 \uFE0F"))
        assertNull(storageDisplayName("\u0301\u0301"))
        assertNull(storageDisplayName("\u2800"))
        assertNull(storageDisplayName("\u034F\u2800"))
    }

    /** An over-long name keeps its start and its extension. */
    @Test fun longNameKeepsBothEnds() {
        val shown = storageDisplayName("a".repeat(300) + ".mp4")!!
        assertEquals(121, shown.length)
        assertEquals(true, shown.startsWith("aaaa") && shown.endsWith(".mp4") && "…" in shown)
    }

    /** A tap maps to the segment drawn under it, gaps included, on a 300 px bar with 2 px gaps. */
    @Test fun tapPicksSegmentDrawnUnderIt() {
        val weights = listOf(0.8f, 0.1f, 0.1f)
        // Drawn: 0..236.8, 238.8..268.4, 270.4..300.
        assertEquals(0, storageSegmentAt(10f, 300f, 2f, weights))
        assertEquals(1, storageSegmentAt(239.5f, 300f, 2f, weights))
        assertEquals(0, storageSegmentAt(237.5f, 300f, 2f, weights))
        assertEquals(2, storageSegmentAt(290f, 300f, 2f, weights))
        assertEquals(2, storageSegmentAt(450f, 300f, 2f, weights))
    }

    /** A sliver too thin to hit also claims taps just beside it, and a wide segment keeps taps further away. */
    @Test fun sliverClaimsNearbyTaps() {
        val weights = listOf(0.8f, 0.02f, 0.18f)
        // Drawn: 0..236.8, 238.8..244.7, 246.7..300; the sliver's centre is about 241.8, reach 12.
        assertEquals(1, storageSegmentAt(241f, 300f, 2f, weights))
        assertEquals(1, storageSegmentAt(232f, 300f, 2f, weights))
        assertEquals(1, storageSegmentAt(252f, 300f, 2f, weights))
        assertEquals(0, storageSegmentAt(200f, 300f, 2f, weights))
        assertEquals(2, storageSegmentAt(280f, 300f, 2f, weights))
    }

    /** No segments, no hit. */
    @Test fun emptyBarHasNoSegment() = assertNull(storageSegmentAt(5f, 300f, 2f, emptyList()))
}
