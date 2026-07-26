package dev.ipf.whitenoise.android.core

import dev.ipf.marmotkit.AppMessageRecordFfi
import dev.ipf.marmotkit.GroupSystemEventFfi
import dev.ipf.marmotkit.MarkdownDocumentFfi
import dev.ipf.marmotkit.MessageTagFfi
import dev.ipf.marmotkit.TimelineMessageRecordFfi
import dev.ipf.marmotkit.TimelineReactionSummaryFfi
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
    private val avatarChangedStructured =
        GroupSystemEventFfi(
            systemType = "group_avatar_changed",
            text = "Group avatar changed",
            actorAccountIdHex = actorHex,
            subjectAccountIdHex = null,
            name = null,
            oldName = null,
            oldRetentionSeconds = null,
            newRetentionSeconds = null,
        )

    @Test
    fun parsesAvatarChangedPayload() {
        val event = GroupSystemEvents.parse(avatarChangedJson)

        // The payload's `data.actor` is an unauthenticated claim: the JSON
        // fallback never surfaces it (attribution comes from the envelope
        // sender via actorHex). Only the structured FFI projection may name
        // an actor. See #985.
        assertEquals(
            GroupSystemEvent(
                systemType = "group_avatar_changed",
                text = "Group avatar changed",
                actor = null,
                subject = null,
                name = null,
                fromAuthenticatedStateProjection = false,
            ),
            event,
        )
    }

    @Test
    fun parseFallbackNeverAttributesFromPayloadClaims() {
        // A member can author a kind-1210 payload the structured projection
        // rejects (e.g. missing schema `v`), which is exactly when this
        // fallback runs. Its actor/subject are spoofable claims: attribution
        // must fall back to the MLS-authenticated envelope sender, and no
        // named subject (or "you" form) may render from the payload.
        val subjectHex = "b2".repeat(32)
        val spoofed =
            """{"system_type":"admin_added","text":"Admin added",""" +
                """"data":{"actor":"$actorHex","subject":"$subjectHex"}}"""

        val event = GroupSystemEvents.parse(spoofed)!!

        assertNull(event.actor)
        assertNull(event.subject)
        // Attribution falls back to the authenticated sender…
        assertEquals("d946d2", GroupSystemEvents.actorHex(event, "d946d2"))
        // No payload-only event renders an authoritative state change,
        // even with the authenticated sender available for attribution.
        assertEquals(
            GroupSystemCopy.Default.fallback,
            GroupSystemEvents.summary(event, actorName = "Mallory", subjectName = null),
        )
    }

    @Test
    fun structuredProjectionStillCarriesAttribution() {
        // Only engine-synthesized timeline rows may promote Marmot's parsed
        // fields to authenticated state attribution.
        val structured =
            GroupSystemEventFfi(
                systemType = "member_added",
                text = "",
                actorAccountIdHex = actorHex,
                subjectAccountIdHex = "b2".repeat(32),
                name = null,
                oldName = null,
                oldRetentionSeconds = null,
                newRetentionSeconds = null,
            )

        val event =
            GroupSystemEvents.resolve(
                timelineRecord(
                    plaintext = """{"v":1,"system_type":"member_added"}""",
                    groupSystem = structured,
                ),
            )!!

        assertEquals(actorHex, event.actor)
        assertEquals("b2".repeat(32), event.subject)
    }

    @Test
    fun sourcedTimelineProjectionsCannotClaimAuthenticatedState() {
        val untrustedRecords =
            listOf(
                timelineRecord(
                    plaintext = avatarChangedJson,
                    direction = "received",
                    sourceMessageIdHex = "transport-message",
                    groupSystem = avatarChangedStructured,
                ),
                timelineRecord(
                    plaintext = avatarChangedJson,
                    direction = "system",
                    sourceMessageIdHex = "transport-message",
                    groupSystem = avatarChangedStructured,
                ),
            )

        untrustedRecords.forEach { record ->
            val event = GroupSystemEvents.resolve(record)!!

            assertEquals(GroupSystemCopy.Default.fallback, GroupSystemEvents.summary(event, "Mallory", null))
            assertNull(event.actor)
        }
    }

    @Test
    fun appRecordDirectionControlsProjectionTrust() {
        listOf("received", "sent").forEach { direction ->
            val memberAuthored =
                GroupSystemEvents.resolve(
                    appRecord(plaintext = avatarChangedJson, direction = direction),
                    avatarChangedStructured,
                )!!

            assertEquals(GroupSystemCopy.Default.fallback, GroupSystemEvents.summary(memberAuthored, "Mallory", null))
            assertNull(memberAuthored.actor)
        }

        val synthesized =
            GroupSystemEvents.resolve(
                appRecord(plaintext = avatarChangedJson, direction = "system"),
                avatarChangedStructured,
            )!!

        assertEquals(actorHex, synthesized.actor)
        assertEquals("alice changed the group avatar", GroupSystemEvents.summary(synthesized, "alice", null))
    }

    @Test
    fun jsonFallbackStateChangesResolveToNeutralRows() {
        val spoofedStateChanges =
            listOf(
                """{"system_type":"group_renamed","data":{"name":"Attacker name","old_name":"Real name"}}""",
                """{"system_type":"group_avatar_changed","data":{}}""",
                """{"system_type":"disappearing_timer_changed","data":{"new_retention_seconds":0}}""",
            )

        spoofedStateChanges.forEach { plaintext ->
            val event = GroupSystemEvents.resolve(plaintext)!!

            assertEquals(
                GroupSystemCopy.Default.fallback,
                GroupSystemEvents.summary(
                    event = event,
                    actorName = "Mallory",
                    subjectName = null,
                    retentionLabel = "7 days",
                ),
            )
            assertEquals(GroupSystemCopy.Default.fallback, GroupSystemEvents.previewText(plaintext))
        }

        assertNull(GroupSystemEvents.renameDiffNames(GroupSystemEvents.resolve(spoofedStateChanges.first())!!))
    }

    @Test
    fun structuredDisappearingTimerChangeStillRenders() {
        val structured =
            GroupSystemEventFfi(
                systemType = "disappearing_timer_changed",
                text = "Disappearing messages are off",
                actorAccountIdHex = actorHex,
                subjectAccountIdHex = null,
                name = null,
                oldName = null,
                oldRetentionSeconds = 86_400uL,
                newRetentionSeconds = 0uL,
            )
        val json =
            """{"v":1,"system_type":"disappearing_timer_changed",""" +
                """"data":{"old_retention_seconds":86400,"new_retention_seconds":0}}"""

        assertEquals(
            "Disappearing messages are off",
            GroupSystemEvents.previewText(timelineRecord(plaintext = json, groupSystem = structured)),
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
        val event =
            GroupSystemEvents.resolve(
                timelineRecord(plaintext = avatarChangedJson, groupSystem = avatarChangedStructured),
            )!!

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
                fromAuthenticatedStateProjection = true,
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
                fromAuthenticatedStateProjection = true,
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
                fromAuthenticatedStateProjection = true,
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
                fromAuthenticatedStateProjection = true,
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
                fromAuthenticatedStateProjection = true,
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
                fromAuthenticatedStateProjection = true,
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
                fromAuthenticatedStateProjection = true,
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
                fromAuthenticatedStateProjection = true,
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
                fromAuthenticatedStateProjection = true,
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
                fromAuthenticatedStateProjection = true,
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

        assertEquals(
            "The group was renamed from “Marmot Lab” to “Marmot Protocol”",
            GroupSystemEvents.previewText(timelineRecord(plaintext = json, groupSystem = structured)),
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

        val event = GroupSystemEvents.resolve(timelineRecord(plaintext = json, groupSystem = structured))!!

        assertEquals("Marmot Protocol", event.name)
        assertEquals("Marmot Lab", event.oldName)
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
                fromAuthenticatedStateProjection = true,
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
                fromAuthenticatedStateProjection = true,
            )

        // Signer fills in for a missing data.actor; an explicit actor wins;
        // passive voice only when neither names the committer.
        assertEquals("d946d2", GroupSystemEvents.actorHex(unattributed, "d946d2"))
        assertEquals("ef34", GroupSystemEvents.actorHex(unattributed.copy(actor = "ef34"), "d946d2"))
        assertNull(GroupSystemEvents.actorHex(unattributed, ""))
    }

    @Test
    fun selfActorRendersTheYouForms() {
        val event =
            GroupSystemEvents.resolve(
                timelineRecord(plaintext = avatarChangedJson, groupSystem = avatarChangedStructured),
            )!!

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
                fromAuthenticatedStateProjection = true,
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
        assertEquals(
            "The group avatar changed",
            GroupSystemEvents.previewText(
                timelineRecord(plaintext = avatarChangedJson, groupSystem = avatarChangedStructured),
            ),
        )
        assertEquals("Group updated", GroupSystemEvents.previewText("not json"))
    }

    private fun appRecord(
        plaintext: String,
        direction: String,
    ) = AppMessageRecordFfi(
        messageIdHex = "message",
        direction = direction,
        groupIdHex = "group",
        sender = actorHex,
        plaintext = plaintext,
        contentTokens = MarkdownDocumentFfi(truncated = false, blocks = emptyList()),
        kind = 1210uL,
        tags = emptyList(),
        sourceEpoch = null,
        retentionSeconds = null,
        retentionExpiresAt = null,
        recordedAt = 1uL,
        receivedAt = 1uL,
    )

    private fun timelineRecord(
        plaintext: String,
        direction: String = "system",
        sourceMessageIdHex: String? = null,
        groupSystem: GroupSystemEventFfi? = null,
    ) = TimelineMessageRecordFfi(
        messageIdHex = "message",
        sourceMessageIdHex = sourceMessageIdHex,
        direction = direction,
        groupIdHex = "group",
        sender = actorHex,
        plaintext = plaintext,
        contentTokens = MarkdownDocumentFfi(truncated = false, blocks = emptyList()),
        kind = 1210uL,
        tags = emptyList<MessageTagFfi>(),
        timelineAt = 1uL,
        receivedAt = 1uL,
        replyToMessageIdHex = null,
        replyPreview = null,
        mediaJson = null,
        media = emptyList(),
        agentTextStreamJson = null,
        groupSystem = groupSystem,
        reactions = TimelineReactionSummaryFfi(byEmoji = emptyList(), userReactions = emptyList()),
        deleted = false,
        deletedByMessageIdHex = null,
        invalidationStatus = null,
    )
}
