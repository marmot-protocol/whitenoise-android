package dev.ipf.darkmatter.state

import kotlinx.coroutines.Job
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class InFlightMediaUploadsTest {
    @Test
    fun cancelAllCancelsTrackedJobsAndClearsRegistry() {
        val registry = InFlightMediaUploads()
        val first = Job()
        val second = Job()

        registry.track("acct-a\u0000group-1", "msg:first", first)
        registry.track("acct-a\u0000group-1", "msg:second", second)

        assertEquals(2, registry.cancelAll())
        assertTrue(first.isCancelled)
        assertTrue(second.isCancelled)
        assertEquals(0, registry.cancelAll())
    }

    @Test
    fun cancelAllCancelsEachCoroutineOnlyOnceWhenSeveralUploadsShareAJob() {
        val registry = InFlightMediaUploads()
        val batchJob = Job()

        registry.track("acct-a\u0000group-1", "msg:first", batchJob)
        registry.track("acct-a\u0000group-1", "msg:second", batchJob)

        assertEquals(1, registry.cancelAll())
        assertTrue(batchJob.isCancelled)
    }

    @Test
    fun completedStaleJobDoesNotRemoveReplacementForSameUploadKey() {
        val registry = InFlightMediaUploads()
        val stale = Job()
        val replacement = Job()

        registry.track("acct-a\u0000group-1", "msg:slot", stale)
        registry.track("acct-a\u0000group-1", "msg:slot", replacement)
        stale.complete()

        assertEquals(1, registry.cancelAll())
        assertFalse(stale.isCancelled)
        assertTrue(replacement.isCancelled)
    }
}
