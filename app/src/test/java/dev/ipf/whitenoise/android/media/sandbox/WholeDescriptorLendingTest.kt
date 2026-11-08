package dev.ipf.whitenoise.android.media.sandbox

import android.system.OsConstants
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class WholeDescriptorLendingTest {
    @Test
    fun onlyAWholeReadOnlyDescriptorIsLentToAWorker() {
        assertTrue(canLendWholeDescriptor(0L, 100L, 100L, OsConstants.O_RDONLY))
    }

    /** A provider may return a writable descriptor; the worker then receives a staged read-only copy. */
    @Test
    fun writableUnknownOrPartialDescriptorsAreStaged() {
        assertFalse(canLendWholeDescriptor(0L, 100L, 100L, OsConstants.O_RDWR))
        assertFalse(canLendWholeDescriptor(0L, 100L, 100L, OsConstants.O_WRONLY))
        assertFalse(canLendWholeDescriptor(0L, 100L, 100L, null))
        assertFalse(canLendWholeDescriptor(10L, 90L, 100L, OsConstants.O_RDONLY))
        assertFalse(canLendWholeDescriptor(0L, 90L, 100L, OsConstants.O_RDONLY))
    }
}
