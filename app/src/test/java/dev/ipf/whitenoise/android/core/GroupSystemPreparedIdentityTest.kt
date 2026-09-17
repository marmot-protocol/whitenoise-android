package dev.ipf.whitenoise.android.core

import dev.ipf.marmotkit.GroupSystemEventFfi
import dev.ipf.marmotkit.GroupSystemEventProvenanceFfi
import dev.ipf.marmotkit.MarkdownDocumentFfi
import dev.ipf.marmotkit.TimelineMessageRecordFfi
import dev.ipf.marmotkit.TimelineReactionSummaryFfi
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * MarmotKit 0.10.1 states whether a system row came from authenticated group state and prepares actor and
 * subject labels. These pin both: a member-authored row never claims authority, and a prepared label is
 * used only when the reader has no real local name.
 */
class GroupSystemPreparedIdentityTest {
    /** An authenticated projection keeps its identities and carries MarmotKit's prepared labels. */
    @Test
    fun authenticatedProjectionKeepsIdentitiesAndPreparedLabels() {
        val authenticated = event(GroupSystemEventProvenanceFfi.AUTHENTICATED_GROUP_STATE)
        val event = GroupSystemEvents.resolve(systemRecord(), authenticated)

        assertTrue(event?.fromAuthenticatedStateProjection == true)
        assertEquals(ACTOR, event?.actor)
        assertEquals(SUBJECT, event?.subject)
        assertEquals("Alice", event?.actorDisplayName)
        assertEquals("Bob", event?.subjectDisplayName)
    }

    /**
     * A member-authored row that merely schema-parsed claims no authority, and its actor and subject are
     * dropped so a summary cannot name whoever the payload says acted (#985, #1318).
     */
    @Test
    fun memberAuthoredRowClaimsNoAuthorityAndNamesNobody() {
        val event = GroupSystemEvents.resolve(systemRecord(), event(GroupSystemEventProvenanceFfi.MEMBER_AUTHORED))

        assertFalse(event?.fromAuthenticatedStateProjection == true)
        assertNull(event?.actor)
        assertNull(event?.subject)
        assertNull(event?.actorDisplayName)
        assertNull(event?.subjectDisplayName)
        assertEquals(
            GroupSystemCopy.Default.fallback,
            GroupSystemEvents.summary(requireNotNull(event), actorName = "Alice", subjectName = "Bob"),
        )
    }

    /** A real local name wins: the reader's own nickname is what they recognise. */
    @Test
    fun localNameWinsOverThePreparedLabel() {
        assertEquals("Mum", GroupSystemEvents.preferredName("Mum", "Alice"))
    }

    /** A local lookup that only produced an identity yields to MarmotKit's prepared label. */
    @Test
    fun preparedLabelReplacesAnIdentityFallback() {
        assertEquals("Alice", GroupSystemEvents.preferredName("npub1qy352euf40x77qfrg4ncn27daufwuj22r4ttmx", "Alice"))
        assertEquals("Alice", GroupSystemEvents.preferredName("a".repeat(64), "Alice"))
    }

    /** With no prepared label the local fallback still shows, and with neither there is no name. */
    @Test
    fun fallsBackWhenNothingBetterExists() {
        val npub = "npub1qy352euf40x77qfrg4ncn27daufwuj22r4ttmx"
        assertEquals(npub, GroupSystemEvents.preferredName(npub, null))
        assertEquals("Alice", GroupSystemEvents.preferredName(null, "Alice"))
        assertEquals("Alice", GroupSystemEvents.preferredName("  ", "Alice"))
        assertNull(GroupSystemEvents.preferredName(null, null))
    }

    /** The chat-list preview takes the engine's projected event over the row's plaintext. */
    @Test
    fun chatListPreviewPrefersTheProjectedEvent() {
        val preview =
            GroupSystemEvents.previewText(
                plaintext = "{}",
                structured = event(GroupSystemEventProvenanceFfi.AUTHENTICATED_GROUP_STATE),
            )

        assertTrue(preview, preview.isNotBlank())
        assertFalse(preview, preview.contains("{"))
    }

    private fun event(provenance: GroupSystemEventProvenanceFfi) =
        GroupSystemEventFfi(
            provenance = provenance,
            actorDisplayName = "Alice",
            subjectDisplayName = "Bob",
            systemType = "member_added",
            text = "Alice added Bob",
            actorAccountIdHex = ACTOR,
            subjectAccountIdHex = SUBJECT,
            name = null,
            oldName = null,
            oldRetentionSeconds = null,
            newRetentionSeconds = null,
        )

    private fun systemRecord() =
        TimelineMessageRecordFfi(
            messageIdHex = "11".repeat(32),
            sourceMessageIdHex = null,
            direction = "system",
            groupIdHex = "22".repeat(32),
            sender = ACTOR,
            plaintext = """{"type":"member_added"}""",
            contentTokens =
                MarkdownDocumentFfi(truncated = false, blankLinesBefore = byteArrayOf(), blocks = emptyList()),
            kind = 1210uL,
            tags = emptyList(),
            timelineAt = 1uL,
            receivedAt = 1uL,
            replyToMessageIdHex = null,
            replyPreview = null,
            mediaJson = null,
            media = emptyList(),
            agentTextStreamJson = null,
            groupSystem = null,
            hasReports = false,
            edit = null,
            reactions = TimelineReactionSummaryFfi(byEmoji = emptyList(), userReactions = emptyList()),
            deleted = false,
            deletedByMessageIdHex = null,
            invalidationStatus = null,
            sourceEpoch = null,
            retentionSeconds = null,
            retentionExpiresAt = null,
        )

    private companion object {
        val ACTOR = "aa".repeat(32)
        val SUBJECT = "bb".repeat(32)
    }
}
