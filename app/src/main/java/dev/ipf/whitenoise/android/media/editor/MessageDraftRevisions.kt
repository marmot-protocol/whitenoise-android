package dev.ipf.whitenoise.android.media.editor

import dev.ipf.marmotkit.MarmotKitException

/** One conditional clear against the currently selected revision, or an unconditional delete on legacy gateways. */
internal fun MessageDraftGateway.deleteAgainstSelected(
    accountRef: String,
    groupIdHex: String,
) {
    val selected = selected(accountRef, groupIdHex) ?: return delete(accountRef, groupIdHex)
    clearIfRevision(accountRef, groupIdHex, selected.revision)
}

/** Clears only while the store still holds [base]'s revision; gateways without one delete unconditionally. */
internal fun MessageDraftGateway.clearRevisioned(
    accountRef: String,
    groupIdHex: String,
    base: RevisionedMessageDraft,
) {
    base.revision?.let { clearIfRevision(accountRef, groupIdHex, it) } ?: delete(accountRef, groupIdHex)
}

/** True when a mutation lost a revision race and should be re-run against the fresh selected draft. */
internal fun MessageDraftMutationResult.isRevisionConflict(): Boolean =
    this is MessageDraftMutationResult.Failure && cause is MarmotKitException.MessageDraftRevisionConflict
