package dev.ipf.whitenoise.android.ui.onboarding.setup

import dev.ipf.marmotkit.OnboardingIssueFfi
import dev.ipf.marmotkit.OnboardingSnapshotFfi
import kotlinx.coroutines.flow.MutableStateFlow

/** A bounded, owned nonpublishing preview followed by one native proposal command. */
internal class AccountSetupRelayRemoval(
    private val client: AccountSetupClient,
    private val account: String,
    private val isCurrent: () -> Boolean,
    private val state: MutableStateFlow<AccountSetupState>,
    private val accept: (OnboardingSnapshotFfi) -> Unit,
) {
    /** Rereads both before preview creation and before the later proposal command. */
    suspend fun prepare(
        request: SetupRequest,
        displayed: OnboardingSnapshotFfi,
        endpoint: String,
    ) {
        val latest = client.snapshot()
        if (latest == null || !latest.matchesRenderedRelayRemoval(displayed, request, endpoint, account)) {
            latest?.let(accept)
            state.value = state.value.copy(staleDecision = true)
            return
        }
        val discarded =
            client.installRelayPreview(request.step) { preview ->
                val visible = state.value.snapshot
                val owned =
                    isCurrent() &&
                        preview.matchesRelayEditorLoad(account, request, visible) &&
                        visible?.sameRemovalSource(displayed) == true
                if (owned) {
                    val editor = preview.relayEditor(request)
                    accept(preview)
                    state.value = state.value.copy(editor = editor, relayRemovalIssue = null)
                }
                owned
            }
        if (discarded != null) {
            accept(discarded)
            state.value = state.value.copy(staleDecision = true)
            return
        }
        finish(endpoint)
    }

    /** Keeps unsafe or incomplete edits local, with no proposal or publication. */
    private suspend fun finish(endpoint: String) {
        val preview = state.value.snapshot ?: return
        val editor = state.value.editor ?: return
        when {
            !editor.canEditRelayDeclaration ->
                state.value = state.value.copy(relayRemovalIssue = RelayRemovalIssue.UNSAFE_DECLARATION)
            endpoint !in editor.reads.lines() && endpoint !in editor.writes.lines() ->
                abandon(preview, null)
            else -> finishStaged(preview, editor, endpoint)
        }
    }

    private suspend fun finishStaged(
        preview: OnboardingSnapshotFfi,
        editor: SetupEditor,
        endpoint: String,
    ) {
        val staged = editor.withoutRelayEndpoint(endpoint)
        state.value = state.value.copy(editor = staged)
        if (!staged.canReviewRelayEdit) {
            state.value = state.value.copy(relayRemovalIssue = RelayRemovalIssue.ADD_REQUIRED_RELAY)
        } else {
            val fresh = client.snapshot()
            val visible = state.value.snapshot
            val nativeOwned = fresh != null && preview.isCurrentRelayPreview(fresh)
            val visibleOwned = visible != null && preview.isCurrentRelayPreview(visible)
            val grantCurrent = fresh?.matchesDecision(account, staged.request()) == true
            val previewCurrent = nativeOwned && visibleOwned
            val ownerAndGrantCurrent = isCurrent() && grantCurrent
            if (!previewCurrent || !ownerAndGrantCurrent) {
                abandon(preview, fresh)
            } else {
                client.execute(staged.request())?.let { proposal ->
                    accept(proposal)
                    state.value = state.value.copy(editor = null, relayRemovalIssue = null, removalReview = true)
                }
            }
        }
    }

    /** Discards only the still-owned preview; a newer checkpoint wins. */
    private suspend fun abandon(
        preview: OnboardingSnapshotFfi,
        latest: OnboardingSnapshotFfi?,
    ) {
        client.discardRelayPreview(preview)?.let(accept)
        latest?.let(accept)
        state.value = state.value.copy(editor = null, staleDecision = true)
    }
}

/** A one-tap edit must still target the displayed native finding and unchanged recovery attempt. */
private fun OnboardingSnapshotFfi.matchesRenderedRelayRemoval(
    displayed: OnboardingSnapshotFfi,
    request: SetupRequest,
    endpoint: String,
    account: String,
): Boolean {
    val endpointCurrent =
        steps.firstOrNull { it.step == request.step }?.findings?.any {
            it.issue == OnboardingIssueFfi.RETIRED_RELAY && it.endpoint == endpoint
        } == true
    return matchesDecision(account, request) && sameRemovalSource(displayed) && endpointCurrent
}

private fun OnboardingSnapshotFfi.sameRemovalSource(displayed: OnboardingSnapshotFfi): Boolean {
    val sameAccount = accountIdHex == displayed.accountIdHex
    val sameAttempt = revision == displayed.revision && recoveryEpoch == displayed.recoveryEpoch
    return sameAccount && sameAttempt && proposal == displayed.proposal
}
