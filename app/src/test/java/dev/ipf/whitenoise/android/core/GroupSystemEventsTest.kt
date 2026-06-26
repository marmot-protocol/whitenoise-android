package dev.ipf.whitenoise.android.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class GroupSystemEventsTest {
    // Wire-shaped payload as a peer client emits it for an avatar change,
    // with a synthetic deterministic actor id.
    private val actorHex = "a1".repeat(32)
    private val avatarChangedJson =
        """{"v":1,"system_type":"group_avatar_changed","text":"Group avatar changed",""" +
            """"data":{"actor":"$actorHex"}}"""

    @Test
    fun parsesAvatarChangedPayload() {
        val event = GroupSystemEvents.parse(avatarChangedJson)

        assertEquals(
            GroupSystemEvent(
                systemType = "group_avatar_changed",
                text = "Group avatar changed",
                actor = actorHex,
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
    fun summaryNeverRendersPeerAuthoredTextForUnknownTypes() {
        // `text` is peer-authored free text; a system row presents content as
        // a state-derived fact, so an unknown type must render the neutral
        // fallback — not whatever the peer wrote.
        val event =
            GroupSystemEvent(
                systemType = "group_description_changed",
                text = "Alice removed you",
                actor = null,
                subject = null,
                name = null,
            )

        assertEquals(
            "Group updated",
            GroupSystemEvents.summary(event, actorName = null, subjectName = null),
        )
    }

    @Test
    fun renamedWithoutNameRendersFallbackNotText() {
        val event =
            GroupSystemEvent(
                systemType = "group_renamed",
                text = "You are no longer an admin",
                actor = null,
                subject = null,
                name = null,
            )

        assertEquals(
            "Group updated",
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
