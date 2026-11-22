package dev.ipf.whitenoise.android.state

/**
 * How inviting one profile to several groups went: of [attempted] groups, [failures] failed and in [waited] the user
 * chose to wait on a group-change question, so those groups were not invited either.
 */
internal data class ProfileGroupInviteOutcome(
    val attempted: Int,
    val failures: Int,
    val firstFailure: AppText? = null,
    val waited: Int = 0,
) {
    val delivered: Int = attempted - failures - waited
    val completedSuccessfully: Boolean = attempted > 0 && failures == 0 && waited == 0
}
