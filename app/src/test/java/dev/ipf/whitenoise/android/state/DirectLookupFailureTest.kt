package dev.ipf.whitenoise.android.state

import dev.ipf.marmotkit.MarmotKitException
import org.junit.Assert.assertEquals
import org.junit.Test

/** MDK's documented not-ready state is "cannot tell", never "no DM": a miss would create a duplicate. */
class DirectLookupFailureTest {
    /** The peer-index backfill still running is unavailable, so no DM gets created. */
    @Test
    fun indexNotReadyIsUnavailable() {
        val notReady = MarmotKitException.DirectConversationIndexNotReady()
        assertEquals(DirectLookup.Unavailable, directLookupFailure(notReady))
    }

    /** Any other engine error leaves the decision to the retained rows, as before. */
    @Test
    fun otherFailuresAreAMiss() {
        assertEquals(DirectLookup.None, directLookupFailure(MarmotKitException.UnknownAccount("x")))
        assertEquals(DirectLookup.None, directLookupFailure(IllegalStateException("plain")))
    }
}
