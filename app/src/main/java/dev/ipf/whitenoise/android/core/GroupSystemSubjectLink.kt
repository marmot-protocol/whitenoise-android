package dev.ipf.whitenoise.android.core

/**
 * A rendered group-system summary. When the affected member's name is a profile link, [subjectRange] is
 * where that name sits in [text] and [subjectAccountIdHex] is the authenticated account it opens.
 */
data class GroupSystemLinkedSummary(
    val text: String,
    val subjectRange: IntRange? = null,
    val subjectAccountIdHex: String? = null,
)

/**
 * Makes the affected member of a member-added or member-removed row a profile link (#2957). The link is
 * keyed by the authenticated subject id MDK projected, never by a display name or parsed prose, so two
 * people sharing a name still open their own profiles and a removed member stays reachable.
 */
object GroupSystemSubjectLink {
    // A private-use character no localized template contains, so its position in the rendered summary is
    // exactly where the subject's name goes — whatever word order the locale uses.
    private const val Placeholder = ""
    private val LinkedTypes = setOf("member_added", "member_removed")

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
            ?.takeIf { it.fromAuthenticatedStateProjection && it.systemType in LinkedTypes }
            ?.subject
            ?.takeIf { it.isNotBlank() && !GroupSystemEvents.isSelf(selfAccountIdHex, it) }

    /**
     * Renders the summary through [render] (which fills the subject slot with its argument) and, for a
     * linkable row, locates the subject's name by rendering once with a placeholder in that slot. Any
     * ambiguity — no slot, or the placeholder appearing twice — leaves the row unlinked rather than guessing.
     */
    fun summary(
        event: GroupSystemEvent?,
        selfAccountIdHex: String?,
        subjectName: String?,
        render: (subjectName: String?) -> String,
    ): GroupSystemLinkedSummary {
        val subject = target(event, selfAccountIdHex)
        val name = subjectName?.takeIf { subject != null && it.isNotEmpty() }
        val marked = name?.let { render(Placeholder) }
        val start = marked?.indexOf(Placeholder)?.takeIf { it >= 0 && it == marked.lastIndexOf(Placeholder) }
        return if (marked == null || name == null || start == null) {
            GroupSystemLinkedSummary(render(subjectName))
        } else {
            GroupSystemLinkedSummary(
                text = marked.replaceRange(start, start + Placeholder.length, name),
                subjectRange = start until start + name.length,
                subjectAccountIdHex = subject,
            )
        }
    }

    /**
     * The profile to open for a tapped subject link, or null when the reader has switched accounts since
     * the row was rendered — a late tap must not open someone under the wrong account.
     */
    fun openTarget(
        subjectAccountIdHex: String,
        renderedAccountRef: String?,
        activeAccountRef: String?,
    ): String? = subjectAccountIdHex.takeIf { renderedAccountRef != null && renderedAccountRef == activeAccountRef }
}
