package dev.ipf.whitenoise.android.core

import dev.ipf.marmotkit.GroupSystemEventFfi
import org.json.JSONObject

/**
 * Parsed content of a kind-1210 group system row (schema v1). These rows are
 * synthesized from MLS-authenticated group state and must never render as
 * chat bubbles; see spec/foundation/application-messages.md.
 */
data class GroupSystemEvent(
    val systemType: String,
    val text: String,
    val actor: String?,
    val subject: String?,
    val name: String?,
    // Rename changes (kind:1210 `group_renamed`): the previous group name, when
    // the payload carries it (`data.old_name`). null = absent, in which case the
    // row falls back to the new-name-only form rather than faking an "Unknown →"
    // diff. Peer-supplied, so it is sanitized at render time like `name`.
    val oldName: String? = null,
    // Disappearing-timer changes (kind:1210 `disappearing_timer_changed`): the
    // previous and new per-group retention in seconds. 0 = off; null = absent.
    val oldRetentionSeconds: ULong? = null,
    val newRetentionSeconds: ULong? = null,
)

data class GroupRenameDiffNames(
    val oldName: String,
    val newName: String,
)

/**
 * Localized format strings for group system rows, threaded in from string
 * resources the same way as [MessageTextCopy]. Active forms take the actor's
 * resolved display name; passive forms cover unattributed changes (e.g. a
 * convergence reorg where the committer isn't resolved).
 */
data class GroupSystemCopy(
    val memberAddedFormat: String,
    val memberAddedPassiveFormat: String,
    val memberRemovedFormat: String,
    val memberRemovedPassiveFormat: String,
    val memberLeftFormat: String,
    val adminAddedFormat: String,
    val adminAddedPassiveFormat: String,
    val adminRemovedFormat: String,
    val adminRemovedPassiveFormat: String,
    val renamedFormat: String,
    val renamedPassiveFormat: String,
    // Rename diff forms used when the previous name is known. %1$s = actor,
    // %2$s = old name, %3$s = new name (active); %1$s = old, %2$s = new
    // (passive). Both names are sanitized and rendered inside explicit visual
    // delimiters so peer-supplied text can't impersonate the surrounding row.
    val renamedDiffFormat: String,
    val renamedDiffPassiveFormat: String,
    val avatarChangedFormat: String,
    val avatarChangedPassive: String,
    // Dedicated self forms rather than substituting a pronoun into the name
    // slot — inflected locales conjugate around the pronoun (passive voice,
    // verb agreement), so "You" can't just replace %1$s.
    val youMemberAddedFormat: String,
    val memberAddedYouFormat: String,
    val memberAddedYouPassive: String,
    val youMemberRemovedFormat: String,
    val memberRemovedYouFormat: String,
    val memberRemovedYouPassive: String,
    val youMemberLeft: String,
    val youAdminAddedFormat: String,
    val adminAddedYouFormat: String,
    val adminAddedYouPassive: String,
    val youAdminRemovedFormat: String,
    val adminRemovedYouFormat: String,
    val adminRemovedYouPassive: String,
    val youRenamedFormat: String,
    // Self rename diff: %1$s = old name, %2$s = new name.
    val youRenamedDiffFormat: String,
    val youAvatarChanged: String,
    val disappearingSetFormat: String,
    val disappearingSetYouFormat: String,
    val disappearingSetPassiveFormat: String,
    val disappearingOffFormat: String,
    val disappearingOffYou: String,
    val disappearingOffPassive: String,
    val someone: String,
    val fallback: String,
) {
    companion object {
        val Default =
            GroupSystemCopy(
                memberAddedFormat = "%1\$s added %2\$s",
                memberAddedPassiveFormat = "%1\$s was added",
                memberRemovedFormat = "%1\$s removed %2\$s",
                memberRemovedPassiveFormat = "%1\$s was removed",
                memberLeftFormat = "%1\$s left",
                adminAddedFormat = "%1\$s made %2\$s an admin",
                adminAddedPassiveFormat = "%1\$s was made an admin",
                adminRemovedFormat = "%1\$s removed %2\$s as admin",
                adminRemovedPassiveFormat = "%1\$s is no longer an admin",
                renamedFormat = "%1\$s renamed the group to “%2\$s”",
                renamedPassiveFormat = "The group was renamed to “%1\$s”",
                renamedDiffFormat = "%1\$s renamed the group from “%2\$s” to “%3\$s”",
                renamedDiffPassiveFormat = "The group was renamed from “%1\$s” to “%2\$s”",
                avatarChangedFormat = "%1\$s changed the group avatar",
                avatarChangedPassive = "The group avatar changed",
                youMemberAddedFormat = "You added %1\$s",
                memberAddedYouFormat = "%1\$s added you",
                memberAddedYouPassive = "You were added",
                youMemberRemovedFormat = "You removed %1\$s",
                memberRemovedYouFormat = "%1\$s removed you",
                memberRemovedYouPassive = "You were removed",
                youMemberLeft = "You left",
                youAdminAddedFormat = "You made %1\$s an admin",
                adminAddedYouFormat = "%1\$s made you an admin",
                adminAddedYouPassive = "You are now an admin",
                youAdminRemovedFormat = "You removed %1\$s as admin",
                adminRemovedYouFormat = "%1\$s removed you as admin",
                adminRemovedYouPassive = "You are no longer an admin",
                youRenamedFormat = "You renamed the group to “%1\$s”",
                youRenamedDiffFormat = "You renamed the group from “%1\$s” to “%2\$s”",
                youAvatarChanged = "You changed the group avatar",
                disappearingSetFormat = "%1\$s set messages to disappear after %2\$s",
                disappearingSetYouFormat = "You set messages to disappear after %1\$s",
                disappearingSetPassiveFormat = "Messages now disappear after %1\$s",
                disappearingOffFormat = "%1\$s turned off disappearing messages",
                disappearingOffYou = "You turned off disappearing messages",
                disappearingOffPassive = "Disappearing messages are off",
                someone = "Someone",
                fallback = "Group updated",
            )
    }
}

