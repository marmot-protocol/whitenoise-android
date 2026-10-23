package dev.ipf.whitenoise.android.ui.conversation.nostr

import dev.ipf.whitenoise.android.core.NostrEventReference
import dev.ipf.whitenoise.android.core.NostrEventReferenceDecoder
import dev.ipf.whitenoise.android.core.nostr.NostrEvent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

/** The conversation's first-frame card snapshots are filled from MDK's cache before rows show (#2238). */
class NostrEventCardSnapshotsTest {
    /** The test encoder produces references the production decoder reads back to the same event. */
    @Test
    fun noteFixturesDecodeToTheirEventIds() {
        val id = eventId(7)
        assertEquals(NostrEventReference.Event(id), NostrEventReferenceDecoder.eventReference(noteBech32(id)))
    }

    /** Warming reads the newest messages' cards in batches of sixteen and keeps every cached hit. */
    @Test
    fun warmReadsNewestCardsInBatchesAndKeepsHits() =
        runTest {
            val records =
                (0 until 20).map { index ->
                    val note = noteBech32(eventId(index))
                    eventCardTimelineRecord(
                        messageIdHex = "m$index",
                        plaintext = "nostr:$note",
                        timelineAt = index.toULong(),
                        contentTokens = eventCardDocument(note),
                    )
                }
            val batches = mutableListOf<List<String>>()
            val snapshots = NostrEventCardSnapshots()

            val read =
                snapshots.warm(records) { references ->
                    batches += references
                    references.map { loaded(it) }
                }

            assertEquals(20, read)
            assertEquals(listOf(16, 4), batches.map { it.size })
            assertEquals(noteBech32(eventId(19)), batches.first().first())
            records.indices.forEach { index ->
                val stableId = NostrEventReference.Event(eventId(index)).stableId
                assertEquals(loaded(noteBech32(eventId(index))), snapshots[stableId])
            }
        }

    /** Only parsed messages name cards, so an unhydrated message is left to hydration, and deleted rows are skipped. */
    @Test
    fun warmReadsOnlyParsedMessagesAndSkipsDeletedOnes() =
        runTest {
            val visible = noteBech32(eventId(1))
            val unhydrated = noteBech32(eventId(2))
            val deleted = noteBech32(eventId(3))
            val records =
                listOf(
                    eventCardTimelineRecord("visible", "nostr:$visible", contentTokens = eventCardDocument(visible)),
                    eventCardTimelineRecord("unhydrated", "nostr:$unhydrated"),
                    eventCardTimelineRecord(
                        "deleted",
                        "nostr:$deleted",
                        contentTokens = eventCardDocument(deleted),
                        deleted = true,
                    ),
                    eventCardTimelineRecord("plain", "no card here"),
                )
            val read = mutableListOf<String>()
            val snapshots = NostrEventCardSnapshots()

            snapshots.warm(records) { references ->
                read += references
                references.map { loaded(it) }
            }

            assertEquals(listOf(visible), read)
        }

    /** Misses, failures and in-flight states never seed a frame, and a resolver's newer state is never overwritten. */
    @Test
    fun warmKeepsOnlySettledHitsAndNeverOverwritesNewerState() =
        runTest {
            val known = noteBech32(eventId(1))
            val missing = noteBech32(eventId(2))
            val failed = noteBech32(eventId(3))
            val snapshots = NostrEventCardSnapshots()
            val newer = loaded("newer")
            snapshots.put(NostrEventReference.Event(eventId(1)).stableId, newer)
            snapshots.put(NostrEventReference.Event(eventId(3)).stableId, NostrEventCardState.Failed)
            val record =
                eventCardTimelineRecord(
                    messageIdHex = "m",
                    plaintext = "$known $missing $failed",
                    contentTokens = eventCardDocument(known, missing, failed),
                )

            snapshots.warm(listOf(record)) { references ->
                references.map { reference ->
                    when (reference) {
                        failed -> NostrEventCardState.Failed
                        else -> null
                    }
                }
            }

            assertSame(newer, snapshots[NostrEventReference.Event(eventId(1)).stableId])
            assertNull(snapshots[NostrEventReference.Event(eventId(2)).stableId])
            assertNull(snapshots[NostrEventReference.Event(eventId(3)).stableId])
        }

    /**
     * The default capacity covers every card a full conversation window can name, three per row, so warming the whole
     * window keeps every snapshot it read rather than evicting its own earlier batches.
     */
    @Test
    fun warmingAFullWindowKeepsEverySnapshot() =
        runTest {
            val rows = 200
            val records =
                (0 until rows).map { row ->
                    val notes =
                        (0 until MAX_CARDS_PER_MESSAGE).map { card ->
                            noteBech32(eventId(row * MAX_CARDS_PER_MESSAGE + card))
                        }
                    eventCardTimelineRecord(
                        messageIdHex = "m$row",
                        plaintext = notes.joinToString(" ") { "nostr:$it" },
                        timelineAt = row.toULong(),
                        contentTokens = eventCardDocument(*notes.toTypedArray()),
                    )
                }
            val snapshots = NostrEventCardSnapshots()

            val read = snapshots.warm(records) { references -> references.map { loaded(it) } }

            assertEquals(rows * MAX_CARDS_PER_MESSAGE, read)
            (0 until rows * MAX_CARDS_PER_MESSAGE).forEach { index ->
                val stableId = NostrEventReference.Event(eventId(index)).stableId
                assertEquals("card $index kept", loaded(noteBech32(eventId(index))), snapshots[stableId])
            }
        }

    /** The snapshot keeps at most its capacity, dropping the least recently used entry first. */
    @Test
    fun snapshotsStayBounded() {
        val snapshots = NostrEventCardSnapshots(capacity = 2)
        snapshots.put("one", loaded("one"))
        snapshots.put("two", loaded("two"))
        snapshots["one"]
        snapshots.put("three", loaded("three"))

        assertEquals(loaded("one"), snapshots["one"])
        assertNull(snapshots["two"])
        assertEquals(loaded("three"), snapshots["three"])
    }

    /** A 64-character hex event id derived from [index]. */
    private fun eventId(index: Int): String = index.toString(16).padStart(64, '0')

    /** A loaded card whose summary names [label], so distinct references give distinct states. */
    private fun loaded(label: String): NostrEventCardState.Loaded =
        NostrEventCardState.Loaded(
            NostrEvent(
                id = "a".repeat(64),
                pubkey = "c".repeat(64),
                createdAt = 1,
                kind = 1,
                tags = emptyList(),
                content = label,
                sig = "0".repeat(128),
            ).toCardModel(),
        )
}
