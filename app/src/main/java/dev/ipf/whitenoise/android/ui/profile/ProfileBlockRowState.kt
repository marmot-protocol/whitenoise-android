package dev.ipf.whitenoise.android.ui.profile

/** Block row state: MDK's confirmed block state for the target, and whether a request is in flight. */
internal data class ProfileBlockRowState(
    val blocked: Boolean,
    val inProgress: Boolean,
    val enabled: Boolean,
)

internal const val PROFILE_BLOCK_ACTION_TAG = "person_profile.block"