object GroupSystemEvents {
    private const val TypeMemberAdded = "member_added"
    private const val TypeMemberRemoved = "member_removed"
    private const val TypeMemberLeft = "member_left"
    private const val TypeAdminAdded = "admin_added"
    private const val TypeAdminRemoved = "admin_removed"
    private const val TypeGroupRenamed = "group_renamed"
    private const val TypeGroupAvatarChanged = "group_avatar_changed"
    private const val TypeDisappearingTimerChanged = "disappearing_timer_changed"

    /**
     * Parses kind-1210 JSON content. Null when [plaintext] isn't a group
     * system payload — the caller still must not fall back to chat-body
     * rendering for a kind-1210 record; use [GroupSystemCopy.fallback].
     */
    fun parse(plaintext: String): GroupSystemEvent? =
        runCatching {
            val json = JSONObject(plaintext)
            val systemType = json.optString("system_type").takeIf { it.isNotBlank() } ?: return null
            val data = json.optJSONObject("data")
            GroupSystemEvent(
                systemType = systemType,
                text = json.optString("text"),
                actor = data?.optString("actor")?.takeIf { it.isNotBlank() },
                subject = data?.optString("subject")?.takeIf { it.isNotBlank() },
                name = data?.optString("name")?.takeIf { it.isNotBlank() },
                // Previous group name for a rename diff, when the peer includes
                // it. Absent stays null so the row falls back to the new-name
                // form rather than rendering a fake "Unknown → New" diff.
                oldName = data?.optString("old_name")?.takeIf { it.isNotBlank() },
                // Only accept a real non-negative number; absent or malformed
                // (non-numeric) stays null so it falls back rather than rendering
                // as an authoritative "turned off" (which is only secs == 0).
                oldRetentionSeconds = data?.optLong("old_retention_seconds", -1L)?.takeIf { it >= 0L }?.toULong(),
                newRetentionSeconds = data?.optLong("new_retention_seconds", -1L)?.takeIf { it >= 0L }?.toULong(),
            )
        }.getOrNull()

    fun fromFfi(ffi: GroupSystemEventFfi): GroupSystemEvent =
        GroupSystemEvent(
            systemType = ffi.systemType,
            text = ffi.text,
            actor = ffi.actorAccountIdHex,
            subject = ffi.subjectAccountIdHex,
            name = ffi.name,
            // The structured projection does not expose a previous name yet, so
            // `oldName` stays null here and the rename row falls back to the
            // new-name-only form. When Marmot adds a field, surface it here.
            oldName = null,
            oldRetentionSeconds = ffi.oldRetentionSeconds,
            newRetentionSeconds = ffi.newRetentionSeconds,
        )

