package dev.ipf.whitenoise.android.ui.profile

/**
 * Per-member group mute row (#2782): the stored device preference for this member in this group.
 *
 * Present only for a non-self member whose profile was opened from inside a group conversation.
 * The preference is a local read with no request behind it, so the row has no loading state to
 * settle into — it opens on its final value and flips as soon as a toggle is stored.
 */
internal data class ProfileMemberMuteRowState(
    val muted: Boolean,
    val enabled: Boolean,
)

internal const val PROFILE_MEMBER_MUTE_ACTION_TAG = "person_profile.mute_in_group"
