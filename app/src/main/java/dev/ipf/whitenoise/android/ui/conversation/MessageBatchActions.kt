package dev.ipf.whitenoise.android.ui.conversation

import dev.ipf.marmotkit.AppMessageRecordFfi
import dev.ipf.whitenoise.android.core.DiagnosticFormatter
import dev.ipf.whitenoise.android.core.MessageProjector
import dev.ipf.whitenoise.android.state.MessageStatus
import dev.ipf.whitenoise.android.state.rethrowIfCancellation
import dev.ipf.whitenoise.android.ui.conversation.composer.ComposerGate
import java.util.Locale

/** UI-ready projection of one selected, user-visible message in timeline order. */
internal data class BatchMessageActionItem(
    val messageId: String,
    val senderId: String,
    val senderDisplayName: String,
    val copyableText: String?,
    val forwardableText: String?,
    /** True when the message has at least one attachment the single-message save path can persist. */
    val hasSaveableMedia: Boolean = false,
    /**
     * Whether this message can be removed for the whole group — the authored
     * message, or another member's message the selecting user may moderate
     * (group admin, never a direct conversation). Derived from the same
     * `deleteCapabilityFor` the single-message surface and the mutation guard
     * use, so batch routing never diverges from the per-message policy.
     * Messages without it can only be hidden on this device.
     */
    val canDeleteForEveryone: Boolean,
)

/**
 * Which removal the user picked in the batch confirm. [EVERYONE] removes each
 * message for the whole group where the user is allowed to and hides the rest
 * on this device; [LOCAL_ONLY] hides every selected message on this device and
 * publishes nothing.
 */
internal enum class BatchDeleteScope {
    EVERYONE,
    LOCAL_ONLY,
}

/** Selection snapshot retained independently of the controller's bounded timeline window. */
internal data class BatchMessageSelection(
    val action: BatchMessageActionItem,
    val record: AppMessageRecordFfi,
    val status: MessageStatus,
    val timelineOrder: ULong,
)

internal data class BatchDeleteBreakdown(
    val deleteForEveryone: Int,
    val hideLocally: Int,
) {
    /** At least one selected message can be removed for the whole group. */
    val canOfferDeleteForEveryone: Boolean get() = deleteForEveryone > 0
}

internal enum class BatchDeleteOperationKind {
    DeleteForEveryone,
    HideLocally,
}

internal data class BatchDeleteAttempt(
    val selection: BatchMessageSelection,
    val operation: BatchDeleteOperationKind,
)

internal enum class BatchDeleteFailureCategory {
    PermissionDenied,
    Connectivity,
    ResourceBusy,
    NotFound,
    PlatformUnavailable,
    InvalidInput,
    CryptoFailure,
    Io,
    Timeout,
    Unexpected,
    ;

    companion object {
        fun from(throwable: Throwable): BatchDeleteFailureCategory =
            when (DiagnosticFormatter.errorCode(throwable)) {
                "PERMISSION_DENIED" -> PermissionDenied
                "CONNECTIVITY" -> Connectivity
                "RESOURCE_BUSY" -> ResourceBusy
                "NOT_FOUND" -> NotFound
                "PLATFORM_UNAVAILABLE" -> PlatformUnavailable
                "INVALID_INPUT" -> InvalidInput
                "CRYPTO_FAILURE" -> CryptoFailure
                "IO" -> Io
                "TIMEOUT" -> Timeout
                else -> Unexpected
            }
    }
}

internal data class BatchDeleteOperationOutcome(
    val attempt: BatchDeleteAttempt,
    val failure: BatchDeleteFailureCategory? = null,
) {
    val succeeded: Boolean get() = failure == null
}

internal data class BatchDeleteResult(
    val outcomes: List<BatchDeleteOperationOutcome>,
) {
    val attempted: Int get() = outcomes.size
    val succeeded: Int get() = outcomes.count(BatchDeleteOperationOutcome::succeeded)
    val failures: List<BatchDeleteOperationOutcome> get() = outcomes.filterNot(BatchDeleteOperationOutcome::succeeded)
    val failedAttempts: List<BatchDeleteAttempt> get() = failures.map(BatchDeleteOperationOutcome::attempt)
}

/** Conversation-scoped retry state; only failed operations remain actionable. */
internal data class BatchDeleteRetryState(
    val originalAttempts: List<BatchDeleteAttempt>,
    val failures: List<BatchDeleteOperationOutcome>,
) {
    val attempted: Int get() = originalAttempts.size
    val succeeded: Int get() = attempted - failures.size
    val failedAttempts: List<BatchDeleteAttempt> get() = failures.map(BatchDeleteOperationOutcome::attempt)
    val failedLocalHides: Int
        get() = failures.count { it.attempt.operation == BatchDeleteOperationKind.HideLocally }
    val failedGroupDeletes: Int
        get() = failures.count { it.attempt.operation == BatchDeleteOperationKind.DeleteForEveryone }

    fun afterRetry(result: BatchDeleteResult): BatchDeleteRetryState {
        val retriedKeys = result.outcomes.mapTo(mutableSetOf(), BatchDeleteOperationOutcome::retryKey)
        val retained = failures.filterNot { it.retryKey() in retriedKeys }
        return copy(failures = retained + result.failures)
    }

    companion object {
        fun from(result: BatchDeleteResult): BatchDeleteRetryState =
            BatchDeleteRetryState(
                originalAttempts = result.outcomes.map(BatchDeleteOperationOutcome::attempt),
                failures = result.failures,
            )
    }
}

