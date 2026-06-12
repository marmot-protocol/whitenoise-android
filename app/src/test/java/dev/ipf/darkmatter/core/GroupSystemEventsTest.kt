package dev.ipf.darkmatter.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class GroupSystemEventsTest {
    // Verbatim wire payload from a peer client's avatar change.
    private val avatarChangedJson =
        """{"v":1,"system_type":"group_avatar_changed","text":"Group avatar changed",""" +
            """"data":{"actor":"d946d284e7dd95185524124bfac2d127941b244508a05e0efd11fd224db136a0"}}"""

    @Test
    fun parsesAvatarChangedPayload() {
        val event = GroupSystemEvents.parse(avatarChangedJson)

        assertEquals(
            GroupSystemEvent(
                systemType = "group_avatar_changed",
                text = "Group avatar changed",
                actor = "d946d284e7dd95185524124bfac2d127941b244508a05e0efd11fd224db136a0",
                subject = null,
                name = null,
            ),
            event,
        )
    }

    @Test
    fun parseRejectsNonSystemContent() {
        assertNull(GroupSystemEvents.parse("just a chat message"))
        assertNull(GroupSystemEvents.parse("""{"v":1,"text":"no type"}"""))
        assertNull(GroupSystemEvents.parse(""))
    }

    @Test
    fun summaryPrefersStructuredFieldsOverEmbeddedText() {
        val event = GroupSystemEvents.parse(avatarChangedJson)!!

        assertEquals(
            "alice changed the group avatar",
            GroupSystemEvents.summary(event, actorName = "alice", subjectName = null),
        )
    }

    @Test
    fun summaryUsesPassiveVoiceForUnattributedChanges() {
        val event =
            GroupSystemEvent(
                systemType = "member_removed",
                text = "",
                actor = null,
                subject = "ab12cd",
                name = null,
            )

        assertEquals(
            "bob was removed",
            GroupSystemEvents.summary(event, actorName = null, subjectName = "bob"),
        )
    }

    @Test
    fun summaryRendersRenameWithNewName() {
        val event =
            GroupSystemEvent(
                systemType = "group_renamed",
                text = "",
                actor = "d9",
                subject = null,
                name = "Ops crew",
            )

        assertEquals(
            "alice renamed the group to “Ops crew”",
            GroupSystemEvents.summary(event, actorName = "alice", subjectName = null),
        )
    }

    @Test
    fun summaryFallsBackToEmbeddedTextForUnknownTypes() {
        val event =
            GroupSystemEvent(
                systemType = "group_description_changed",
                text = "Group description changed",
                actor = null,
                subject = null,
                name = null,
            )

        assertEquals(
            "Group description changed",
            GroupSystemEvents.summary(event, actorName = null, subjectName = null),
        )
    }

    @Test
    fun actorAttributionFallsBackToTheEventSigner() {
        val unattributed =
            GroupSystemEvent(
                systemType = "member_added",
                text = "",
                actor = null,
                subject = "ab12cd",
                name = null,
            )

        // Signer fills in for a missing data.actor; an explicit actor wins;
        // passive voice only when neither names the committer.
        assertEquals("d946d2", GroupSystemEvents.actorHex(unattributed, "d946d2"))
        assertEquals("ef34", GroupSystemEvents.actorHex(unattributed.copy(actor = "ef34"), "d946d2"))
        assertNull(GroupSystemEvents.actorHex(unattributed, ""))
    }

    @Test
    fun selfActorRendersTheYouForms() {
        val event = GroupSystemEvents.parse(avatarChangedJson)!!

        assertEquals(
            "You changed the group avatar",
            GroupSystemEvents.summary(event, actorName = "Zesty Jaguar", subjectName = null, actorIsSelf = true),
        )
    }

    @Test
    fun selfSubjectRendersTheYouForms() {
        val added =
            GroupSystemEvent(
                systemType = "member_added",
                text = "",
                actor = "ef34",
                subject = "ab12cd",
                name = null,
            )

        assertEquals(
            "alice added you",
            GroupSystemEvents.summary(added, actorName = "alice", subjectName = "me", subjectIsSelf = true),
        )
        assertEquals(
            "You were added",
            GroupSystemEvents.summary(added.copy(actor = null), actorName = null, subjectName = "me", subjectIsSelf = true),
        )
    }

    @Test
    fun selfMatchingIsCaseInsensitive() {
        assertEquals(true, GroupSystemEvents.isSelf("AB12CD", "ab12cd"))
        assertEquals(false, GroupSystemEvents.isSelf("AB12CD", null))
        assertEquals(false, GroupSystemEvents.isSelf(null, "ab12cd"))
    }

    @Test
    fun previewTextIsNameFreePassiveForm() {
        assertEquals("The group avatar changed", GroupSystemEvents.previewText(avatarChangedJson))
        assertEquals("Group updated", GroupSystemEvents.previewText("not json"))
    }
}
