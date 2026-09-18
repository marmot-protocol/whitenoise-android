package dev.ipf.whitenoise.android.state

import dev.ipf.marmotkit.TimelinePageFfi

/**
 * Why a window command left the installed window where it was.
 *
 * MDK's window contract is that none of these carry detail the app can act on beyond deciding
 * whether to wait, retry or tell the reader — the newest installed replacement stays authoritative
 * either way. Keeping the reason lets a caller separate "ask again shortly" from "the engine did
 * not answer in time", which the page-shaped call's bare fallback could not.
 */
internal enum class ConversationWindowUnchangedReason {
    /** MDK is repairing read state and retrying accepted work; asking again shortly can succeed. */
    NOT_READY,

    /** The command did not complete before the window deadline. */
    TIMED_OUT,

    /** A newer replacement, a foreign generation or an out-of-window anchor overtook this command. */
    SUPERSEDED,

    /** The window is closed, or answered with an error the app cannot retry. */
    TERMINAL,

    /** No replacement has been installed yet, so there is no revision to quote. */
    NO_WINDOW,
}

/**
 * One window command's result: the page of a newer installed replacement, or why nothing moved.
 *
 * Every window command produces this shape — paging, anchoring, jumping and returning to the tail
 * all either install a newer replacement or leave the current one standing.
 */
internal sealed interface TimelinePageOutcome {
    /** A newer replacement was installed and [page] is the new authoritative window. */
    data class Advanced(
        val page: TimelinePageFfi,
    ) : TimelinePageOutcome

    /** Nothing was installed; [current] is what the handle still holds, null before any install. */
    data class Unchanged(
        val reason: ConversationWindowUnchangedReason,
        val current: TimelinePageFfi?,
    ) : TimelinePageOutcome
}

/**
 * The authoritative window after this outcome: the newly installed page, or the one the handle still
 * holds. Empty only when a command was refused before any replacement had been installed.
 */
internal fun TimelinePageOutcome.pageOrCurrent(): TimelinePageFfi =
    when (this) {
        is TimelinePageOutcome.Advanced -> page
        is TimelinePageOutcome.Unchanged -> current ?: TimelinePageFfi(emptyList(), false, false)
    }
