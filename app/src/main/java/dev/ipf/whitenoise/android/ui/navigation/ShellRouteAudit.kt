package dev.ipf.whitenoise.android.ui.navigation

import android.util.Log

/** One audit failure: where it was seen, what was wrong and the stack it was wrong about. */
internal data class ShellRouteFinding(
    val origin: String,
    val problem: String,
    val stack: List<ShellRoute>,
)

/**
 * Process-wide record of route audits. With [ShellNavigator] the single owner there is no second route to compare
 * with, so after every event and every committed shell composition the audit checks what can still go wrong: that
 * the stack keeps its canonical shape, and that the section the Activity would save matches the stack. A finding
 * never throws in production: it is counted, kept (up to a small cap) for tests to assert on, and logged at debug
 * level by route shape only, with no account, group, message id or text.
 */
internal object ShellRouteAudit {
    private const val TAG = "ShellRouteAudit"
    private const val MAX_RECORDED = 32
    private val lock = Any()
    private val recorded = ArrayList<ShellRouteFinding>()
    private var checkCount = 0L
    private var findingTotal = 0L
    private val originTotals = LinkedHashMap<String, Long>()

    /** How many audits ran since the last [reset]. */
    val checks: Long
        get() = synchronized(lock) { checkCount }

    /** How many findings were seen since the last [reset], including any beyond the recorded cap. */
    val findingCount: Long
        get() = synchronized(lock) { findingTotal }

    /** How many audits each origin (an event name, or the per-composition check) drove since the last [reset]. */
    val originCounts: Map<String, Long>
        get() = synchronized(lock) { originTotals.toMap() }

    /** The first recorded findings since the last [reset]. */
    val findings: List<ShellRouteFinding>
        get() = synchronized(lock) { recorded.toList() }

    /** Audits [stack], and the saved section's [persistenceProblem] if any, and records what is wrong. */
    fun check(
        origin: String,
        stack: List<ShellRoute>,
        persistenceProblem: String? = null,
    ): Boolean {
        val problem = shellRouteShapeProblem(stack) ?: persistenceProblem
        synchronized(lock) {
            checkCount += 1
            originTotals[origin] = (originTotals[origin] ?: 0L) + 1L
            if (problem != null) {
                findingTotal += 1
                if (recorded.size < MAX_RECORDED) recorded += ShellRouteFinding(origin, problem, stack)
            }
        }
        if (problem != null) runCatching { Log.d(TAG, "finding origin=$origin $problem at ${routeShape(stack)}") }
        return problem == null
    }

    /** Forgets every audit and finding, for a test that starts its own record. */
    fun reset() {
        synchronized(lock) {
            checkCount = 0L
            findingTotal = 0L
            originTotals.clear()
            recorded.clear()
        }
    }
}

/**
 * What is wrong with [stack]'s shape, or null: the chat list as the only root, a Settings chain exactly as its leaf
 * re-synthesizes it, Diagnostics only on top of that chain, at most one conversation, and nothing above it.
 */
internal fun shellRouteShapeProblem(stack: List<ShellRoute>): String? {
    val section = sectionOf(stack).drop(1)
    val above = conversationSegment(stack).drop(1)
    val chain = section.filterIsInstance<ShellRoute.Settings>()
    val leaf = chain.lastOrNull()?.detail
    val expectedSection = if (section.isEmpty()) emptyList() else sectionEntries(sectionProjection(stack).first, leaf)
    return when {
        stack.firstOrNull() != ShellRoute.Chats -> "the root is not the chat list"
        stack.drop(1).any { it == ShellRoute.Chats } -> "the chat list above the root"
        section != expectedSection -> "the section is not the chain its leaf synthesizes"
        stack.count { it is ShellRoute.Conversation } > 1 -> "more than one conversation"
        above.isNotEmpty() -> "an entry above the conversation"
        else -> null
    }
}

/** A redacted outline of [stack] for logs: entry kinds and Settings details, never identifiers or message ids. */
internal fun routeShape(stack: List<ShellRoute>): String =
    stack.joinToString(" > ") { route ->
        when (route) {
            ShellRoute.Chats -> "Chats"
            ShellRoute.Diagnostics -> "Diagnostics"
            is ShellRoute.Settings -> "Settings(${route.detail?.name ?: "home"})"
            is ShellRoute.Conversation ->
                "Conversation(pinned=${route.open.pinnedAccountRef != null}, " +
                    "notification=${route.open.notificationOpenRequestId}, " +
                    "trace=${route.open.notificationRouteTraceRequestId}, " +
                    "focus=${route.open.focusMessageId != null}/${route.open.focusMessageRequestId})"
        }
    }