    /**
     * Prefer Marmot's structured projection; fall back to parsing kind-1210
     * JSON. The structured projection does not yet carry a previous group name,
     * so when it is used we still backfill `oldName` from the JSON payload's
     * `data.old_name` (when present) so the rename row can render the diff
     * without inventing an Android-owned cache.
     */
    fun resolve(
        plaintext: String,
        structured: GroupSystemEventFfi? = null,
    ): GroupSystemEvent? =
        structured?.let { ffi ->
            val event = fromFfi(ffi)
            if (event.oldName == null) event.copy(oldName = parse(plaintext)?.oldName) else event
        } ?: parse(plaintext)

    /**
     * The hex pubkey to attribute the change to: the payload's `actor` when
     * named, otherwise the event signer — a peer that omits `data.actor` but
     * signs as the committing member still names the actor via the envelope.
     * Null (passive voice) only when neither is present, e.g. a synthesized
     * row for a convergence reorg.
     */
    fun actorHex(
        event: GroupSystemEvent,
        senderHex: String,
    ): String? = event.actor ?: senderHex.takeIf { it.isNotBlank() }

    /** Sanitized old/new rename names when a real previous name is known. */
    fun renameDiffNames(event: GroupSystemEvent): GroupRenameDiffNames? =
        if (event.systemType == TypeGroupRenamed) {
            ProfileSanitizer.displayName(event.name)?.let { name -> renameDiffNames(event, name) }
        } else {
            null
        }

    private fun renameDiffNames(
        event: GroupSystemEvent,
        newName: String,
    ): GroupRenameDiffNames? =
        ProfileSanitizer
            .displayName(event.oldName)
            ?.takeIf { it != newName }
            ?.let { oldName -> GroupRenameDiffNames(oldName = oldName, newName = newName) }

