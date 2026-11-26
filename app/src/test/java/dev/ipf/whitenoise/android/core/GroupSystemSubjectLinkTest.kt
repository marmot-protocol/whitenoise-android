package dev.ipf.whitenoise.android.core

import dev.ipf.marmotkit.AppMessageRecordFfi
import dev.ipf.marmotkit.GroupSystemEventFfi
import dev.ipf.marmotkit.GroupSystemEventProvenanceFfi
import dev.ipf.marmotkit.MarkdownDocumentFfi
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Pins the profile links on member-added and member-removed rows (#2957, #4209): each targets an
 * authenticated id, sits exactly on that person's name in any word order, and never appears for untrusted rows.
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

    /** A removal links only the removed member, even when the actor shares their display name. */
    @Test
    fun removalLinksOnlyTheSubjectSlot() {
        val linked = linkedSummary(requireNotNull(event("member_removed")), actorName = "Sam", subjectName = "Sam")

        assertEquals("Sam removed Sam", linked.text)
        assertNull(linked.link(GroupSystemLinkRole.ACTOR))
        assertEquals(12..14, linked.link(GroupSystemLinkRole.SUBJECT)?.range)
        assertEquals(SUBJECT, linked.link(GroupSystemLinkRole.SUBJECT)?.accountIdHex)
    }

    /** An addition links the actor and the subject separately, each to their own authenticated id (#4209). */
    @Test
    fun additionLinksActorAndSubject() {
        val linked = linkedSummary(requireNotNull(event("member_added")))

        assertEquals("Alice added Bob", linked.text)
        assertEquals(
            listOf(
                GroupSystemProfileLink(GroupSystemLinkRole.ACTOR, 0..4, ACTOR),
                GroupSystemProfileLink(GroupSystemLinkRole.SUBJECT, 12..14, SUBJECT),
            ),
            linked.links,
        )
    }

    /** Identical display names still route by slot: the first "Sam" opens the actor, the second the subject. */
    @Test
    fun identicalNamesRouteBySlot() {
        val linked = linkedSummary(requireNotNull(event("member_added")), actorName = "Sam", subjectName = "Sam")

        assertEquals("Sam added Sam", linked.text)
        assertEquals(0..2, linked.link(GroupSystemLinkRole.ACTOR)?.range)
        assertEquals(ACTOR, linked.link(GroupSystemLinkRole.ACTOR)?.accountIdHex)
        assertEquals(10..12, linked.link(GroupSystemLinkRole.SUBJECT)?.range)
        assertEquals(SUBJECT, linked.link(GroupSystemLinkRole.SUBJECT)?.accountIdHex)
    }

    /** A locale that puts the subject first still links each span to the right person, without guessing from names. */
    @Test
    fun subjectFirstWordOrderLinksEachSlot() {
        val subjectFirst = GroupSystemCopy.Default.copy(memberAddedFormat = "%2\$s wurde von %1\$s hinzugefügt")
        val linked = linkedSummary(requireNotNull(event("member_added")), copy = subjectFirst)

        assertEquals("Bob wurde von Alice hinzugefügt", linked.text)
        assertEquals(
            listOf(
                GroupSystemProfileLink(GroupSystemLinkRole.SUBJECT, 0..2, SUBJECT),
                GroupSystemProfileLink(GroupSystemLinkRole.ACTOR, 14..18, ACTOR),
            ),
            linked.links,
        )
    }

    /** "You added Bob" links only Bob; "Alice added you" links only Alice. */
    @Test
    fun selfRendersAsPlainYou() {
        val event = requireNotNull(event("member_added"))
        val readerActed = linkedSummary(event, self = ACTOR)
        val readerAdded = linkedSummary(event, self = SUBJECT)

        assertEquals("You added Bob", readerActed.text)
        assertEquals(listOf(GroupSystemLinkRole.SUBJECT), readerActed.links.map { it.role })
        assertEquals("Alice added you", readerAdded.text)
        assertEquals(listOf(GroupSystemProfileLink(GroupSystemLinkRole.ACTOR, 0..4, ACTOR)), readerAdded.links)
    }

    /** An actor with no id or no name renders in the passive voice without an actor link. */
    @Test
    fun anonymousActorStaysUnlinked() {
        val event = requireNotNull(event("member_added"))
        val noId = linkedSummary(event, actor = null)
        val noName = linkedSummary(event, actorName = null)

        assertEquals(listOf(GroupSystemLinkRole.SUBJECT), noId.links.map { it.role })
        assertEquals("Bob was added", noName.text)
        assertEquals(listOf(GroupSystemLinkRole.SUBJECT), noName.links.map { it.role })
    }

    /** Untrusted and non-membership rows never link the actor. */
    @Test
    fun actorLinkNeedsAnAuthenticatedAddition() {
        val memberAuthored = event("member_added", GroupSystemEventProvenanceFfi.MEMBER_AUTHORED)
        assertNull(GroupSystemSubjectLink.actorTarget(memberAuthored, ACTOR, SELF))
        assertNull(GroupSystemSubjectLink.actorTarget(event("member_removed"), ACTOR, SELF))
        assertNull(GroupSystemSubjectLink.actorTarget(event("admin_added"), ACTOR, SELF))
        assertNull(GroupSystemSubjectLink.actorTarget(event("member_added"), " ", SELF))
        assertNull(GroupSystemSubjectLink.actorTarget(event("member_added"), ACTOR.uppercase(), ACTOR))
        assertNull(GroupSystemSubjectLink.actorTarget(null, ACTOR, SELF))
        assertEquals(ACTOR, GroupSystemSubjectLink.actorTarget(event("member_added"), ACTOR, SELF))
    }

    /** A rendered form with no slot, or a slot rendered twice, leaves only that slot unlinked. */
    @Test
    fun ambiguousRenderingStaysUnlinked() {
        val event = requireNotNull(event("member_added"))
        val noSlot = GroupSystemSubjectLink.summary(event, SELF, ACTOR, "Alice", "Bob") { _, _ -> "Group updated" }
        val doubledSubject =
            GroupSystemSubjectLink.summary(event, SELF, ACTOR, "Alice", "Bob") { actor, subject -> "$actor: $subject and $subject" }

        assertEquals(emptyList<GroupSystemProfileLink>(), noSlot.links)
        assertEquals("Alice: Bob and Bob", doubledSubject.text)
        assertEquals(listOf(GroupSystemProfileLink(GroupSystemLinkRole.ACTOR, 0..4, ACTOR)), doubledSubject.links)
    }

    /** A tap opens the profile only while the account that rendered the row is still active. */
    @Test
    fun staleAccountTapsAreDropped() {
        assertEquals(SUBJECT, GroupSystemSubjectLink.openTarget(SUBJECT, "personal", "personal"))
        assertNull(GroupSystemSubjectLink.openTarget(SUBJECT, "personal", "work"))
        assertNull(GroupSystemSubjectLink.openTarget(ACTOR, "personal", "work"))
        assertNull(GroupSystemSubjectLink.openTarget(SUBJECT, null, null))
    }

    /** An authenticated timeline removal keeps the removed subject's id, which the link needs after they leave. */
    @Test
    fun timelineRemovalKeepsTheRemovedSubjectId() {
        val resolved = GroupSystemEvents.resolve(systemRecord(), ffi("member_removed"))

        assertEquals(SUBJECT, GroupSystemSubjectLink.target(resolved, SELF))
    }

    /** Renders [event] the way the row does, linking whichever slots qualify. */
    private fun linkedSummary(
        event: GroupSystemEvent,
        self: String = SELF,
        actor: String? = ACTOR,
        actorName: String? = "Alice",
        subjectName: String? = "Bob",
        copy: GroupSystemCopy = GroupSystemCopy.Default,
    ): GroupSystemLinkedSummary =
        GroupSystemSubjectLink.summary(event, self, actor, actorName, subjectName) { shownActor, shownSubject ->
            GroupSystemEvents.summary(
                event = event,
                actorName = shownActor,
                subjectName = shownSubject,
                actorIsSelf = GroupSystemEvents.isSelf(self, actor),
                subjectIsSelf = GroupSystemEvents.isSelf(self, event.subject),
                copy = copy,
            )
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
            invalidated = false,
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
            sticker = null,
        )

    private companion object {
        val ACTOR = "a1".repeat(32)
        val SUBJECT = "b2".repeat(32)
        val SELF = "c3".repeat(32)
        const val SPOOFED_PAYLOAD =
            """{"v":1,"system_type":"member_removed","data":{"actor":"x","subject":"b2"}}"""
    }
}

/** The link for [role], or null when that participant's name is plain text. */
private fun GroupSystemLinkedSummary.link(role: GroupSystemLinkRole): GroupSystemProfileLink? = links.firstOrNull { it.role == role }
