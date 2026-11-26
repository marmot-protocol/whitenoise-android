package dev.ipf.whitenoise.android.core

/** Which participant of a group-system row a profile link names. */
enum class GroupSystemLinkRole { ACTOR, SUBJECT }

/** One profile link inside a rendered summary: the [range] of the name in the text and the account it opens. */
data class GroupSystemProfileLink(
    val role: GroupSystemLinkRole,
    val range: IntRange,
    val accountIdHex: String,
)

/** A rendered group-system summary and the profile links it carries, ordered by position in [text]. */
data class GroupSystemLinkedSummary(
    val text: String,
    val links: List<GroupSystemProfileLink> = emptyList(),
)

/**
 * Makes participant names in member-added and member-removed rows profile links (#2957, #4209). Each link
 * is keyed by an authenticated account id from MDK's projection, never by a display name or parsed prose,
 * so two people sharing a name still open their own profiles and a removed member stays reachable.
 */
object GroupSystemSubjectLink {
    // Private-use characters no localized template contains, so each one's position in the rendered summary
    // is exactly where that participant's name goes — whatever word order the locale uses.
    private const val SubjectPlaceholder = "\uE000"
    private const val ActorPlaceholder = "\uE001"
    private val SubjectLinkedTypes = setOf("member_added", "member_removed")
    private val ActorLinkedTypes = setOf("member_added")

    /**
     * The account a row's subject link opens, or null. Only an authenticated add/remove of someone other
     * than the reader qualifies: member-authored and fallback rows have no trusted subject, and the
     * reader's own change renders as "You", which has no name to link.
     */
    fun target(
        event: GroupSystemEvent?,
        selfAccountIdHex: String?,
    ): String? =
        event
            ?.takeIf { it.fromAuthenticatedStateProjection && it.systemType in SubjectLinkedTypes }
            ?.subject
            ?.takeIf { it.isNotBlank() && !GroupSystemEvents.isSelf(selfAccountIdHex, it) }

    /**
     * The account a row's actor link opens, or null. Only the member who added someone in an authenticated
     * row qualifies, keyed by [actorAccountIdHex] as [GroupSystemEvents.actorHex] resolved it. The reader's
     * own action renders as "You" and an unknown actor renders in the passive voice; neither is linked.
     */
    fun actorTarget(
        event: GroupSystemEvent?,
        actorAccountIdHex: String?,
        selfAccountIdHex: String?,
    ): String? =
        actorAccountIdHex
            ?.takeIf { event?.fromAuthenticatedStateProjection == true && event.systemType in ActorLinkedTypes }
            ?.takeIf { it.isNotBlank() && !GroupSystemEvents.isSelf(selfAccountIdHex, it) }

    /**
     * Renders the summary through [render], which fills the actor and subject slots with its arguments, and
     * locates each linkable name by rendering once with a placeholder in that slot. A slot whose placeholder
     * is missing or appears more than once stays plain text rather than guessing; the other slot keeps its
     * link.
     */
    fun summary(
        event: GroupSystemEvent?,
        selfAccountIdHex: String?,
        actorAccountIdHex: String?,
        actorName: String?,
        subjectName: String?,
        render: (actorName: String?, subjectName: String?) -> String,
    ): GroupSystemLinkedSummary {
        val slots =
            listOfNotNull(
                slot(GroupSystemLinkRole.ACTOR, actorTarget(event, actorAccountIdHex, selfAccountIdHex), actorName),
                slot(GroupSystemLinkRole.SUBJECT, target(event, selfAccountIdHex), subjectName),
            )
        return linked(slots, actorName, subjectName, render)
    }

    /** Fills [slots] when each placeholder appears exactly once; otherwise retries with only the unique ones. */
    private fun linked(
        slots: List<Slot>,
        actorName: String?,
        subjectName: String?,
        render: (String?, String?) -> String,
    ): GroupSystemLinkedSummary {
        if (slots.isEmpty()) return GroupSystemLinkedSummary(render(actorName, subjectName))
        val marked = renderMarked(slots, actorName, subjectName, render)
        val unique =
            slots.filter { slot ->
                val at = marked.indexOf(slot.placeholder)
                at >= 0 && at == marked.lastIndexOf(slot.placeholder)
            }
        // Each retry drops at least one slot, so this ends after at most two renderings per slot.
        return if (unique.size == slots.size) fill(marked, slots) else linked(unique, actorName, subjectName, render)
    }

    /**
     * The profile to open for a tapped link, or null when the reader has switched accounts since the row was
     * rendered — a late tap must not open someone under the wrong account.
     */
    fun openTarget(
        accountIdHex: String,
        renderedAccountRef: String?,
        activeAccountRef: String?,
    ): String? = accountIdHex.takeIf { renderedAccountRef != null && renderedAccountRef == activeAccountRef }

    private class Slot(
        val role: GroupSystemLinkRole,
        val accountIdHex: String,
        val name: String,
    ) {
        val placeholder = if (role == GroupSystemLinkRole.ACTOR) ActorPlaceholder else SubjectPlaceholder
    }

    private fun slot(
        role: GroupSystemLinkRole,
        accountIdHex: String?,
        name: String?,
    ): Slot? = if (accountIdHex != null && !name.isNullOrEmpty()) Slot(role, accountIdHex, name) else null

    private fun renderMarked(
        slots: List<Slot>,
        actorName: String?,
        subjectName: String?,
        render: (String?, String?) -> String,
    ): String {
        fun nameFor(
            role: GroupSystemLinkRole,
            real: String?,
        ) = slots.firstOrNull { it.role == role }?.placeholder ?: real
        return render(nameFor(GroupSystemLinkRole.ACTOR, actorName), nameFor(GroupSystemLinkRole.SUBJECT, subjectName))
    }

    /** Replaces each slot's single placeholder with its name, left to right, recording the final ranges. */
    private fun fill(
        marked: String,
        slots: List<Slot>,
    ): GroupSystemLinkedSummary {
        val ordered = slots.sortedBy { marked.indexOf(it.placeholder) }
        val text = StringBuilder()
        val links = ArrayList<GroupSystemProfileLink>(ordered.size)
        var cursor = 0
        for (slot in ordered) {
            val at = marked.indexOf(slot.placeholder)
            text.append(marked, cursor, at)
            val start = text.length
            text.append(slot.name)
            links += GroupSystemProfileLink(slot.role, start until text.length, slot.accountIdHex)
            cursor = at + slot.placeholder.length
        }
        text.append(marked, cursor, marked.length)
        return GroupSystemLinkedSummary(text.toString(), links)
    }
}
