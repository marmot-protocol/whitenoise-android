package dev.ipf.whitenoise.android.qualification

import dev.ipf.marmotkit.MarmotInterface
import dev.ipf.whitenoise.android.state.requireCreatedGroupId

/** Returns the created group ID using this runtime's result shape. */
internal suspend fun MarmotInterface.createQualificationGroup(
    accountRef: String,
    groupName: String,
    members: List<String>,
    description: String?,
): String = createGroup(accountRef, groupName, members, description).requireCreatedGroupId()