/** Recomposition-stable single-flight gate for initial submissions and retries. */
internal class BatchDeleteSubmissionGuard {
    private var inFlight = false

    fun tryStart(): Boolean {
        if (inFlight) return false
        inFlight = true
        return true
    }

    fun finish() {
        inFlight = false
    }
}

private fun BatchDeleteOperationOutcome.retryKey(): Pair<String, BatchDeleteOperationKind> = attempt.selection.action.messageId to attempt.operation

internal fun batchDeleteDiagnosticReport(state: BatchDeleteRetryState): String =
    buildString {
        appendLine("White Noise batch delete report")
        appendLine("operation=MESSAGE_BATCH_DELETE")
        appendLine("attempted=${state.attempted}")
        appendLine("succeeded=${state.succeeded}")
        appendLine("failed=${state.failures.size}")
        BatchDeleteOperationKind.entries.forEach { operation ->
            val code = operation.name.toBatchDeleteDiagnosticCode()
            val attempted = state.originalAttempts.count { it.operation == operation }
            val failed = state.failures.count { it.attempt.operation == operation }
            appendLine("$code.attempted=$attempted")
            appendLine("$code.failed=$failed")
        }
        state.failures
            .groupingBy { it.attempt.operation to requireNotNull(it.failure) }
            .eachCount()
            .toSortedMap(compareBy({ it.first.name }, { it.second.name }))
            .forEach { (key, count) ->
                appendLine(
                    "${key.first.name.toBatchDeleteDiagnosticCode()}." +
                        "${key.second.name.toBatchDeleteDiagnosticCode()}=$count",
                )
            }
    }.trimEnd().take(MAX_BATCH_DELETE_REPORT_LENGTH)

private const val MAX_BATCH_DELETE_REPORT_LENGTH = 600
private val DIAGNOSTIC_CODE_BOUNDARY = Regex("([a-z0-9])([A-Z])")

private fun String.toBatchDeleteDiagnosticCode(): String = replace(DIAGNOSTIC_CODE_BOUNDARY, "\$1_\$2").uppercase(Locale.ROOT)

internal fun isBatchSelectableMessage(
    messageId: String,
    userVisibleMessage: Boolean,
    committedMessage: Boolean,
    projectedDeleted: Boolean,
    deletedMessageIds: Set<String>,
): Boolean =
    messageId.isNotBlank() &&
        userVisibleMessage &&
        committedMessage &&
        !projectedDeleted &&
        messageId !in deletedMessageIds

internal fun batchForwardSheetOpenForBodies(
    currentlyOpen: Boolean,
    forwardBodies: List<String>,
): Boolean = currentlyOpen && forwardBodies.isNotEmpty()

internal fun reconcileBatchSelections(
    selected: Map<String, BatchMessageSelection>,
    selectableVisible: Map<String, BatchMessageSelection>,
    deletedMessageIds: Set<String>,
    invalidVisibleMessageIds: Set<String>,
): Map<String, BatchMessageSelection> =
    buildMap {
        selected.forEach { (messageId, snapshot) ->
            when {
                messageId in deletedMessageIds || messageId in invalidVisibleMessageIds -> Unit
                messageId in selectableVisible -> put(messageId, selectableVisible.getValue(messageId))
                else -> put(messageId, snapshot)
            }
        }
    }

internal fun orderedBatchSelections(selections: Collection<BatchMessageSelection>): List<BatchMessageSelection> =
    selections.sortedWith(
        compareBy<BatchMessageSelection> { it.record.recordedAt }
            .thenBy { it.timelineOrder }
            .thenBy { it.action.messageId },
    )

internal fun batchCopyText(items: List<BatchMessageActionItem>): String {
    val copyable =
        items.mapNotNull { item ->
            val text = item.copyableText?.trim()?.takeIf { it.isNotEmpty() } ?: return@mapNotNull null
            item to text
        }
    val multipleSenders = copyable.map { (item, _) -> item.senderId.lowercase() }.distinct().size > 1
    return copyable.joinToString("\n") { (item, text) ->
        if (multipleSenders) {
            "${item.senderDisplayName.ifBlank { item.senderId }}: $text"
        } else {
            text
        }
    }
}

internal fun batchForwardBodies(items: List<BatchMessageActionItem>): List<String> =
    MessageProjector.validatedForwardTextBodies(items.map(BatchMessageActionItem::forwardableText))

