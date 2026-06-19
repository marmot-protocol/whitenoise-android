package dev.ipf.darkmatter.core

import kotlin.math.abs

/**
 * Pure gesture math for the chat-list swipe-to-archive axis-lock (#296).
 *
 * Scrolling the chat list vertically used to register an incidental
 * sideways component as a swipe-to-archive. The composable in
 * `DarkMatterApp.kt` observes the drag on the pointer-input Initial pass
 * and delegates the directional-dominance decision here so it can be
 * unit-tested without a Compose harness — same split as [ReplySwipe].
 */
object ArchiveSwipe {
    /**
     * Whether cumulative travel `(dx, dy)` from pointer-down has moved
     * far enough on either axis for the dominant-axis decision to be
     * made. Below the slop the gesture is still ambiguous finger jitter.
     */
    fun axisDecided(
        dx: Float,
        dy: Float,
        slopPx: Float,
    ): Boolean = abs(dx) >= slopPx || abs(dy) >= slopPx

    /**
     * Whether a drag of cumulative travel `(dx, dy)` should be allowed to
     * drive the archive swipe. Direction sign is irrelevant — only axis
     * magnitudes matter — so this works identically in LTR and RTL.
     *
     * Horizontal travel must dominate vertical by BOTH:
     *  - a ratio: `|dx| >= |dy| * ratio`, and
     *  - an absolute lead: `|dx| - |dy| >= minLeadPx`.
     *
     * The absolute lead guards the near-origin case where a tiny `dx`
     * satisfies the ratio against a tiny `dy` (e.g. 4px vs 1px) but is
     * plainly not a deliberate swipe.
     */
    fun shouldEngageSwipe(
        dx: Float,
        dy: Float,
        ratio: Float,
        minLeadPx: Float,
    ): Boolean {
        val horizontal = abs(dx)
        val vertical = abs(dy)
        return horizontal >= vertical * ratio && (horizontal - vertical) >= minLeadPx
    }

    /**
     * Whether a drag of cumulative travel `(dx, dy)` is a clear vertical
     * scroll that should permanently lock the swipe gesture out for the
     * rest of this pointer interaction (the LazyColumn scroll wins).
     *
     * This is the mirror of [shouldEngageSwipe] with the axes swapped:
     * vertical travel must dominate horizontal by BOTH the [ratio] and
     * an absolute [minLeadPx] lead. It is the only *terminal* lock-out
     * condition — until it is met the gesture stays ambiguous and the
     * swipe box remains enabled, so an ordinary incremental horizontal
     * drag (which crosses the small axis slop long before it builds a
     * 24dp horizontal lead) is never killed prematurely. The deliberate
     * half-row positional threshold on the swipe box is what gates an
     * actual archive commit.
     *
     * Direction sign is irrelevant — only axis magnitudes matter — so
     * this behaves identically in LTR and RTL.
     */
    fun shouldLockOutSwipe(
        dx: Float,
        dy: Float,
        ratio: Float,
        minLeadPx: Float,
    ): Boolean {
        val horizontal = abs(dx)
        val vertical = abs(dy)
        return vertical >= horizontal * ratio && (vertical - horizontal) >= minLeadPx
    }
}
