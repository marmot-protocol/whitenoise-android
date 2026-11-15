package dev.ipf.whitenoise.android.state

import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import dev.ipf.marmotkit.MarmotKitException
import dev.ipf.marmotkit.SendSummaryFfi
import dev.ipf.marmotkit.TimelineMessageRecordFfi
import dev.ipf.whitenoise.android.R
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.yield
import java.util.UUID

/**
 * Publish a kind-1009 edit replacing the body of [targetMessageId] with
 * [content]. The runtime enforces the wire-level constraint that the
 * edit's signer matches the original; recipients re-enforce
 * through MDK’s accepted-edit projection. Trim is applied before send so a
 * trailing newline from the composer doesn't change the visible body.
 */
suspend fun ConversationController.editMessage(
    targetMessageId: String,
    content: String,
) {
    val account = conversationAccountRef ?: return
    if (!canSendMessages) return
    val target = targetMessageId.takeIf { it.isNotBlank() } ?: return
    val trimmed = content.trim()
    if (trimmed.isEmpty()) return
    // Apply the new body locally before the publish round-trips so the
    // bubble flips to the edited text at once instead of showing the old
    // text until the kind-1009 echoes back (~1s). Capture the body shown
    // before this edit (a prior optimistic edit's pre-edit text takes
    // priority over the now-stale displayed text) so a failure reverts
    // verbatim. Pending drives a brief sending indicator on the bubble.
    val preEditText = optimisticEdits[target]?.preEditText ?: currentDisplayedText(target)
    optimisticEdits[target] = OptimisticEdit(trimmed, preEditText, MessageStatus.Pending)
    publishTimelineFromIndexes()
    try {
        appState.withGroupCommitLock(account, group.groupIdHex) {
            messageEditPublisher(account, group.groupIdHex, target, trimmed)
        }
        // Publish accepted: drop the Pending indicator but keep the text
        // overlay so the bubble doesn't flicker back to the old body in the
        // gap before the kind-1009 lands in the timeline. The overlay is
        // pruned once MDK reflects the same latest text.
        // Only act if this attempt still owns the overlay: if the user
        // re-edited the same target while this publish was in flight, a
        // newer Pending overlay (different text) has superseded us, and
        // flipping it to Sent would wrongly confirm the newer attempt.
        optimisticEdits[target]
            ?.takeIf { it.status == MessageStatus.Pending && it.text == trimmed }
            ?.let { optimisticEdits[target] = it.copy(status = MessageStatus.Sent) }
        publishTimelineFromIndexes()
    } catch (throwable: Throwable) {
        throwable.rethrowIfCancellation()
        // Revert the displayed body to the pre-edit text and flip the
        // bubble to Failed, lighting the same retry/discard affordance a
        // failed send shows. Retry re-runs this edit; discard clears the
        // overlay and restores the original body. Guarded the same way as
        // the success path: a newer in-flight attempt's overlay must not be
        // clobbered back to this stale attempt's Failed/pre-edit text.
        // MDK refuses an edit of a message this account cannot edit; retrying could never succeed, so the
        // original body returns instead of a Failed overlay with Retry.
        val notEditable = throwable is MarmotKitException.InvalidEditTarget
        optimisticEdits[target]
            ?.takeIf { it.status == MessageStatus.Pending && it.text == trimmed }
            ?.let {
                if (notEditable) {
                    optimisticEdits.remove(target)
                } else {
                    optimisticEdits[target] = OptimisticEdit(trimmed, preEditText, MessageStatus.Failed)
                }
            }
        publishTimelineFromIndexes()
        val detail = if (notEditable) R.string.error_message_not_editable else R.string.error_try_again
        val title = R.string.toast_couldnt_edit_message
        appState.presentFailure(title, "MESSAGE_EDIT", throwable, AppText.Resource(detail))
    }
}

