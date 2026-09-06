package dev.ipf.whitenoise.android.ui.conversation.composer

import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue

/**
 * The four things the conversation bottom bar can render for the membership
 * gate. Pulled out of Compose as a pure decision so [conversationComposerGate]
 * can be unit-tested and pinned against regression (issues #545, #623, and
 * #802).
 */
internal enum class ComposerGate {
    /** Active composer — self is (believed to be) a member. */
    COMPOSER,

    /** "You are no longer a member of this group" notice. */
    NOTICE,

    /** Pending invite preview — read-only transcript with explicit Join/Decline. */
    INVITE,

    /** MDK froze this local group copy until verified repair completes. */
    FROZEN,

    /**
     * A disband is converging or has landed. The engine gates all ordinary
     * outbound group work either way, so the composer yields to a notice.
     */
    DISBANDED,

    /**
     * Membership is not yet known locally: render NOTHING this frame and wait
     * for `refreshMembers()` rather than flash a wrong state. See [PENDING] use
     * in [conversationComposerGate].
     */
    PENDING,
}

/**
 * Decide what the conversation bottom bar renders for the membership gate,
 * given only what is known synchronously at (and shortly after) first paint.
 *
 * Pending invites are a separate, explicit state: opening the conversation must
 * not auto-accept the MLS welcome and must not expose the live composer until
 * the user taps Join group (#802). For already-joined or left groups, the older
 * membership flash rules still apply:
 *
 * - Confirmed member (`isSelfMember`) → [ComposerGate.COMPOSER].
 * - Confirmed not-member (`membersLoaded && !isSelfMember`) → [ComposerGate.NOTICE].
 * - Still loading (`!membersLoaded`):
 *   - A projected or cached membership signal was present
 *     (`seededMembershipKnown`) → it is authoritative locally: self is a
 *     member (`seededSelfMember`) →
 *     [ComposerGate.COMPOSER] (warm member, no blank-bar flash, preserving the
 *     #264 intent); self was removed from it (the left group) →
 *     [ComposerGate.NOTICE] immediately (the #545 fix).
 *   - No seeding signal at all (genuinely cold open) → membership is unknown,
 *     so [ComposerGate.PENDING]: render neither the composer nor the notice
 *     until `refreshMembers()` confirms. This removes the #623 notice-flash for
 *     a member opening cold without reintroducing the #545 composer-flash for a
 *     left group.
 *
 * This drives only the INITIAL VISUAL state. Text sends and reactions
 * separately allow a positively seeded current member to hand work off during
 * refresh; membership and administrative mutations still require verification.
 */
internal fun conversationComposerGate(
    pendingInvite: Boolean,
    inviteAcceptanceResolutionPending: Boolean = false,
    membersVerified: Boolean,
    isSelfMember: Boolean,
    seededSelfMember: Boolean,
    seededMembershipKnown: Boolean,
    assumeMemberUntilVerified: Boolean,
    unrecoverable: Boolean = false,
    disbanding: Boolean = false,
    disbanded: Boolean = false,
): ComposerGate =
    when {
        // The most specific terminal state wins: a disbanded group is done by
        // design, not broken.
        disbanded || disbanding -> ComposerGate.DISBANDED
        unrecoverable -> ComposerGate.FROZEN
        pendingInvite -> ComposerGate.INVITE
        inviteAcceptanceResolutionPending -> ComposerGate.PENDING
        isSelfMember -> ComposerGate.COMPOSER
        // Removed-member notice only once refreshMembers() has VERIFIED the
        // roster. An unverified roster that merely omits self — e.g. a stale or
        // cross-account snapshot right after tapping another account's
        // notification — must not flash the notice; wait instead.
        membersVerified -> ComposerGate.NOTICE
        seededMembershipKnown && seededSelfMember -> ComposerGate.COMPOSER
        // Opened from a message notification: receiving a message for a group
        // implies current membership, so show the composer immediately rather
        // than a placeholder while verification catches up. A genuine removal
        // still wins above via membersVerified once refreshMembers() confirms.
        assumeMemberUntilVerified -> ComposerGate.COMPOSER
        else -> ComposerGate.PENDING
    }

/**
 * Decide whether to restore composer focus (and thus re-raise the soft
 * keyboard) when the conversation returns to the foreground (issue #589).
 *
 * Case B of #589: switching away with the keyboard CLOSED and then returning
 * must NOT pop the keyboard open — Android/Compose otherwise restores the
 * `BasicTextField` focus and IME visibility on its own. We only re-request
 * focus on resume when the composer actually held focus when we were paused,
 * so the post-resume keyboard state matches the pre-switch state exactly.
 *
 * An active edit or reply session is treated as focus-owning even if the raw
 * focus flag briefly lagged behind on pause: those sessions deliberately raise
 * the keyboard (see the edit/reply focus effects), so returning to them with
 * the keyboard down would be just as surprising as Case A. The caller still
 * gates the actual `requestFocus()` on this predicate so the decision stays in
 * one pure, unit-tested place.
 */
internal fun shouldRestoreComposerFocusOnResume(
    wasComposerFocusedOnPause: Boolean,
    hasActiveEditOrReplySession: Boolean = false,
): Boolean = wasComposerFocusedOnPause || hasActiveEditOrReplySession

/**
 * Whether the resume observer should actively clear focus and hide the keyboard
 * (issue #589, Case B "keyboard was closed on leave").
 *
 * This is NOT the inverse of [shouldRestoreComposerFocusOnResume]. The clear/hide
 * branch uses the screen-wide [androidx.compose.ui.focus.FocusManager], so it must
 * not fire whenever *some other* text field legitimately owns focus and the
 * keyboard. In-chat search (#292) is exactly that case: while the search bar is
 * open the composer is not focused (so [shouldRestoreComposerFocusOnResume] is
 * false), but the search field holds focus and the IME is up on purpose. Clearing
 * focus here would drop the search field's focus and hide its keyboard, and
 * `LaunchedEffect(searchOpen)` would not re-fire on resume to restore it —
 * regressing the search UX after an app-switch.
 *
 * So: clear focus only when we are not restoring composer focus AND no other
 * text field (currently just in-chat search) owns the focus/IME.
 */
internal fun shouldClearFocusOnResume(
    restoringComposerFocus: Boolean,
    searchOpen: Boolean,
): Boolean = !restoringComposerFocus && !searchOpen
