package dev.ipf.whitenoise.android.ui.conversation.media

import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.Locale

class AttachmentProgressSizeTest {
    /** Byte counters choose familiar units at exact binary boundaries, without rounding up progress. */
    @Test
    fun readableUnitsCoverSmallFilesAndLargeTransfers() {
        mapOf(
            0uL to "0 B",
            1023uL to "1,023 B",
            1024uL to "1.0 KB",
            1536uL to "1.5 KB",
            1048576uL to "1.0 MB",
            1073741824uL to "1.0 GB",
            1099511627776uL to "1.0 TB",
            1048575uL to "1,023.9 KB",
        ).forEach { (bytes, expected) ->
            assertEquals(expected, formatAttachmentProgressSize(bytes, Locale.US))
        }
    }

    /** The decimal separator follows the UI locale rather than forcing English punctuation. */
    @Test
    fun unitsKeepLocalizedNumbers() {
        assertEquals("1,5 MB", formatAttachmentProgressSize(1572864uL, Locale.GERMANY))
    }

    /** Native unsigned sizes above Long.MAX_VALUE remain positive and do not overflow. */
    @Test
    fun fullUnsignedByteRangeIsRepresentable() {
        assertEquals("15.9 EB", formatAttachmentProgressSize(ULong.MAX_VALUE, Locale.US))
    }
}
