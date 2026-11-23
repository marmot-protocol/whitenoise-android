package dev.ipf.whitenoise.android.state

import dev.ipf.marmotkit.TimelineThreadRefFfi
import dev.ipf.whitenoise.android.core.ChatListMessageSearch
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Engine rows reach search through [searchableTimelineRecord], which must carry MDK's `thread` placement. */
class MessageBodySearchThreadTest {
    /** A kind-1111 row MDK placed under a parent is searchable like chat; the same row without `thread` is not. */
    @Test
    fun aProjectedThreadMessageIsSearchableOnlyWithItsThread() {
        val app = timelineAppMessage("t1").copy(kind = 1111uL, plaintext = "marmot thread reply")
        val projected = requireNotNull(projectedTimelineMessage(app).projected)
        val placed = projected.copy(thread = TimelineThreadRefFfi(rootMessageIdHex = "root", parentMessageIdHex = "p"))

        assertTrue(ChatListMessageSearch.isEligibleMatch(searchableTimelineRecord(placed), "marmot"))
        assertFalse(ChatListMessageSearch.isEligibleMatch(searchableTimelineRecord(projected), "marmot"))
    }
}
