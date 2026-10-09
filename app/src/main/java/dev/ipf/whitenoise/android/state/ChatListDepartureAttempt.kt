package dev.ipf.whitenoise.android.state

import dev.ipf.marmotkit.AppGroupMemberRecordFfi

/** Captured invocation state only; native membership is re-read before each departure or retry. */
internal class ChatListDepartureAttempt(
    val account: String,
    val groupId: String,
    val activeAccountId: String?,
    val isCurrent: () -> Boolean,
    val onStage: (ChatDepartureStage) -> Unit,
) {
    var members = emptyList<AppGroupMemberRecordFfi>()
    var cleanupOnly = false
    var demoted = false
    var soleMemberResult: Boolean? = null
}
