package dev.ipf.whitenoise.android.state

import dev.ipf.marmotkit.DeviceLinkSnapshotFfi
import dev.ipf.marmotkit.LinkChatProgressFfi
import dev.ipf.marmotkit.NewDeviceLinkStateFfi
import org.junit.Assert.assertEquals
import org.junit.Test

/** When a newly linked device's chat list says chats are on their way before MDK has a count. */
class AwaitingChatCountTest {
    private val count = LinkChatProgressFfi("this", true, 15u, 6u)

    /** A link seen turning Linked without a count starts the wait at that moment. */
    @Test
    fun aLinkSeenCompletingAwaitsItsFirstCount() {
        val joining = mapOf("a" to snapshot(NewDeviceLinkStateFfi.Joining))
        val awaiting = awaitingChatCount(joining, linked(), emptyMap(), 40L)
        assertEquals(mapOf("a" to 40L), awaiting)
        assertEquals(mapOf("a" to 40L), awaitingChatCount(linked(), linked(), awaiting, 90L))
    }

    /** A link already Linked when first read is an old one, so nothing waits for it. */
    @Test
    fun aLinkFirstReadAsLinkedIsNotNew() {
        assertEquals(emptyMap<String, Long>(), awaitingChatCount(emptyMap(), linked(), emptyMap(), 40L))
    }

    /** The first count ends the wait for good, even if MDK drops the count later. */
    @Test
    fun theFirstCountEndsTheWait() {
        val counted = mapOf("a" to snapshot(NewDeviceLinkStateFfi.Linked, listOf(count)))
        val awaiting = awaitingChatCount(linked(), counted, mapOf("a" to 40L), 50L)
        assertEquals(emptyMap<String, Long>(), awaiting)
        assertEquals(emptyMap<String, Long>(), awaitingChatCount(counted, linked(), awaiting, 60L))
    }

    /** One profile [state] without counts unless [progress] is given. */
    private fun snapshot(
        state: NewDeviceLinkStateFfi,
        progress: List<LinkChatProgressFfi> = emptyList(),
    ) = DeviceLinkSnapshotFfi(state, emptyList(), emptyList(), chatProgress = progress)

    /** Profile "a" linked, with no count yet. */
    private fun linked() = mapOf("a" to snapshot(NewDeviceLinkStateFfi.Linked))
}