/** Transfers the revision to MDK before dismissing the editor, with immutable tokens for safe retries. */
internal suspend fun ConversationController.admitPendingEdit(
    target: String,
    originalToken: String,
    text: String,
    retryIntent: DurablePendingEditIntent? = null,
    onAttemptStarted: () -> Unit = {},
): Boolean {
    val account = conversationAccountRef ?: return false
    val confirmedTarget = pendingMessageEditHandoff.confirmedTarget(pendingEditKey(target))
    val resolvedTarget =
        confirmedTarget?.takeIf {
            it in timelineRecords || "msg:$it" in optimisticMessages
        } ?: target
    val retained =
        optimisticEdits[resolvedTarget]
            ?.takeIf {
                !it.nativeRevisionRejected &&
                    it.text == text &&
                    it.durableIntent?.originalClientToken == originalToken
            }?.durableIntent
    val intent = retryIntent ?: retained ?: DurablePendingEditIntent(originalToken, UUID.randomUUID().toString())
    return if (!pendingEditAdmissions.add(intent.editClientToken)) {
        false
    } else {
        onAttemptStarted()
        performPendingEdit(account, target, resolvedTarget, intent, text)
    }
}

private suspend fun ConversationController.performPendingEdit(
    account: String,
    target: String,
    resolvedTarget: String,
    intent: DurablePendingEditIntent,
    text: String,
): Boolean {
    val previous = optimisticEdits[resolvedTarget]?.preEditText ?: currentDisplayedText(resolvedTarget)
    optimisticEdits[resolvedTarget] = OptimisticEdit(text, previous, MessageStatus.Pending, intent)
    publishTimelineFromIndexes()
    return try {
        runCatchingCancellable { publishPendingEdit(account, intent, text) }.fold(
            onSuccess = { summary -> completePendingEditAdmission(intent, summary, text) },
            onFailure = { cause ->
                if (intent.editClientToken in pendingEditPublicationObserved) {
                    true
                } else {
                    failPendingEditAdmission(intent, target, account, cause)
                    // A message MDK will never let this account edit ends the edit too, so the editor closes.
                    cause is MarmotKitException.InvalidEditTarget
                }
            },
        )
    } finally {
        pendingEditAdmissions.remove(intent.editClientToken)
        pendingEditPublicationObserved.remove(intent.editClientToken)
        optimisticEdits.entries
            .firstOrNull { it.value.durableIntent == intent }
            ?.key
            ?.let(::recoverPendingEditStatus)
    }
}

private suspend fun ConversationController.publishPendingEdit(
    account: String,
    intent: DurablePendingEditIntent,
    text: String,
) = pendingEditAdmissionMutex.withLock {
    // Preserve this controller's submission order at the local native boundary.
    // Newer UI revisions already own their overlay while this admission waits.
    pendingMessageEditPublisher?.invoke(
        account,
        group.groupIdHex,
        intent.originalClientToken,
        text,
        intent.editClientToken,
    ) ?: appState.marmotIo(MarmotTraceSection.MESSAGE_EDIT) {
        admitPendingMessageEdit(account, group.groupIdHex, intent, text)
    }
}

private fun ConversationController.completePendingEditAdmission(
    intent: DurablePendingEditIntent,
    summary: SendSummaryFfi,
    text: String,
): Boolean {
    val current = optimisticEdits.entries.firstOrNull { it.value.durableIntent == intent }
    if (current != null) {
        optimisticEdits[current.key] = current.value.copy(nativeEditMessageId = summary.messageIds.firstOrNull())
    }
    publishTimelineFromIndexes()
    return current != null ||
        intent.editClientToken in pendingEditPublicationObserved ||
        timelineRecords.values.any { record ->
            record.edit?.latestEditMessageIdHex in summary.messageIds && record.plaintext == text
        }
}

/** Shows a refused pending edit as Failed with Retry, or restores the original text when MDK says it is final. */
private fun ConversationController.failPendingEditAdmission(
    intent: DurablePendingEditIntent,
    target: String,
    account: String,
    cause: Throwable,
) {
    val current = optimisticEdits.entries.firstOrNull { it.value.durableIntent == intent }
    // A message that can no longer be edited gets its original text back; a Retry could never succeed.
    val notEditable = cause is MarmotKitException.InvalidEditTarget
    if (current != null) {
        if (notEditable || optimisticMessages["msg:$target"]?.status == MessageStatus.Failed) {
            optimisticEdits.remove(current.key)
        } else {
            optimisticEdits[current.key] = current.value.copy(status = MessageStatus.Failed)
        }
    }
    publishTimelineFromIndexes()
    if (current != null && appState.activeAccountRef == account) {
        val detail = if (notEditable) R.string.error_message_not_editable else R.string.error_try_again
        val title = R.string.toast_couldnt_edit_message
        appState.presentFailure(title, "MESSAGE_EDIT", cause, AppText.Resource(detail))
    }
}