internal fun batchDeleteBreakdown(items: List<BatchMessageActionItem>): BatchDeleteBreakdown =
    BatchDeleteBreakdown(
        deleteForEveryone = items.count(BatchMessageActionItem::canDeleteForEveryone),
        hideLocally = items.count { !it.canDeleteForEveryone },
    )

internal fun batchDeleteAttempts(
    selections: List<BatchMessageSelection>,
    scope: BatchDeleteScope,
): List<BatchDeleteAttempt> =
    selections.map { selection ->
        BatchDeleteAttempt(
            selection = selection,
            operation =
                if (scope == BatchDeleteScope.EVERYONE && selection.action.canDeleteForEveryone) {
                    BatchDeleteOperationKind.DeleteForEveryone
                } else {
                    BatchDeleteOperationKind.HideLocally
                },
        )
    }

internal data class BatchSelectionActionAvailability(
    val canCopy: Boolean,
    val canForward: Boolean,
    val canSave: Boolean,
    val canReply: Boolean,
    val canInfo: Boolean,
    val canDelete: Boolean,
)

internal fun batchSelectionActionAvailability(
    items: List<BatchMessageActionItem>,
    composerGate: ComposerGate,
): BatchSelectionActionAvailability {
    if (items.isEmpty()) {
        return BatchSelectionActionAvailability(
            canCopy = false,
            canForward = false,
            canSave = false,
            canReply = false,
            canInfo = false,
            canDelete = false,
        )
    }
    val forwardBodies = batchForwardBodies(items)
    val single = items.size == 1
    return BatchSelectionActionAvailability(
        canCopy = items.all { !it.copyableText.isNullOrBlank() },
        canForward = forwardBodies.isNotEmpty(),
        canSave = items.all(BatchMessageActionItem::hasSaveableMedia),
        canReply = single && composerGate == ComposerGate.COMPOSER,
        canInfo = single,
        canDelete = true,
    )
}

internal enum class MessageSelectionBarAction {
    Reply,
    Info,
    Copy,
    Forward,
    Save,
}

internal data class MessageSelectionBarRow(
    val actions: List<MessageSelectionBarAction>,
    val includesDelete: Boolean,
)

@Suppress("MaxLineLength")
internal fun offeredMessageSelectionBarActions(availability: BatchSelectionActionAvailability): List<MessageSelectionBarAction> =
    buildList {
        add(MessageSelectionBarAction.Copy)
        add(MessageSelectionBarAction.Forward)
        if (availability.canReply) add(MessageSelectionBarAction.Reply)
        if (availability.canInfo) add(MessageSelectionBarAction.Info)
        add(MessageSelectionBarAction.Save)
    }

internal fun messageSelectionBarActionRows(
    offered: List<MessageSelectionBarAction>,
    maxActionsPerRow: Int,
): List<MessageSelectionBarRow> {
    val slotsPerRow = maxActionsPerRow.coerceAtLeast(1)
    return buildList {
        if (offered.isEmpty()) {
            add(MessageSelectionBarRow(actions = emptyList(), includesDelete = true))
            return@buildList
        }
        var index = 0
        while (index < offered.size) {
            val take = minOf(slotsPerRow, offered.size - index)
            if (index + take >= offered.size) {
                if (take < slotsPerRow) {
                    add(
                        MessageSelectionBarRow(
                            actions = offered.subList(index, index + take),
                            includesDelete = true,
                        ),
                    )
                } else {
                    add(MessageSelectionBarRow(actions = offered.subList(index, index + take), includesDelete = false))
                    add(MessageSelectionBarRow(actions = emptyList(), includesDelete = true))
                }
                return@buildList
            }
            add(MessageSelectionBarRow(actions = offered.subList(index, index + take), includesDelete = false))
            index += take
        }
    }
}

/** Executes the frozen operation kind for each selection and retains a safe per-message outcome. */
internal suspend fun executeBatchDelete(
    attempts: List<BatchDeleteAttempt>,
    deleteForEveryone: suspend (AppMessageRecordFfi) -> Result<Unit>,
    hideLocally: suspend (String) -> Result<Unit>,
    onOutcome: (BatchDeleteOperationOutcome) -> Unit = {},
): BatchDeleteResult {
    val outcomes =
        attempts.map { attempt ->
            val result =
                try {
                    when (attempt.operation) {
                        BatchDeleteOperationKind.DeleteForEveryone ->
                            deleteForEveryone(attempt.selection.record)
                        BatchDeleteOperationKind.HideLocally ->
                            hideLocally(attempt.selection.action.messageId)
                    }
                } catch (throwable: Throwable) {
                    rethrowIfCancellation(throwable)
                    Result.failure(throwable)
                }
            result.exceptionOrNull()?.let(::rethrowIfCancellation)
            BatchDeleteOperationOutcome(
                attempt = attempt,
                failure = result.exceptionOrNull()?.let(BatchDeleteFailureCategory::from),
            ).also(onOutcome)
        }
    return BatchDeleteResult(outcomes)
}
