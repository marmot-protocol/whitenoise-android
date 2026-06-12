package dev.ipf.darkmatter.core

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
    val youAvatarChanged: String,
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
                youAvatarChanged = "You changed the group avatar",
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
            )
        }.getOrNull()

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
                event.name?.let { name ->
                    when {
                        actorIsSelf -> String.format(copy.youRenamedFormat, name)
                        actorName != null -> String.format(copy.renamedFormat, actorName, name)
                        else -> String.format(copy.renamedPassiveFormat, name)
                    }
                } ?: event.text.ifBlank { copy.fallback }
            TypeGroupAvatarChanged ->
                when {
                    actorIsSelf -> copy.youAvatarChanged
                    actorName != null -> String.format(copy.avatarChangedFormat, actorName)
                    else -> copy.avatarChangedPassive
                }
            else -> event.text.ifBlank { copy.fallback }
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
    ): String {
        val event = parse(plaintext) ?: return copy.fallback
        return summary(event, actorName = null, subjectName = null, copy = copy)
    }
}