/** Edit-event delivery can advance without changing the original row's rendered body. */
internal fun ConversationController.recoverPendingEditStatusForProjection(record: TimelineMessageRecordFfi) {
    val targets =
        optimisticEdits
            .filterValues { edit ->
                edit.nativeEditMessageId == record.messageIdHex ||
                    (record.clientToken != null && edit.durableIntent?.editClientToken == record.clientToken)
            }.keys
            .toList()
    recoverPendingEditStatus(record)
    if (record.kind == MESSAGE_EDIT_EVENT_KIND && record.sourceMessageIdHex != null) {
        targets.forEach { target ->
            optimisticEdits[target]?.let { edit ->
                edit.durableIntent
                    ?.editClientToken
                    ?.takeIf { it in pendingEditAdmissions }
                    ?.let(pendingEditPublicationObserved::add)
                optimisticEdits[target] =
                    edit.copy(
                        status = MessageStatus.Sent,
                        nativeEditMessageId = record.messageIdHex,
                    )
            }
        }
        if (targets.isNotEmpty()) publishTimelineFromIndexes()
    } else {
        targets.mapNotNull(timelineRecords::get).forEach(::recoverPendingEditStatus)
    }
}

/** Recover native engine ownership or terminal rejection when the target reprojects. */
private fun ConversationController.recoverPendingEditStatus(record: TimelineMessageRecordFfi) {
    recoverPendingEditStatus(record.messageIdHex)
}

private fun ConversationController.recoverPendingEditStatus(target: String) {
    val edit = optimisticEdits[target]
    val intent = edit?.durableIntent
    val account = conversationAccountRef
    if (edit == null || intent == null || account == null) return
    val unresolvedFailure = edit.status == MessageStatus.Failed && !edit.nativeRevisionRejected
    val shouldRecover = !controllerCleared && (edit.status == MessageStatus.Pending || unresolvedFailure)
    if (!shouldRecover || intent.editClientToken in pendingEditAdmissions) {
        return
    }
    if (!pendingEditStatusReads.add(intent.editClientToken)) {
        pendingEditStatusRechecks.add(intent.editClientToken)
    } else {
        controllerScope.launch { readPendingEditStatus(account, intent) }
    }
}

private suspend fun ConversationController.readPendingEditStatus(
    account: String,
    intent: DurablePendingEditIntent,
) {
    try {
        val status =
            runCatchingCancellable {
                pendingEditStatusReader(account, group.groupIdHex, intent.editClientToken)
            }.getOrNull()
        if (controllerCleared) return
        val current =
            optimisticEdits.entries.firstOrNull {
                it.value.durableIntent == intent && it.value.status != MessageStatus.Sent
            }
        if (current != null) {
            optimisticEdits[current.key] = current.value.withNativeEditStatus(status)
            publishTimelineFromIndexes()
        }
    } finally {
        pendingEditStatusReads.remove(intent.editClientToken)
        // Coalesce native events received during the read, including identical rows.
        if (pendingEditStatusRechecks.remove(intent.editClientToken) && !controllerCleared) {
            optimisticEdits.entries
                .firstOrNull { it.value.durableIntent == intent }
                ?.key
                ?.let(::recoverPendingEditStatus)
        }
    }
}

internal fun ConversationController.handoffPendingMessageEdit(
    clientToken: String,
    confirmedId: String,
    ready: Boolean,
) {
    val queuedText = pendingMessageEditHandoff.confirm(pendingEditKey(clientToken), confirmedId, ready)
    if (!ready || confirmedId == clientToken) return
    optimisticEdits.remove(clientToken)?.let { edit ->
        // An edit already submitted against the confirmed row is newer than the temporary overlay.
        if (confirmedId !in optimisticEdits) optimisticEdits[confirmedId] = edit
    }
    if (queuedText != null) {
        // Projection installation can still be mid-batch here. Resume on
        // the next main turn before publishing and rebuilding the timeline.
        appState.launchMutation {
            yield()
            editMessage(confirmedId, queuedText)
        }
    }
}

/**
 * Body shown for [targetId] before any in-flight optimistic edit: the
 * latest confirmed kind-1009 text if one exists, otherwise the message's
 * original plaintext. Captured as an optimistic edit's revert target.
 */
internal fun ConversationController.currentDisplayedText(targetId: String): String =
    editsByTarget[targetId]?.latestText
        ?: messageById[targetId]?.plaintext
        ?: optimisticMessages["msg:$targetId"]?.record?.plaintext
        ?: ""
