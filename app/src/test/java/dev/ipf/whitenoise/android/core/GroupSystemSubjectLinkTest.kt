package dev.ipf.whitenoise.android.core

import dev.ipf.marmotkit.AppMessageRecordFfi
import dev.ipf.marmotkit.GroupSystemEventFfi
import dev.ipf.marmotkit.GroupSystemEventProvenanceFfi
import dev.ipf.marmotkit.MarkdownDocumentFfi
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Pins the profile link on member-added and member-removed rows (#2957): it targets the authenticated
 * subject id, sits exactly on the subject's name in any word order, and never appears for untrusted rows.
 */
class GroupSystemSubjectLinkTest {
    /** Added and removed rows link to their authenticated subject, including a member who has since left. */
    @Test
    fun authenticatedAddAndRemoveLinkTheSubject() {
        assertEquals(SUBJECT, GroupSystemSubjectLink.target(event("member_added"), SELF))
        assertEquals(SUBJECT, GroupSystemSubjectLink.target(event("member_removed"), SELF))
    }

    /** Untrusted, subjectless, self and unrelated rows carry no subject link. */
    @Test
    fun untrustedSelfAndOtherRowsHaveNoTarget() {
        val memberAuthored = event("member_added", GroupSystemEventProvenanceFfi.MEMBER_AUTHORED)
        assertNull(GroupSystemSubjectLink.target(memberAuthored, SELF))
        assertNull(GroupSystemSubjectLink.target(event("member_added", subject = null), SELF))
        assertNull(GroupSystemSubjectLink.target(event("member_added", subject = " "), SELF))
        assertNull(GroupSystemSubjectLink.target(event("member_removed"), SUBJECT.uppercase()))
        assertNull(GroupSystemSubjectLink.target(event("admin_added"), SELF))
        assertNull(GroupSystemSubjectLink.target(event("member_left"), SELF))
        assertNull(GroupSystemSubjectLink.target(null, SELF))
        assertNull(GroupSystemSubjectLink.target(GroupSystemEvents.parse(SPOOFED_PAYLOAD), SELF))
    }

    /** The link covers the subject's name, not the actor's, even when both share the same display name. */
    @Test
    fun identicalNamesLinkOnlyTheSubjectSlot() {
        val event = requireNotNull(event("member_removed"))
        val linked =
            GroupSystemSubjectLink.summary(event, SELF, "Sam") { subject ->
                GroupSystemEvents.summary(event, actorName = "Sam", subjectName = subject)
            }

        assertEquals("Sam removed Sam", linked.text)
        assertEquals(12..14, linked.subjectRange)
        assertEquals(SUBJECT, linked.subjectAccountIdHex)
    }

    /** A locale that puts the subject first still links the right span, without guessing from the name. */
    @Test
    fun subjectFirstWordOrderLinksTheLeadingName() {
        val event = requireNotNull(event("member_added"))
        val subjectFirst = GroupSystemCopy.Default.copy(memberAddedFormat = "%2\$s wurde von %1\$s hinzugefügt")
        val linked =
            GroupSystemSubjectLink.summary(event, SELF, "Bob") { subject ->
                GroupSystemEvents.summary(event, actorName = "Alice", subjectName = subject, copy = subjectFirst)
            }

        assertEquals("Bob wurde von Alice hinzugefügt", linked.text)
        assertEquals(0..2, linked.subjectRange)
    }

    /** The reader's own add renders "You were added", which has no name to link. */
    @Test
    fun selfSubjectRendersUnlinked() {
        val event = requireNotNull(event("member_added"))
        val linked =
            GroupSystemSubjectLink.summary(event, SUBJECT, "Me") { subject ->
                GroupSystemEvents.summary(event, actorName = null, subjectName = subject, subjectIsSelf = true)
            }

        assertEquals("You were added", linked.text)
        assertNull(linked.subjectRange)
    }

    /** A rendered form with no subject slot, or a name already holding the marker, stays unlinked. */
    @Test
    fun ambiguousRenderingStaysUnlinked() {
        val event = requireNotNull(event("member_added"))
        val noSlot = GroupSystemSubjectLink.summary(event, SELF, "Bob") { "Group updated" }
        val doubled = GroupSystemSubjectLink.summary(event, SELF, "Bob") { subject -> "$subject and $subject" }

        assertNull(noSlot.subjectRange)
        assertNull(doubled.subjectRange)
        assertEquals("Bob and Bob", doubled.text)
    }

    /** A tap opens the profile only while the account that rendered the row is still active. */
    @Test
    fun staleAccountTapsAreDropped() {
        assertEquals(SUBJECT, GroupSystemSubjectLink.openTarget(SUBJECT, "personal", "personal"))
        assertNull(GroupSystemSubjectLink.openTarget(SUBJECT, "personal", "work"))
        assertNull(GroupSystemSubjectLink.openTarget(SUBJECT, null, null))
    }

    /** An authenticated timeline removal keeps the removed subject's id, which the link needs after they leave. */
    @Test
    fun timelineRemovalKeepsTheRemovedSubjectId() {
        val resolved = GroupSystemEvents.resolve(systemRecord(), ffi("member_removed"))

        assertEquals(SUBJECT, GroupSystemSubjectLink.target(resolved, SELF))
    }

    /** Resolves an event the way timeline rows do, from MDK's typed projection. */
    private fun event(
        systemType: String,
        provenance: GroupSystemEventProvenanceFfi = GroupSystemEventProvenanceFfi.AUTHENTICATED_GROUP_STATE,
        subject: String? = SUBJECT,
    ): GroupSystemEvent? = GroupSystemEvents.resolve(systemRecord(), ffi(systemType, provenance, subject))

    /** The typed group-system event MDK projects for [systemType]. */
    private fun ffi(
        systemType: String,
        provenance: GroupSystemEventProvenanceFfi = GroupSystemEventProvenanceFfi.AUTHENTICATED_GROUP_STATE,
        subject: String? = SUBJECT,
    ) = GroupSystemEventFfi(
        provenance = provenance,
        actorDisplayName = "Alice",
        subjectDisplayName = "Bob",
        systemType = systemType,
        text = "",
        actorAccountIdHex = ACTOR,
        subjectAccountIdHex = subject,
        name = null,
        oldName = null,
        oldRetentionSeconds = null,
        newRetentionSeconds = null,
    )

    /** An engine-synthesized kind-1210 record. */
    private fun systemRecord() =
        AppMessageRecordFfi(
            messageIdHex = "11".repeat(32),
            direction = "system",
            groupIdHex = "22".repeat(32),
            sender = ACTOR,
            plaintext = "",
            contentTokens =
                MarkdownDocumentFfi(truncated = false, blankLinesBefore = byteArrayOf(), blocks = emptyList()),
            kind = 1210uL,
            tags = emptyList(),
            sourceEpoch = null,
            retentionSeconds = null,
            retentionExpiresAt = null,
            recordedAt = 1uL,
            receivedAt = 1uL,
        )

    private companion object {
        val ACTOR = "a1".repeat(32)
        val SUBJECT = "b2".repeat(32)
        val SELF = "c3".repeat(32)
        const val SPOOFED_PAYLOAD =
            """{"v":1,"system_type":"member_removed","data":{"actor":"x","subject":"b2"}}"""
    }
}
