package dev.ipf.whitenoise.android.state

import dev.ipf.whitenoise.android.R
import dev.ipf.whitenoise.android.core.MessageProjector
import dev.ipf.whitenoise.android.core.exceedsOutgoingMessageLimit
import kotlinx.coroutines.CancellationException

/**
 * Forward [text] into each of [targetGroupIds] as a fresh send.
 *
 * Each target is an independent [sendText] into that group, so the message
 * is re-encrypted under that group's own MLS state — there is no
 * cross-group key reuse and no source-group key material leaves the origin
 * conversation. The plain body is sent verbatim: it carries neither the
 * original sender's pubkey nor the source group id, so a forward never
 * leaks cross-group attribution (issue #390 privacy notes). The optional
 * receiver-visible "Forwarded" label is deliberately *not* embedded here —
 * the FFI text-send carries no source-free forward marker, so a
 * receiver-visible label would require either an engine change or a content
 * marker that crosses the group boundary; that is a separate product/privacy
 * decision tracked as a follow-up.
 *
 * Sends fan out on [launchMutation] so each MLS commit + Nostr publish
 * survives the picker sheet dismissing immediately after the user confirms.
 * Per-target failures are counted and surfaced in the result toast rather
 * than aborting the remaining targets — one unreachable group must not block
 * delivery to the others. Blank text and an empty target set are no-ops.
 */
fun WhiteNoiseAppState.forwardText(
    targetGroupIds: List<String>,
    text: String,
) {
    val trimmed = text.trim()
    val targets = MessageProjector.normalizeForwardTargets(targetGroupIds)
    // Forward eligibility already blocks these, this keeps any other caller from sending one.
    val tooLong = exceedsOutgoingMessageLimit(trimmed)
    if (tooLong) present(R.string.toast_message_too_long)
    if (trimmed.isEmpty() || targets.isEmpty() || tooLong) return
    val account = activeAccountRef?.takeIf { it.isNotBlank() } ?: return
    launchMutation {
        var failures = 0
        var firstFailure: Throwable? = null
        for (groupIdHex in targets) {
            val result =
                runCatchingCancellable {
                    withGroupCommitLock(account, groupIdHex) {
                        marmotIo(MarmotTraceSection.TEXT_SEND) { sendText(account, groupIdHex, trimmed) }
                    }
                }
            result.onFailure {
                failures += 1
                if (firstFailure == null) firstFailure = it
            }
        }
        val delivered = targets.size - failures
        when {
            failures == 0 ->
                presentTransient(AppText.Resource(R.string.toast_forwarded_to_chats, listOf(delivered)))
            delivered == 0 ->
                presentFailure(R.string.toast_forward_failed, "MESSAGE_FORWARD", requireNotNull(firstFailure))
            else -> {
                val partialTitle =
                    AppText.Resource(
                        R.string.toast_forwarded_partial,
                        listOf("$delivered/${targets.size}"),
                    )
                presentFailure(
                    title = partialTitle,
                    operationCode = "MESSAGE_FORWARD_PARTIAL",
                    throwable = requireNotNull(firstFailure),
                )
            }
        }
    }
}

/**
 * Forward multiple text messages to each target chat in their original
 * timeline order. One target's batch holds the group commit lock for the
 * complete sequence so another mutation cannot interleave between messages.
 */
@Suppress("CyclomaticComplexMethod") // Fan-out accounting keeps partial-success reporting in one transaction.
fun WhiteNoiseAppState.forwardTexts(
    targetGroupIds: List<String>,
    texts: List<String>,
) {
    val bodies = MessageProjector.validatedForwardTextBodies(texts)
    val targets = MessageProjector.normalizeForwardTargets(targetGroupIds)
    val tooLong = bodies.any { exceedsOutgoingMessageLimit(it.trim()) }
    if (tooLong) present(R.string.toast_message_too_long)
    if (bodies.isEmpty() || targets.isEmpty() || tooLong) return
    val account = activeAccountRef?.takeIf { it.isNotBlank() } ?: return
    launchMutation {
        var completeTargets = 0
        var successfulSends = 0
        var firstFailure: Throwable? = null
        for (groupIdHex in targets) {
            var targetComplete = true
            try {
                withGroupCommitLock(account, groupIdHex) {
                    for (body in bodies) {
                        try {
                            marmotIo(MarmotTraceSection.TEXT_SEND) { sendText(account, groupIdHex, body) }
                            successfulSends += 1
                        } catch (throwable: Throwable) {
                            if (throwable is CancellationException) throw throwable
                            if (firstFailure == null) firstFailure = throwable
                            targetComplete = false
                        }
                    }
                }
            } catch (throwable: Throwable) {
                if (throwable is CancellationException) throw throwable
                if (firstFailure == null) firstFailure = throwable
                targetComplete = false
            }
            if (targetComplete) completeTargets += 1
        }
        when {
            completeTargets == targets.size ->
                presentTransient(AppText.Resource(R.string.toast_forwarded_to_chats, listOf(completeTargets)))
            successfulSends == 0 ->
                presentFailure(
                    R.string.toast_forward_batch_failed,
                    "MESSAGE_FORWARD_BATCH",
                    requireNotNull(firstFailure),
                )
            else ->
                presentFailure(
                    R.string.toast_forwarded_batch_partial,
                    "MESSAGE_FORWARD_BATCH_PARTIAL",
                    requireNotNull(firstFailure),
                )
        }
    }
}
