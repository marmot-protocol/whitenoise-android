package dev.ipf.whitenoise.android.ui.conversation.composer

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import dev.ipf.whitenoise.android.core.GroupProjector
import dev.ipf.whitenoise.android.core.MentionComposer
import dev.ipf.whitenoise.android.state.ConversationController
import dev.ipf.whitenoise.android.state.WhiteNoiseAppState

internal data class ConversationMentionPickerState(
    val enabled: Boolean,
    val candidates: List<MentionComposer.Candidate>,
)

@Composable
internal fun rememberConversationMentionPickerState(
    controller: ConversationController,
    appState: WhiteNoiseAppState,
    requestProfiles: Boolean = true,
): ConversationMentionPickerState {
    val enabled = !controller.isDm
    val memberIds =
        remember(controller.members) {
            controller.members.map { it.memberIdHex }
        }
    LaunchedEffect(enabled, requestProfiles, memberIds) {
        if (enabled && requestProfiles && memberIds.isNotEmpty()) {
            appState.requestProfiles(memberIds)
        }
    }
    val candidates =
        if (enabled) {
            val revision = appState.profileRevisionForCompose
            val activeAccountIdHex = appState.activeAccount?.accountIdHex
            remember(controller.members, revision, activeAccountIdHex) {
                controller.members
                    // Exclude only the active account, not every member flagged
                    // `local`. Marmot marks any identity present on this device
                    // as local; filtering all locals can empty the picker.
                    .filterNot { GroupProjector.isActiveAccountMember(it, activeAccountIdHex) }
                    .map { member ->
                        MentionComposer.Candidate(
                            accountIdHex = member.memberIdHex,
                            npub = appState.npub(member.memberIdHex),
                            displayName = appState.chatMemberTitleCached(member.memberIdHex),
                            nip05 = appState.userProfile(member.memberIdHex)?.nip05,
                            avatarUrl = appState.avatarUrl(member.memberIdHex),
                        )
                    }
            }
        } else {
            emptyList()
        }
    return ConversationMentionPickerState(enabled = enabled, candidates = candidates)
}
