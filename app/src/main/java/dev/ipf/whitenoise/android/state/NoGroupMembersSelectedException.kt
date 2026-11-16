package dev.ipf.whitenoise.android.state

import dev.ipf.marmotkit.MemberResolutionOutcomeFfi

/** MDK resolved the selection without creating a group or publishing an invite. */
internal class NoGroupMembersSelectedException(
    val outcomes: List<MemberResolutionOutcomeFfi>,
) : IllegalStateException("No selected recipients were available")
