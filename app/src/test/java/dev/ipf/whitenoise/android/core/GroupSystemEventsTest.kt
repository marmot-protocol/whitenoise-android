package dev.ipf.whitenoise.android.core

import dev.ipf.marmotkit.GroupSystemEventFfi
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
    fun parsesOldNameFromRenamePayload() {
        val json =
            """{"v":1,"system_type":"group_renamed","text":"Group renamed",""" +
                """"data":{"actor":"$actorHex","name":"Marmot Protocol","old_name":"Marmot Lab"}}"""

        val event = GroupSystemEvents.parse(json)!!

        assertEquals("Marmot Protocol", event.name)
        assertEquals("Marmot Lab", event.oldName)
    }

    @Test
    fun summaryRendersRenameDiffWhenOldNameIsKnown() {
        val event =
            GroupSystemEvent(
                systemType = "group_renamed",
                text = "",
                actor = "d9",
                subject = null,
                name = "Marmot Protocol",
                oldName = "Marmot Lab",
            )

        assertEquals(
            "alice renamed the group from “Marmot Lab” to “Marmot Protocol”",
            GroupSystemEvents.summary(event, actorName = "alice", subjectName = null),
        )
    }

    @Test
    fun summaryRendersRenameDiffPassiveFormForNotifications() {
        // The name-free passive form is what feeds chat-list previews and
        // notification bodies; it must carry the diff too when old name is known.
        val event =
            GroupSystemEvent(
                systemType = "group_renamed",
                text = "",
                actor = null,
                subject = null,
                name = "Marmot Protocol",
                oldName = "Marmot Lab",
            )

        assertEquals(
            "The group was renamed from “Marmot Lab” to “Marmot Protocol”",
            GroupSystemEvents.summary(event, actorName = null, subjectName = null),
        )
    }

    @Test
    fun summaryRendersSelfRenameDiff() {
        val event =
            GroupSystemEvent(
                systemType = "group_renamed",
                text = "",
                actor = "d9",
                subject = null,
                name = "Marmot Protocol",
                oldName = "Marmot Lab",
            )

        assertEquals(
            "You renamed the group from “Marmot Lab” to “Marmot Protocol”",
            GroupSystemEvents.summary(event, actorName = "Zoe", subjectName = null, actorIsSelf = true),
        )
    }

    @Test
    fun summaryFallsBackToNewNameWhenOldNameAbsent() {
        // No old name: keep the existing new-name-only behavior, never an
        // "Unknown → New" diff.
        val event =
            GroupSystemEvent(
                systemType = "group_renamed",
                text = "",
                actor = "d9",
                subject = null,
                name = "Marmot Protocol",
                oldName = null,
            )

        assertEquals(
            "alice renamed the group to “Marmot Protocol”",
            GroupSystemEvents.summary(event, actorName = "alice", subjectName = null),
        )
    }

    @Test
    fun summaryDoesNotFakeADiffForFirstNameSet() {
        // First-ever name set / blank old name must not synthesize a diff; it
        // renders the dedicated named-the-group form instead.
        val event =
            GroupSystemEvent(
                systemType = "group_renamed",
                text = "",
                actor = "d9",
                subject = null,
                name = "Marmot Lab",
                oldName = "   ",
            )

        assertEquals(
            "alice named the group “Marmot Lab”",
            GroupSystemEvents.summary(event, actorName = "alice", subjectName = null),
        )
    }

    @Test
    fun summaryDoesNotRenderNoOpWhitespaceOnlyRenameAsADiff() {
        // A change that only adds/removes whitespace collapses to the same name
        // after sanitization, so it falls back to the new-name form rather than
        // creating a "old → new" surface where old and new look identical.
        val event =
            GroupSystemEvent(
                systemType = "group_renamed",
                text = "",
                actor = "d9",
                subject = null,
                name = "Marmot Lab",
                oldName = "Marmot   Lab",
            )

        assertEquals(
            "alice renamed the group to “Marmot Lab”",
            GroupSystemEvents.summary(event, actorName = "alice", subjectName = null),
        )
    }

    @Test
    fun summarySanitizesBothNamesInTheRenameDiff() {
        // Both names are peer-supplied. Bidi overrides and zero-width chars must
        // be stripped from old AND new before rendering, and the “ ” delimiters
        // around each keep the names visually fenced from the row's own copy.
        val event =
            GroupSystemEvent(
                systemType = "group_renamed",
                text = "",
                actor = "d9",
                subject = null,
                name = "New\u200bName\u2066",
                oldName = "\u202EOld\u200dName",
            )

        assertEquals(
            "alice renamed the group from “Old\u200dName” to “NewName”",
            GroupSystemEvents.summary(event, actorName = "alice", subjectName = null),
        )
    }

    @Test
    fun renameDiffFlowsThroughPreviewTextWhenOldNameIsInPayload() {
        val json =
            """{"v":1,"system_type":"group_renamed",""" +
                """"data":{"name":"Marmot Protocol","old_name":"Marmot Lab"}}"""

        assertEquals(
            "The group was renamed from “Marmot Lab” to “Marmot Protocol”",
            GroupSystemEvents.previewText(json),
        )
    }

    @Test
    fun resolveBackfillsOldNameFromPayloadWhenStructuredProjectionOmitsIt() {
        // The system row renders from the structured FFI projection, which does
        // not carry a previous name yet. resolve() must still backfill old_name
        // from the JSON payload so the diff shows on that path.
        val structured =
            GroupSystemEventFfi(
                systemType = "group_renamed",
                text = "Group renamed",
                actorAccountIdHex = "alice",
                subjectAccountIdHex = null,
                name = "Marmot Protocol",
                oldName = null,
                oldRetentionSeconds = null,
                newRetentionSeconds = null,
            )
        val json =
            """{"v":1,"system_type":"group_renamed",""" +
                """"data":{"name":"Marmot Protocol","old_name":"Marmot Lab"}}"""

        val event = GroupSystemEvents.resolve(json, structured)!!

        assertEquals("Marmot Protocol", event.name)
        assertEquals("Marmot Lab", event.oldName)
    }

    @Test
    fun resolveUsesLocalPreviousNameForCurrentProjectionShape() {
        // Current Marmot group-rename rows expose only data.name / FFI.name. The
        // Android local snapshot supplies the previous name without requiring a
        // hypothetical data.old_name field.
        val structured =
            GroupSystemEventFfi(
                systemType = "group_renamed",
                text = "Group renamed",
                actorAccountIdHex = "alice",
                subjectAccountIdHex = null,
                name = "Marmot Protocol",
                oldName = null,
                oldRetentionSeconds = null,
                newRetentionSeconds = null,
            )
        val json =
            """{"v":1,"system_type":"group_renamed",""" +
                """"data":{"name":"Marmot Protocol"}}"""

        val event = GroupSystemEvents.resolve(json, structured, GroupRenamePreviousName("Marmot Lab"))!!

        assertEquals(
            "alice renamed the group from “Marmot Lab” to “Marmot Protocol”",
            GroupSystemEvents.summary(event, actorName = "alice", subjectName = null),
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