    /**
     * One-line summary rendered from `system_type` + `data` per the spec —
     * the embedded `text` is a last-resort fallback only, since synthesized
     * rows often carry it empty and names should re-resolve as profiles load.
     * [actorName]/[subjectName] are the caller's resolved display names;
     * [actorIsSelf]/[subjectIsSelf] select the dedicated "You" forms when the
     * active account is the one acting or acted upon.
     */
    fun summary(
        event: GroupSystemEvent,
        actorName: String?,
        subjectName: String?,
        actorIsSelf: Boolean = false,
        subjectIsSelf: Boolean = false,
        // Localized label for the new retention window (e.g. "7 days"), supplied
        // by the caller that has the duration formatter. Only used by the
        // disappearing-timer row's "set to …" forms.
        retentionLabel: String? = null,
        copy: GroupSystemCopy = GroupSystemCopy.Default,
    ): String {
        val subject = subjectName ?: copy.someone
        return when (event.systemType) {
            TypeMemberAdded ->
                when {
                    actorIsSelf -> String.format(copy.youMemberAddedFormat, subject)
                    subjectIsSelf && actorName != null -> String.format(copy.memberAddedYouFormat, actorName)
                    subjectIsSelf -> copy.memberAddedYouPassive
                    actorName != null -> String.format(copy.memberAddedFormat, actorName, subject)
                    else -> String.format(copy.memberAddedPassiveFormat, subject)
                }
            TypeMemberRemoved ->
                when {
                    actorIsSelf -> String.format(copy.youMemberRemovedFormat, subject)
                    subjectIsSelf && actorName != null -> String.format(copy.memberRemovedYouFormat, actorName)
                    subjectIsSelf -> copy.memberRemovedYouPassive
                    actorName != null -> String.format(copy.memberRemovedFormat, actorName, subject)
                    else -> String.format(copy.memberRemovedPassiveFormat, subject)
                }
            TypeMemberLeft ->
                when {
                    actorIsSelf || (actorName == null && subjectIsSelf) -> copy.youMemberLeft
                    else -> String.format(copy.memberLeftFormat, actorName ?: subject)
                }
            TypeAdminAdded ->
                when {
                    actorIsSelf -> String.format(copy.youAdminAddedFormat, subject)
                    subjectIsSelf && actorName != null -> String.format(copy.adminAddedYouFormat, actorName)
                    subjectIsSelf -> copy.adminAddedYouPassive
                    actorName != null -> String.format(copy.adminAddedFormat, actorName, subject)
                    else -> String.format(copy.adminAddedPassiveFormat, subject)
                }
            TypeAdminRemoved ->
                when {
                    actorIsSelf -> String.format(copy.youAdminRemovedFormat, subject)
                    subjectIsSelf && actorName != null -> String.format(copy.adminRemovedYouFormat, actorName)
                    subjectIsSelf -> copy.adminRemovedYouPassive
                    actorName != null -> String.format(copy.adminRemovedFormat, actorName, subject)
                    else -> String.format(copy.adminRemovedPassiveFormat, subject)
                }
            TypeGroupRenamed ->
                ProfileSanitizer.displayName(event.name)?.let { name ->
                    // Both names are peer-supplied; sanitize the old name the
                    // same way (strip bidi/zero-width, NFKC-fold, cap length)
                    // before comparing or rendering. Only show the "old → new"
                    // diff when a real previous name survives sanitization AND
                    // differs from the new one — a blank/absent old name (first
                    // name set) or a whitespace-only change collapses to the
                    // same value and falls back to the new-name-only form,
                    // never a fake "Unknown → New" or a no-op self-diff.
                    val diff = renameDiffNames(event, name)
                    when {
                        diff != null && actorIsSelf -> String.format(copy.youRenamedDiffFormat, diff.oldName, diff.newName)
                        diff != null && actorName != null ->
                            String.format(copy.renamedDiffFormat, actorName, diff.oldName, diff.newName)
                        diff != null -> String.format(copy.renamedDiffPassiveFormat, diff.oldName, diff.newName)
                        actorIsSelf -> String.format(copy.youRenamedFormat, name)
                        actorName != null -> String.format(copy.renamedFormat, actorName, name)
                        else -> String.format(copy.renamedPassiveFormat, name)
                    }
                } ?: copy.fallback
            TypeGroupAvatarChanged ->
                when {
                    actorIsSelf -> copy.youAvatarChanged
                    actorName != null -> String.format(copy.avatarChangedFormat, actorName)
                    else -> copy.avatarChangedPassive
                }
            TypeDisappearingTimerChanged -> {
                // Off only when the new window is explicitly 0; a null (absent or
                // malformed payload) must fall back, not render as "turned off".
                val turnedOff = event.newRetentionSeconds == 0uL
                when {
                    turnedOff && actorIsSelf -> copy.disappearingOffYou
                    turnedOff && actorName != null -> String.format(copy.disappearingOffFormat, actorName)
                    turnedOff -> copy.disappearingOffPassive
                    // Turned on / changed → "set to <duration>". retentionLabel is the
                    // localized new-window label from the caller; without it (name-free
                    // preview path) fall back rather than print raw seconds.
                    retentionLabel == null -> copy.fallback
                    actorIsSelf -> String.format(copy.disappearingSetYouFormat, retentionLabel)
                    actorName != null -> String.format(copy.disappearingSetFormat, actorName, retentionLabel)
                    else -> String.format(copy.disappearingSetPassiveFormat, retentionLabel)
                }
            }
            // Never render the embedded `text` for unknown (or malformed)
            // payloads: the system row visually presents content as a
            // state-derived fact, and `text` is peer-authored free text — an
            // unknown system_type carrying "X removed you" would read as a
            // spoofed authoritative event. Unknown types get the neutral
            // fallback until a known rendering exists for them.
            else -> copy.fallback
        }
    }

    /**
     * Case-insensitive self test for hex account ids — sender/actor casing
     * can drift between clients the same way reaction senders do.
     */
    fun isSelf(
        accountIdHex: String?,
        candidateHex: String?,
    ): Boolean = accountIdHex != null && candidateHex != null && candidateHex.equals(accountIdHex, ignoreCase = true)

    /**
     * Name-free summary for chat-list previews and notifications, where no
     * profile resolution is available: the localized passive form.
     */
    fun previewText(
        plaintext: String,
        copy: GroupSystemCopy = GroupSystemCopy.Default,
        structured: GroupSystemEventFfi? = null,
    ): String {
        val event = resolve(plaintext, structured) ?: return copy.fallback
        return summary(event, actorName = null, subjectName = null, copy = copy)
    }
}
