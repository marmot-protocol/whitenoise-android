package dev.ipf.whitenoise.android.state

import android.graphics.Bitmap
import android.os.SystemClock
import android.util.Log
import androidx.compose.ui.graphics.asAndroidBitmap
import dev.ipf.marmotkit.ChatListRowFfi
import dev.ipf.marmotkit.EventsSubscription
import dev.ipf.marmotkit.MarmotEventFfi
import dev.ipf.marmotkit.MarmotInterface
import dev.ipf.marmotkit.NotificationTriggerFfi
import dev.ipf.marmotkit.NotificationUpdateFfi
import dev.ipf.marmotkit.RuntimeMessageReceivedFfi
import dev.ipf.marmotkit.TimelineMessageQueryFfi
import dev.ipf.marmotkit.TimelineMessageRecordFfi
import dev.ipf.whitenoise.android.core.AvatarImageLoader
import dev.ipf.whitenoise.android.core.GroupAvatarImageLoader
import dev.ipf.whitenoise.android.core.ReplyMediaKind
import dev.ipf.whitenoise.android.core.encryptedGroupAvatarCacheKey
import dev.ipf.whitenoise.android.diagnostics.PerformanceDiagnostics
import dev.ipf.whitenoise.android.diagnostics.PerformanceLayer
import dev.ipf.whitenoise.android.diagnostics.PerformanceOperation
import dev.ipf.whitenoise.android.diagnostics.PerformancePhase
import dev.ipf.whitenoise.android.diagnostics.PerformanceResult
import dev.ipf.whitenoise.android.notifications.ActiveConversationCard
import dev.ipf.whitenoise.android.notifications.LocalNotificationContent
import dev.ipf.whitenoise.android.notifications.LocalNotificationFormatter
import dev.ipf.whitenoise.android.notifications.PushWakeDiagnostics
import dev.ipf.whitenoise.android.notifications.PushWakeEvent
import dev.ipf.whitenoise.android.state.GroupInviteNotificationIdentityRefreshStore.RefreshCandidate
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext

private const val NOTIFICATION_RETRY_INITIAL_BACKOFF_MILLIS = 1_000L
private const val NOTIFICATION_CANDIDATE_PAGE_LIMIT = 30u
private const val NOTIFICATION_RECONCILIATION_PAGE_LIMIT = 200u
private const val NOTIFICATION_RECONCILIATION_ATTEMPTS = 3
private const val NOTIFICATION_CANDIDATE_ATTEMPTS = 2
private const val NOTIFICATION_CANDIDATE_TIMEOUT_MILLIS = 1_500L
private const val NOTIFICATION_READ_RECONCILE_FOLLOW_UP_MILLIS = 1_000L
private const val CARD_SNAPSHOT_SLACK_MILLIS = 1_000L
private const val NOTIFICATION_RECONCILIATION_RETRY_MILLIS = 1_000L

/** The Marmot app-event kind of a chat message, the only kind that carries attachments. */
internal const val MARMOT_CHAT_EVENT_KIND = 9uL

/** Converts the runtime's second timestamps for receipt lookups, which work in milliseconds. */
private const val MILLIS_PER_SECOND_LONG = 1_000L
private const val NOTIFICATION_RETRY_MAX_BACKOFF_MILLIS = 60_000L

private fun WhiteNoiseAppState.applyNotificationDisplayNameHint(update: NotificationUpdateFfi) {
    if (update.accountRef != activeAccountRef) return
    val senderIdHex =
        update.sender.accountIdHex
            .trim()
            .takeIf { it.isNotEmpty() }
    val hint = notificationDisplayNameHint(update.sender.displayName)
    if (senderIdHex == null || hint == null) return
    val changed =
        synchronized(profilePresentationLock) {
            if (profilePresentations[senderIdHex]?.displayName != null) {
                false
            } else {
                notificationDisplayNameHints.put(senderIdHex, hint) != hint
            }
        }
    if (changed) {
        profileRevision += 1
        bumpProfileAccountRevision(senderIdHex)
    }
}

internal suspend fun WhiteNoiseAppState.notificationMessageRecord(update: NotificationUpdateFfi) =
    update.messageIdHex?.let { messageId ->
        // A small recent tail; the just-arrived message is within it, and a
        // miss simply falls back to the generic notification body.
        runCatchingCancellable { marmotIo { messages(update.accountRef, update.groupIdHex, 30u, null) } }
            .getOrNull()
            ?.validRawMessage(messageId)
    }

/**
 * Queues durable automatic downloads for every attachment a received message carries, from the receipt
 * pipeline rather than a Compose bubble, so media is locally ready before the reader returns (#3308). Each
 * slot is held to its own type's policy for the receiving account, which need not be the active one, and
 * waits in WorkManager for a network that policy could allow. The worker persists only the lookup identity
 * and re-checks the live policy against MDK's reference when it runs.
 */
private suspend fun WhiteNoiseAppState.scheduleIncomingAttachmentDownloads(update: NotificationUpdateFfi) {
    val messageIdHex = update.messageIdHex
    if (messageIdHex == null || !needsIncomingAttachmentDownload(update)) return
    scheduleReceivedMessageAttachments(update.accountRef, update.groupIdHex, messageIdHex, update.timestampMs)
}

/**
 * The receipt scheduling both receipt sources share, for one received message of [accountRef]'s group
 * [groupIdHex]: a bounded authoritative lookup of its attachments, each queued under its own type's policy.
 */
@Suppress("ReturnCount") // A held account and a failed lookup each end the receipt early.
private suspend fun WhiteNoiseAppState.scheduleReceivedMessageAttachments(
    accountRef: String,
    groupIdHex: String,
    messageIdHex: String,
    receivedAtMillis: Long,
) {
    val policy = automaticAttachmentPolicy(accountRef)
    val wanted = policy.receiptTypes()
    if (wanted.isEmpty()) {
        Log.i(INCOMING_ATTACHMENT_TAG, "attachment_receipt_skipped reason=${policy.receiptSkipReason().reason}")
        return
    }
    // The notification and the runtime event may both report this message; only the first runs its lookup.
    if (!receiptClaims.claim(accountRef, messageIdHex)) return
    val lookup =
        runCatchingCancellable {
            // Both receipt sources share one bound, so a catch-up burst cannot fan out unbounded history reads.
            receiptLookupGate.withPermit {
                receivedMessageAttachments(accountRef, groupIdHex, messageIdHex, receivedAtMillis)
            }
        }.onFailure {
            // A failed lookup releases its claim, so the other source, or a later receipt, can try again.
            receiptClaims.release(accountRef, messageIdHex)
            Log.w(INCOMING_ATTACHMENT_TAG, "attachment_receipt_lookup_failed type=${it.javaClass.simpleName}")
        }.getOrNull() ?: return
    val (eligible, held) = lookup.attachments.partition { it.type in wanted }
    // A slot whose durable enqueue fails, now or when its operation completes, releases the claim, so the other
    // source or a later receipt schedules it again rather than finding the message already handled.
    val releaseClaim = { receiptClaims.release(accountRef, messageIdHex) }
    eligible.forEach {
        enqueueAttachmentDownload(
            it.request,
            networkType = policy.networkType(it.type),
            type = it.type,
            onAutomaticNotQueued = releaseClaim,
        )
    }
    // The claim only stands for a lookup that settled every slot. A held type, or a read the budget cut short,
    // leaves work a later receipt may still schedule once the policy allows it, so that receipt must look again.
    val incomplete = lookup.end == ReceiptLookupEnd.Exhausted || lookup.end == ReceiptLookupEnd.Unavailable
    if (held.isNotEmpty() || incomplete) receiptClaims.release(accountRef, messageIdHex)
    logReceiptScheduling(policy, lookup, eligible, held)
}

/**
 * Logs one receipt's outcome without attachment identity: queued work, the part of it waiting for an allowed
 * network, held types, and how the history read ended. A text message's ordinary read logs nothing.
 */
private fun WhiteNoiseAppState.logReceiptScheduling(
    policy: AutomaticAttachmentPolicy,
    lookup: ReceiptAttachmentLookup,
    eligible: List<IncomingAttachment>,
    held: List<IncomingAttachment>,
) {
    val incomplete = lookup.end == ReceiptLookupEnd.Exhausted || lookup.end == ReceiptLookupEnd.Unavailable
    if (lookup.attachments.isEmpty() && !incomplete) return
    val waiting = eligible.count { policy.hold(it.type) != null }
    val heldTypes =
        held
            .map { it.type.preferenceKey }
            .distinct()
            .sorted()
            .joinToString(",")
    Log.i(
        INCOMING_ATTACHMENT_TAG,
        "attachment_receipt_scheduled queued=${eligible.size} waiting=$waiting held=${held.size} " +
            "held_types=$heldTypes pages=${lookup.pages} end=${lookup.end.key}",
    )
}

/**
 * [accountRef]'s automatic-download inputs right now. Receipt scheduling and durable work both decide with
 * this, and [AutomaticAttachmentPolicy.allowedNow] matches the native permission exactly.
 */

internal fun WhiteNoiseAppState.automaticAttachmentPolicy(accountRef: String): AutomaticAttachmentPolicy {
    val account = accounts.firstOrNull { it.label == accountRef }
    return AutomaticAttachmentPolicy(
        account =
            when {
                !accountListLoaded -> AutomaticPolicyAccount.Unloaded
                account == null -> AutomaticPolicyAccount.Unknown
                account.signedOut -> AutomaticPolicyAccount.SignedOut
                else -> AutomaticPolicyAccount.SignedIn
            },
        paused = attachmentDownloadIntents.isAutomaticPaused(accountRef),
        matrix = loadMediaAutoDownloadMatrix(accountRef),
        networks = activeNetworkTypes(),
        validatedInternet = hasValidatedInternet(),
    )
}

/** The stored timeline row for a notification's message, or null when it has no id or the read fails. */
internal suspend fun WhiteNoiseAppState.notificationTimelineRecord(update: NotificationUpdateFfi) =
    update.messageIdHex?.let { messageId ->
        runCatchingCancellable {
            marmotIo {
                timelineMessages(
                    update.accountRef,
                    TimelineMessageQueryFfi.page(
                        groupIdHex = update.groupIdHex,
                        search = null,
                        before = null,
                        beforeMessageId = null,
                        after = null,
                        afterMessageId = null,
                        limit = 30u,
                    ),
                ).messages
            }
        }.getOrNull()
            ?.firstOrNull { it.messageIdHex.equals(messageId, ignoreCase = true) }
    }

/**
 * Reads MDK's durable state for one message candidate right before it may alert or be enriched (#2065, #3323).
 * Only incoming timeline messages are governed by the read state: reactions, invites and membership events pass,
 * except that nothing but the invitation itself rings for a group this account has not accepted, since MDK still
 * emits that group's messages and admin changes. A read, deleted or hidden candidate is withheld. A read that fails
 * or exceeds its bounded time is retried once, and a state that still cannot be read is
 * [NotificationCandidateState.Unknown]: the first write is withheld, because a
 * stale alert is worse than a missed one, while enrichment of an already-posted card proceeds. The outcome is
 * logged without identifiers or content.
 */
private suspend fun WhiteNoiseAppState.notificationCandidateGate(
    update: NotificationUpdateFfi,
    phase: String,
): NotificationCandidateState {
    val messageIdHex = update.messageIdHex?.takeIf(String::isNotBlank)
    val governed =
        update.trigger == NotificationTriggerFfi.NEW_MESSAGE &&
            !update.isFromSelf &&
            !LocalNotificationFormatter.isReaction(update)
    if (!governed || messageIdHex == null) {
        val invitation = update.trigger == NotificationTriggerFfi.GROUP_INVITE
        return if (invitation) NotificationCandidateState.Unread else pendingInviteState(update)
    }
    val state =
        if (notificationContentInvalidations.isRemoved(update.accountRef, update.groupIdHex, messageIdHex)) {
            NotificationCandidateState.Removed
        } else {
            readNotificationCandidateStateWithinBudget(update, messageIdHex)
        }
    appStateDebug { "notification candidate phase=$phase outcome=${state.logValue}" }
    return state
}

/**
 * Bounds each await to [NOTIFICATION_CANDIDATE_TIMEOUT_MILLIS], at most [NOTIFICATION_CANDIDATE_ATTEMPTS]
 * times. A synchronous binding may keep running; retries join it and its slot stays occupied until completion.
 * Different candidates fail fast behind an abandoned holder, including globally when all slots are abandoned.
 */
private suspend fun WhiteNoiseAppState.readNotificationCandidateStateWithinBudget(
    update: NotificationUpdateFfi,
    messageIdHex: String,
): NotificationCandidateState {
    repeat(NOTIFICATION_CANDIDATE_ATTEMPTS) {
        runCatchingCancellable {
            notificationCandidateReads.read(
                key =
                    NotificationCandidateReadKey(
                        update.accountRef,
                        update.groupIdHex.lowercase(),
                        messageIdHex.lowercase(),
                        notificationPostEpoch.capture(),
                        profileCacheLifetime.capture(),
                    ),
                timeoutMillis = NOTIFICATION_CANDIDATE_TIMEOUT_MILLIS,
            ) { readNotificationCandidateState(update, messageIdHex) }
        }.getOrNull()?.let { return it }
    }
    return NotificationCandidateState.Unknown
}

/**
 * Resolves one governed candidate from its hidden mark, chat-list row and timeline record, off main. The record
 * is always looked up, first in the latest page and then in the wider reconciliation page, so a deletion MDK
 * already accepted is honoured even when nothing has been read yet or the removal mark did not survive a restart.
 * A message in a group this account has not accepted is withheld before its timeline is read.
 */
@Suppress("ReturnCount") // Hidden, pending-invite and read-state outcomes each end the lookup at a different read.
private suspend fun WhiteNoiseAppState.readNotificationCandidateState(
    update: NotificationUpdateFfi,
    messageIdHex: String,
): NotificationCandidateState {
    val hidden =
        withContext(Dispatchers.IO) { hiddenMessageIdsInGroup(update.accountRef, update.groupIdHex) }
    if (hidden.any { it.equals(messageIdHex, ignoreCase = true) }) return NotificationCandidateState.Removed
    val row = marmotIo(MarmotTraceSection.CHAT_ROW_READ) { chatListRow(update.accountRef, update.groupIdHex) }
    // A message whose pending state cannot be confirmed still rings, so the first message after an Accept is kept.
    val pendingInvite = unacceptedInvite(update.accountRef, update.groupIdHex, row) == true
    if (pendingInvite) return NotificationCandidateState.PendingInvite
    val latest = notificationTimelinePage(update.accountRef, update.groupIdHex)
    val page =
        if (latest.any { it.messageIdHex.equals(messageIdHex, ignoreCase = true) }) {
            latest
        } else {
            notificationTimelinePage(update.accountRef, update.groupIdHex, NOTIFICATION_RECONCILIATION_PAGE_LIMIT)
        }
    return notificationCandidateState(messageIdHex, row, page)
}

/**
 * Whether [groupIdHex] is an invitation [accountRef] has not accepted. Only a chat-list [row] that says pending is
 * considered, and it is confirmed with the group record, because the row can lag an Accept and the first message after
 * accepting must still ring. No row, or a failed confirmation, counts as accepted, as before this check. MDK should
 * withhold these notifications itself (mdk#3469), and this guard can go once it does.
 */
private suspend fun WhiteNoiseAppState.unacceptedInvite(
    accountRef: String,
    groupIdHex: String,
    row: ChatListRowFfi?,
): Boolean? {
    if (row?.pendingConfirmation != true) return false
    val details = runCatchingCancellable { marmotIo { groupDetails(accountRef, groupIdHex) } }.getOrNull()
    return details?.group?.pendingConfirmation
}

/**
 * [unacceptedInvite] for an update the read state does not govern, such as a reaction or an admin change. The
 * blocking chat-row read runs through its own [PendingInviteReads] coordinator, so the serial listener stops waiting
 * after [NOTIFICATION_CANDIDATE_TIMEOUT_MILLIS] even while the binding still runs, and an abandoned invite read never
 * holds the slot a message's read-state check needs. A row that says pending but whose group record cannot be read
 * withholds the update as [NotificationCandidateState.Unknown]. A read that times out lets the update ring, as it did
 * before this check.
 */
private suspend fun WhiteNoiseAppState.pendingInviteState(update: NotificationUpdateFfi): NotificationCandidateState {
    val key =
        NotificationCandidateReadKey(
            update.accountRef,
            update.groupIdHex.lowercase(),
            PENDING_INVITE_READ,
            notificationPostEpoch.capture(),
            profileCacheLifetime.capture(),
        )
    val reads = runtimeMirrors.pendingInviteReads.coordinator(notificationScope)
    val state =
        runCatchingCancellable {
            reads.read(key, NOTIFICATION_CANDIDATE_TIMEOUT_MILLIS) {
                val row =
                    marmotIo(MarmotTraceSection.CHAT_ROW_READ) { chatListRow(update.accountRef, update.groupIdHex) }
                when (unacceptedInvite(update.accountRef, update.groupIdHex, row)) {
                    true -> NotificationCandidateState.PendingInvite
                    null -> NotificationCandidateState.Unknown
                    false -> NotificationCandidateState.Unread
                }
            }
        }.getOrNull()
    return state ?: NotificationCandidateState.Unread
}

/** The read key's message slot for a group's pending-invite check, which no message id can collide with. */
private const val PENDING_INVITE_READ = "pending-invite"

/** The conversation's latest bounded timeline page, the same window notification preparation reads. */
private suspend fun WhiteNoiseAppState.notificationTimelinePage(
    accountRef: String,
    groupIdHex: String,
    limit: UInt = NOTIFICATION_CANDIDATE_PAGE_LIMIT,
): List<TimelineMessageRecordFfi> =
    marmotIo {
        timelineMessages(
            accountRef,
            TimelineMessageQueryFfi.page(
                groupIdHex = groupIdHex,
                search = null,
                before = null,
                beforeMessageId = null,
                after = null,
                afterMessageId = null,
                limit = limit,
            ),
        ).messages
    }

/**
 * Removes [message] from its conversation's cards after MDK accepted its deletion or this device hid it (#3323).
 * The removal mark lands first, so a queued post or late enrichment of that message is fenced under the card
 * lock whichever side of the rewrite it reaches. Only that entry, and any entry without an identity, leaves the
 * card; other valid entries stay. Reconciliation is silent: it only rewrites or cancels and never alerts.
 */
internal suspend fun WhiteNoiseAppState.retractNotificationContent(message: RemovedNotificationMessage) {
    recordNotificationContentRemoval(message)
    withNotificationReconcileLock(message.accountRef, message.groupIdHex) {
        withContext(Dispatchers.Default) {
            localNotificationPresenter.reconcileConversationContent(
                message.accountRef,
                message.groupIdHex,
                cancelReactionCards = true,
            ) { entryId, _ ->
                entryId != null &&
                    !notificationContentInvalidations.isRemoved(message.accountRef, message.groupIdHex, entryId)
            }
        }
    }
}

/** Records an accepted removal before any platform card lock can delay its retraction. */
internal fun WhiteNoiseAppState.recordNotificationContentRemoval(message: RemovedNotificationMessage) {
    notificationContentInvalidations.markRemoved(message)
}

/**
 * Serializes card reconciliation per account and conversation, so a deletion, a read change and a stream-open
 * pass for the same conversation apply in order. Fixed stripes bound memory; hash collisions may serialize
 * unrelated conversations without losing mutual exclusion for existing waiters.
 */
private suspend fun <T> WhiteNoiseAppState.withNotificationReconcileLock(
    accountRef: String,
    groupIdHex: String,
    block: suspend () -> T,
): T {
    val key = "$accountRef\u0000${groupIdHex.lowercase()}"
    val stripe = (key.hashCode() and Int.MAX_VALUE) % notificationReconcileLocks.size
    return notificationReconcileLocks[stripe].withLock { block() }
}

/**
 * Requests cleanup for each shown conversation on stream open (#3323, #2065). Opens and read-state events
 * share the receipt loop's workers, so reconnecting never cancels or duplicates an unfinished native read.
 */
private suspend fun WhiteNoiseAppState.requestActiveCardReconciliation(coordinator: NotificationReadReconciliationCoordinator) {
    val cards = withContext(Dispatchers.Default) { localNotificationPresenter.activeConversationCards() }
    cards.map { it.accountRef to it.groupIdHex.lowercase() }.distinct().forEach { (account, group) ->
        coordinator.request(account, group)
    }
}

/**
 * Keeps on one conversation's cards only the entries MDK still reports showable: not deleted, not hidden on this
 * device and not read (#3323, #2065). Only what the state snapshot covers is judged. An entry missing from the
 * snapshot that was shown after the snapshot was taken is newer than it and stays; an older missing entry
 * cannot be verified and leaves. Cards whose entries carry no identity (posted by an older build) fall back to
 * [reconcileActiveConversationCard]. When MDK state stays unavailable, cards are kept and only content this
 * process knows was removed leaves them: the reconciliation is retried at the next stream open or read change.
 */
private suspend fun WhiteNoiseAppState.reconcileConversationCards(
    accountRef: String,
    groupIdHex: String,
    cards: List<ActiveConversationCard>,
    marmot: MarmotInterface? = null,
) {
    val snapshotStartedMs = System.currentTimeMillis()
    val state = readNotificationReconciliationState(accountRef, groupIdHex, marmot)
    val reconcile = { cancelReactions: Boolean, valid: (String?, Long) -> Boolean ->
        localNotificationPresenter.reconcileConversationContent(accountRef, groupIdHex, cancelReactions, valid)
    }
    val outcome =
        withNotificationReconcileLock(accountRef, groupIdHex) {
            when {
                state == null -> {
                    withContext(Dispatchers.Default) {
                        reconcile(false) { id, _ ->
                            id == null || !notificationContentInvalidations.isRemoved(accountRef, groupIdHex, id)
                        }
                    }
                    "unavailable"
                }
                cards.all { card -> card.entryMessageIds.all { it == null } } ->
                    reconcileLegacyConversationCards(accountRef, groupIdHex, cards, state).logValue
                else -> {
                    val (row, page, hidden) = state
                    val cancelReactions = hasRemovalWhileShown(page, hidden, cards.minOf { it.contentSinceMs })
                    withContext(Dispatchers.Default) {
                        reconcile(cancelReactions) { id, shownAtMs ->
                            isNotificationEntryStillShowable(
                                accountRef = accountRef,
                                groupIdHex = groupIdHex,
                                messageIdHex = id,
                                shownAtMs = shownAtMs,
                                snapshotStartedMs = snapshotStartedMs,
                                state = state,
                            )
                        }
                    }
                    "entries"
                }
            }
        }
    appStateDebug { "notification reconcile outcome=$outcome" }
}

/** Applies the coarse decision to cards whose entries were posted without message identities. */
private suspend fun WhiteNoiseAppState.reconcileLegacyConversationCards(
    accountRef: String,
    groupIdHex: String,
    cards: List<ActiveConversationCard>,
    state: NotificationReconciliationState,
): ActiveCardReconciliation {
    val messageCardId = cards.firstOrNull { it.isMessageCard }?.messageIdHex
    val outcome =
        reconcileActiveConversationCard(
            messageCardId,
            cards.minOf { it.contentSinceMs },
            state.row,
            state.page,
            state.hidden,
        )
    withContext(Dispatchers.Default) {
        when (outcome) {
            ActiveCardReconciliation.Retract ->
                localNotificationPresenter.reconcileConversationContent(accountRef, groupIdHex, true) { _, _ ->
                    false
                }
            ActiveCardReconciliation.CancelRead ->
                localNotificationPresenter.cancelConversationCardIfSameGeneration(
                    LocalNotificationFormatter.conversationDismissalKey(accountRef, groupIdHex).tag,
                    LocalNotificationFormatter.MESSAGE_NOTIFICATION_ID,
                    messageCardId,
                )
            ActiveCardReconciliation.Keep -> Unit
        }
    }
    return outcome
}

/**
 * Reads the hidden marks, chat-list row and bounded latest page one conversation's cards are checked against,
 * through [marmot] when the caller owns a runtime, retrying a failed read [NOTIFICATION_RECONCILIATION_ATTEMPTS]
 * times with a doubling delay. Null means the state stayed unavailable.
 */
private suspend fun WhiteNoiseAppState.readNotificationReconciliationState(
    accountRef: String,
    groupIdHex: String,
    marmot: MarmotInterface? = null,
): NotificationReconciliationState? {
    var delayMillis = NOTIFICATION_RECONCILIATION_RETRY_MILLIS
    repeat(NOTIFICATION_RECONCILIATION_ATTEMPTS) { attempt ->
        runCatchingCancellable {
            val hidden = withContext(Dispatchers.IO) { hiddenMessageIdsInGroup(accountRef, groupIdHex) }
            val (row, page) =
                if (marmot != null) {
                    withContext(Dispatchers.IO) {
                        marmot.chatListRow(accountRef, groupIdHex) to
                            marmot.timelineMessages(accountRef, notificationPageQuery(groupIdHex)).messages
                    }
                } else {
                    marmotIo(MarmotTraceSection.CHAT_ROW_READ) { chatListRow(accountRef, groupIdHex) } to
                        notificationTimelinePage(accountRef, groupIdHex, NOTIFICATION_RECONCILIATION_PAGE_LIMIT)
                }
            NotificationReconciliationState(row, page, hidden.mapTo(HashSet()) { it.lowercase() })
        }.onSuccess { return it }
        if (attempt < NOTIFICATION_RECONCILIATION_ATTEMPTS - 1) delay(delayMillis)
        delayMillis *= 2
    }
    return null
}

/** The reconciliation page query for one conversation. */
private fun WhiteNoiseAppState.notificationPageQuery(groupIdHex: String) =
    TimelineMessageQueryFfi.page(
        groupIdHex = groupIdHex,
        search = null,
        before = null,
        beforeMessageId = null,
        after = null,
        afterMessageId = null,
        limit = NOTIFICATION_RECONCILIATION_PAGE_LIMIT,
    )

/**
 * Whether a shown card entry may stay after reconciling against [state] read at [snapshotStartedMs] (#3323). An
 * entry the snapshot covers is judged by [isNotificationEntryShowable]. One it does not cover stays only if it was
 * shown after the snapshot started, so a message posted while the state was being read is never dropped, and an
 * older uncovered entry, which cannot be verified, leaves. An entry this process saw removed never stays.
 */
private fun WhiteNoiseAppState.isNotificationEntryStillShowable(
    accountRef: String,
    groupIdHex: String,
    messageIdHex: String?,
    shownAtMs: Long,
    snapshotStartedMs: Long,
    state: NotificationReconciliationState,
): Boolean {
    val id = messageIdHex ?: return false
    val covered = state.page.any { it.messageIdHex.equals(id, ignoreCase = true) }
    val removedHere = notificationContentInvalidations.isRemoved(accountRef, groupIdHex, id)
    return !removedHere &&
        if (covered) {
            isNotificationEntryShowable(id, state.row, state.page, state.hidden)
        } else {
            id.lowercase() !in state.hidden && shownAtMs >= snapshotStartedMs - CARD_SNAPSHOT_SLACK_MILLIS
        }
}

/**
 * Durable engine mute for the update's conversation, resolved once per
 * update in [processNotificationUpdate] and threaded through the initial
 * post, optional enrichment, and the presenter's sync post-time re-check (which
 * cannot suspend). Prefers the active account's loaded projection;
 * otherwise reads the engine's notification settings directly, which also covers the
 * cold FCM process with no UI-owned controllers. Fail-open on errors: a
 * spurious notification from a muted chat beats a silently swallowed
 * real one.
 */
private suspend fun WhiteNoiseAppState.engineNotificationMuted(update: NotificationUpdateFfi): Boolean {
    // Hex ids compare case-insensitively (projector idiom): a casing
    // drift here would silently fail open as a missed mute.
    if (activeAccountRef == update.accountRef) {
        chatsController
            ?.items
            ?.firstOrNull { it.group.groupIdHex.equals(update.groupIdHex, ignoreCase = true) }
            ?.let { return it.engineMuted() }
    }
    return runCatchingCancellable {
        chatMuteRepository.settings(update.accountRef, update.groupIdHex).getOrThrow().muted
    }.getOrDefault(false)
}

/** Writes one silent matching correction under the captured post generation. */
private suspend fun WhiteNoiseAppState.postNotificationLateCorrection(
    update: NotificationUpdateFfi,
    firstPost: NotificationFirstPost,
    content: NotificationFirstPostContent,
): Boolean {
    if (
        !isNotificationEnrichmentAllowed(withoutStaleQueuedReactionQuote(update, firstPost), firstPost) ||
        !firstPost.lateCorrectionPermit.acquire()
    ) {
        return false
    }
    var posted = false
    try {
        posted =
            withVerifiedReactionQuote(update, firstPost) { candidate, isNotDismissed ->
                if (!isNotDismissed() || !isNotificationEnrichmentAllowed(candidate, firstPost)) {
                    return@withVerifiedReactionQuote false
                }
                val useDirectLayout = candidate.isDm || content.isDirectConversation
                val peer = candidate.sender.accountIdHex.takeIf { useDirectLayout }
                localNotificationPresenter.show(
                    update = candidate,
                    conversationTitleOverride = content.conversationTitle,
                    senderNameOverride = content.senderName,
                    previewTextOverride = content.previewText,
                    reactedToPreviewOverride = verifiedReactionPreview(candidate, content.reactedToPreview),
                    mediaKind = content.mediaKind,
                    recipientAccountSubtext = content.recipientAccountSubtext,
                    directShareEligible = candidate.accountRef == activeAccountRef,
                    conversationAvatarBitmap = firstPost.avatars.conversationBitmap(useDirectLayout),
                    senderAvatarBitmap = firstPost.avatars.senderAvatarBitmap,
                    identityContact = firstPost.avatars.identityContact ?: peer,
                    useDirectLayout = useDirectLayout,
                    silentUpdate = true,
                    replaceCurrentMessage = true,
                    shortNpub = ::shortNpub,
                    isPostStillAllowed = {
                        isNotDismissed() && isNotificationEnrichmentAllowed(candidate, firstPost)
                    },
                )
            }
    } finally {
        firstPost.lateCorrectionPermit.complete(posted)
    }
    return posted
}

/**
 * Publishes at most one silent text correction. A card first drawn without
 * its conversation or sender picture then gets at most one silent picture
 * rewrite once the avatar prewarm has made that picture available (#3311),
 * and a card that already showed its pictures is never repainted for imagery.
 * The prewarm runs here, and [deferPicture] receives the picture refresh it
 * enables, which the caller runs after releasing the enrichment permit.
 */
private suspend fun WhiteNoiseAppState.enrichPostedNotificationUpdate(
    update: NotificationUpdateFfi,
    firstPost: NotificationFirstPost,
    deferPicture: (LateNotificationIdentity) -> Unit,
): NotificationLateCorrectionOutcome {
    val candidate = withoutStaleQueuedReactionQuote(update, firstPost)
    return if (!isNotificationEnrichmentAllowed(candidate, firstPost)) {
        NotificationLateCorrectionOutcome.Stale
    } else {
        // Always run the post-deadline resolver. Even a complete local first
        // draw may contain deterministic mention/title fallbacks that need to
        // start ordinary profile hydration after the first notify has returned.
        val content =
            runCatchingCancellable {
                notificationContentResolution.firstPost.resolve(candidate, localOnly = false)
            }.getOrNull() ?: firstPost.content
        when {
            content == null -> NotificationLateCorrectionOutcome.Failed
            !isNotificationEnrichmentAllowed(withoutStaleQueuedReactionQuote(candidate, firstPost), firstPost) ->
                NotificationLateCorrectionOutcome.Stale
            else ->
                enrichResolvedNotificationUpdate(
                    withoutStaleQueuedReactionQuote(candidate, firstPost),
                    firstPost,
                    content,
                    deferPicture,
                )
        }
    }
}

/** Routes a stable resolved presentation to its single permitted correction type. */
private suspend fun WhiteNoiseAppState.enrichResolvedNotificationUpdate(
    update: NotificationUpdateFfi,
    firstPost: NotificationFirstPost,
    resolvedContent: NotificationFirstPostContent,
    deferPicture: (LateNotificationIdentity) -> Unit,
): NotificationLateCorrectionOutcome {
    val content =
        if (firstPost.content?.isDirectConversation == true && !resolvedContent.isDirectConversation) {
            // A late local read can fail. Keep the direct layout already established for this post.
            resolvedContent.copy(conversationTitle = null, isDirectConversation = true)
        } else {
            resolvedContent
        }
    val resolvedPresentation =
        notificationContentPresentation(
            context = appContext,
            update = update,
            content = content.copy(reactedToPreview = verifiedReactionPreview(update, content.reactedToPreview)),
            shortNpub = ::shortNpub,
        )
    val outcome =
        if (
            notificationLateCorrectionPlan(
                firstPresentation = firstPost.presentation,
                resolvedPresentation = resolvedPresentation,
            ) == NotificationLateCorrectionPlan.Content
        ) {
            postNotificationContentCorrection(update, firstPost, content)
        } else {
            NotificationLateCorrectionOutcome.Unchanged
        }
    // Prime imagery only after any text correction is visible. A fallback
    // receives at most one text replacement for this message, and a stale
    // correction primes nothing.
    if (outcome != NotificationLateCorrectionOutcome.Stale) {
        prepareLateNotificationIdentity(update, firstPost, resolvedPresentation.content)?.let(deferPicture)
    }
    return outcome
}

/** Posts corrected text as this message's one silent late text write. */
private suspend fun WhiteNoiseAppState.postNotificationContentCorrection(
    update: NotificationUpdateFfi,
    firstPost: NotificationFirstPost,
    content: NotificationFirstPostContent,
): NotificationLateCorrectionOutcome =
    if (postNotificationLateCorrection(update, firstPost, content)) {
        NotificationLateCorrectionOutcome.ContentPosted
    } else {
        NotificationLateCorrectionOutcome.Stale
    }

/**
 * #3311: runs the post-write avatar prewarm and returns the late picture
 * refresh it enables for a card first drawn without its conversation or
 * sender picture, or null when no picture can still arrive. [content] is
 * what the card shows. Unless the card already shows it, the stored
 * picture the chat list draws is read first from MDK's copy (#3635), so a
 * picture already on the device needs no download and replaces one the
 * first post drew from elsewhere, such as a direct chat's published
 * sender picture on an account whose chat list was not loaded. The row's
 * peer, whose private picture the row shows instead, is carried along so
 * the rewrite records them. A missing sender picture gets a loader with
 * the late refresh's longer bound. Reaction cards show only the reactor's picture.
 */
private suspend fun WhiteNoiseAppState.prepareLateNotificationIdentity(
    update: NotificationUpdateFfi,
    firstPost: NotificationFirstPost,
    content: LocalNotificationContent?,
): LateNotificationIdentity? {
    if (content == null) return null
    val reaction = LocalNotificationFormatter.isReaction(update)
    val isDm = !content.isGroupConversation
    val warmed = notificationAvatarCoordinator.preWarm(update, firstPost.engineMuted)
    val pictures =
        firstPost.avatars
            .lateIdentitySources(
                warmed,
                isDm,
                dev.ipf.whitenoise.android.notifications
                    .notificationConversationDomain(update),
                dev.ipf.whitenoise.android.notifications
                    .notificationSenderDomain(update),
                reactorOnly = reaction,
            ).withLateLoaders(
                isDm = isDm,
                reactorOnly = reaction,
                storedShown = firstPost.avatars.storedConversationBitmap != null,
                row = {
                    storedConversationIdentity(update)?.takeUnless { reaction && it.contact != null }
                },
                encryptedGroupLoader = { encryptedGroupIdentityLoader(update) },
                urlLoader = ::latePictureLoader,
            )
    return if (pictures.mayRefresh) LateNotificationIdentity(content, pictures) else null
}

/**
 * #3635: loads a published picture, a sender's or a conversation's URL picture, for the late refresh through the
 * same decoded cache and worker domain as the first post's fetch, without that fetch's short bound, so a cold
 * download or a newly started decoder still reaches the card. Nothing new starts while the app lock shows, and a
 * picture that finishes after the lock appears is dropped. The late refresh fetches only through such loaders,
 * so a stop behind the lock is never followed by a raw URL fetch.
 */
private fun WhiteNoiseAppState.latePictureLoader(
    url: String,
    domain: dev.ipf.whitenoise.android.media.MediaTrustDomain,
): suspend () -> Bitmap? =
    lockCheckedPictureLoader(url, domain, { appLock.screenVisible }) { target, trust ->
        AvatarImageLoader.loadBitmap(target, trust)
    }

/**
 * #3635: what the chat row adds to a late refresh, for any account and with no network, read from MDK's presented
 * row by the chat list's own adoption rule, so a named group never borrows a member's face while a direct chat
 * shows its contact: a loader for MDK's stored copy of the row's selected avatar, and the peer whose private
 * picture the row shows instead. The loader warms that peer's private picture but never returns it. The card
 * records the peer, and the final write applies their current choice, so a picture saved or cleared meanwhile
 * is honored. A missing copy is never acquired here, so the URL and encrypted-download fallbacks still apply.
 * Nothing new starts while the app lock shows, and a copy that finishes after the lock appears is dropped. Null
 * when the row cannot be read.
 */
private suspend fun WhiteNoiseAppState.storedConversationIdentity(update: NotificationUpdateFfi): LateRowIdentity? {
    val row = presentedNotificationChatRow(update) ?: return null
    val account = update.accountRef
    val item =
        chatListItemFromProjection(row.row, row.presentation, row.avatarAsset, activeAccountIdHex = update.accountIdHex)
    return lateRowIdentity(account, item)
}

/** Reads the exact receiver-owned presentation without waiting for a chat list to bind. */
internal suspend fun WhiteNoiseAppState.presentedNotificationChatRow(update: NotificationUpdateFfi) =
    runCatchingCancellable { marmotIo { presentedChatListRow(update.accountRef, update.groupIdHex) } }.getOrNull()

/**
 * #3635: [storedConversationIdentity] for a row already read. Once the loader decodes the row's stored picture, it
 * records that picture for the row's peer, so a cleared private choice shows it while no chat list holds the row.
 */
internal fun WhiteNoiseAppState.lateRowIdentity(
    account: String,
    item: ChatListItem,
): LateRowIdentity {
    val contact = item.privatePictureContact()
    val stored = item.storedNotificationAvatar(account)
    val loader: (suspend () -> Bitmap?)? =
        if (contact == null && stored == null) {
            null
        } else {
            {
                if (!appLock.screenVisible) contact?.let { warmPrivateContactAvatar(account, it) }
                stored?.takeUnless { appLock.screenVisible }?.let { row ->
                    val picture =
                        durableAvatar(row.asset, account, acquireMissing = false, domain = row.domain)
                            ?.takeUnless { appLock.screenVisible }
                    if (picture != null && contact != null) {
                        notificationContactRowPictures.record(account, contact, row.asset)
                    }
                    picture?.asAndroidBitmap()
                }
            }
        }
    return LateRowIdentity(loader, contact)
}

/**
 * #3635: the current private picture choice for [item]'s peer, by the chat row's own rule, which notifications
 * share, so the Direct Share shortcut and a card never flip between the private and the stored picture.
 */
internal fun WhiteNoiseAppState.contactPictureOverride(
    account: String,
    item: ChatListItem,
): Pair<Boolean, Bitmap?> {
    val contact = item.privatePictureContact() ?: return false to null
    return contactAvatarOverride(account, contact)
}

/**
 * #3311: loads a group's Marmot encrypted picture, which has no URL, through the same MDK download and decoded
 * cache the group avatar uses on screen, or null when the group record carries no such picture. Nothing is
 * decrypted while the app lock shows, and a picture that finishes after the lock appears is dropped. A failed
 * read or download yields no picture, so the late refresh still applies a sender picture that did arrive, while
 * cancellation still propagates.
 */
private suspend fun WhiteNoiseAppState.encryptedGroupIdentityLoader(update: NotificationUpdateFfi): (suspend () -> Bitmap?)? {
    val group =
        runCatchingCancellable { marmotIo { groupDetails(update.accountRef, update.groupIdHex) }.group }.getOrNull()
    val key = group?.let { encryptedGroupAvatarCacheKey(update.accountRef, it) } ?: return null
    return {
        if (appLock.screenVisible) {
            null
        } else {
            runCatchingCancellable {
                GroupAvatarImageLoader
                    .load(key) { marmotIo { downloadGroupBlossomImage(update.accountRef, group.groupIdHex) } }
                    ?.asAndroidBitmap()
                    ?.takeUnless { appLock.screenVisible }
            }.getOrNull()
        }
    }
}

/**
 * #3311: gives the card the picture that has since become available, as
 * one silent rewrite of the same live card under every fence a late
 * correction obeys. It runs after the enrichment permit is released, since
 * it may wait for a download. App lock, a switched account, a newer epoch,
 * or a dismissed, read or replaced card stop it here or at the write, and
 * the presenter leaves redacted and hidden-preview cards alone.
 */
private suspend fun WhiteNoiseAppState.refreshLateNotificationIdentity(
    update: NotificationUpdateFfi,
    firstPost: NotificationFirstPost,
    late: LateNotificationIdentity,
) {
    // Avatar rewrites preserve live card text. Never replace its quote fence with an unquoted candidate:
    // that would permit the presenter's copy of a still-quoted live card to re-publish a removed excerpt.
    if (!isNotificationEnrichmentAllowed(update, firstPost)) return
    localNotificationPresenter.refreshLateIdentityImage(
        update = update,
        content = late.content,
        pictures = late.sources,
        directShareEligible = update.accountRef == activeAccountRef,
        isRefreshCurrent = { isNotificationEnrichmentAllowed(update, firstPost) },
    )
}

/** Posts either the resolved first-draw value or the existing safe fallback. */
private suspend fun WhiteNoiseAppState.postInitialNotificationUpdate(
    update: NotificationUpdateFfi,
    firstPost: NotificationFirstPost,
    receivedAtElapsedMs: Long,
): Boolean {
    appStateDebug {
        "notification eligibility outcome=${if (firstPost.shouldPost) "post" else "skip"} " +
            "trigger=${update.trigger} app_lock=${appLock.screenVisible} engine_muted=${firstPost.engineMuted}"
    }
    val gateStartedAt = SystemClock.elapsedRealtime()
    val candidateState =
        if (firstPost.shouldPost) measureNotificationCandidateGate(update) else NotificationCandidateState.Unknown
    recordNotificationFirstPostTiming(
        stage = NotificationFirstPostTimingStage.CandidateGateComplete,
        receivedAtElapsedMs = receivedAtElapsedMs,
        stageStartedAtElapsedMs = gateStartedAt,
        outcome = candidateState.logValue,
    )
    if (!firstPost.shouldPost || candidateState != NotificationCandidateState.Unread) {
        PushWakeDiagnostics.event(PushWakeEvent.Suppressed)
        return false
    }

    val redactContent = appLock.screenVisible
    val onNotificationWritten = {
        notificationProfileRepeats.finish(update, posted = true, nowMs = System.currentTimeMillis())
        recordNotificationFirstPostTiming(
            stage = NotificationFirstPostTimingStage.NotifyWritten,
            receivedAtElapsedMs = receivedAtElapsedMs,
            outcome = "posted",
        )
    }
    var posted = showInitialNotificationUpdate(update, firstPost, redactContent, onNotificationWritten)
    var postedRedacted = redactContent
    // Lock activation can race the suspending presenter setup. Retry only
    // as a redacted card, under the same generation and eligibility gates.
    if (!posted && !redactContent && appLock.screenVisible) {
        postedRedacted = true
        posted = showRedactedNotificationUpdate(update, firstPost, onNotificationWritten)
    }
    rememberPostedGroupInvite(update, firstPost, posted, postedRedacted)
    PushWakeDiagnostics.event(if (posted) PushWakeEvent.Posted else PushWakeEvent.Suppressed)
    return posted
}

/** Emits the actual admission/read wait through opt-in WNPerf, without account or message identifiers. */
private suspend fun WhiteNoiseAppState.measureNotificationCandidateGate(update: NotificationUpdateFfi): NotificationCandidateState {
    val trace = PerformanceDiagnostics.begin(PerformanceOperation.NOTIFICATION_POST)
    val startedAt = SystemClock.elapsedRealtime()
    val state = notificationCandidateGate(update, "post")
    val elapsed = (SystemClock.elapsedRealtime() - startedAt).coerceAtLeast(0L)
    PerformanceDiagnostics.record(
        trace = trace,
        phase = PerformancePhase.NOTIFICATION_CANDIDATE_GATE,
        elapsedMs = elapsed,
        durationMs = elapsed,
        result =
            when (state) {
                NotificationCandidateState.Unread -> PerformanceResult.SUCCESS
                NotificationCandidateState.Read,
                NotificationCandidateState.Removed,
                NotificationCandidateState.PendingInvite,
                -> PerformanceResult.DROPPED
                NotificationCandidateState.Unknown -> PerformanceResult.FAILURE
            },
        layer = PerformanceLayer.ANDROID,
    )
    return state
}

/** Executes the resolved or fallback first write under the captured fences. */
private suspend fun WhiteNoiseAppState.showInitialNotificationUpdate(
    update: NotificationUpdateFfi,
    firstPost: NotificationFirstPost,
    redactContent: Boolean,
    onNotificationWritten: () -> Unit,
): Boolean =
    withVerifiedReactionQuote(update, firstPost) { candidate, isNotDismissed ->
        val useDirectLayout = candidate.isDm || firstPost.content?.isDirectConversation == true
        val peer = candidate.sender.accountIdHex.takeIf { useDirectLayout }
        localNotificationPresenter.show(
            update = candidate,
            conversationTitleOverride = firstPost.content?.conversationTitle,
            senderNameOverride = firstPost.content?.senderName,
            previewTextOverride = firstPost.content?.previewText,
            reactedToPreviewOverride = verifiedReactionPreview(candidate, firstPost.content?.reactedToPreview),
            mediaKind = firstPost.content?.mediaKind ?: ReplyMediaKind.None,
            recipientAccountSubtext = firstPost.content?.recipientAccountSubtext,
            conversationAvatarBitmap = firstPost.avatars.conversationBitmap(useDirectLayout),
            senderAvatarBitmap = firstPost.avatars.senderAvatarBitmap,
            identityContact = firstPost.avatars.identityContact ?: peer,
            useDirectLayout = useDirectLayout,
            redactContent = redactContent,
            directShareEligible = !redactContent && candidate.accountRef == activeAccountRef,
            shortNpub = ::cachedShortNpubOrUnknown,
            onNotificationWritten = onNotificationWritten,
            isPostStillAllowed = {
                isNotDismissed() &&
                    isQueuedReactionQuoteCurrent(candidate, firstPost) &&
                    isNotificationGenerationPostAllowed(
                        update = candidate,
                        postEpoch = firstPost.epoch,
                        accountCacheEpoch = firstPost.accountCacheEpoch,
                        engineMuted = firstPost.engineMuted,
                        requireUnlocked = !redactContent,
                        directConversation = useDirectLayout || firstPost.profileDirectConversation,
                        reserveRepeat = true,
                    )
            },
        )
    }

/** Retries a lock-raced initial write without carrying any decrypted content. */
private suspend fun WhiteNoiseAppState.showRedactedNotificationUpdate(
    update: NotificationUpdateFfi,
    firstPost: NotificationFirstPost,
    onNotificationWritten: () -> Unit,
): Boolean =
    localNotificationPresenter.show(
        update = update.copy(reactedToPreview = null),
        redactContent = true,
        shortNpub = ::cachedShortNpubOrUnknown,
        onNotificationWritten = onNotificationWritten,
        isPostStillAllowed = {
            isNotificationGenerationPostAllowed(
                update = update.copy(reactedToPreview = null),
                postEpoch = firstPost.epoch,
                accountCacheEpoch = firstPost.accountCacheEpoch,
                engineMuted = firstPost.engineMuted,
                requireUnlocked = false,
                directConversation = update.isDm || firstPost.profileDirectConversation,
                reserveRepeat = true,
            )
        },
    )

/** Retains one posted invite's actual rendered name and shared correction budget. */
private fun WhiteNoiseAppState.rememberPostedGroupInvite(
    update: NotificationUpdateFfi,
    firstPost: NotificationFirstPost,
    posted: Boolean,
    redactContent: Boolean,
) {
    postedGroupInviteIdentity(
        update = update,
        posted = posted,
        redactContent = redactContent,
        displayedName =
            firstPost.content?.senderName
                ?: notificationDisplayNameHint(update.sender.displayName),
    )?.let { identity ->
        inviteNotificationIdentityRefreshStore.rememberPosted(
            identity = identity.identity,
            displayedName = identity.displayedName,
            postEpoch = firstPost.epoch,
            accountCacheEpoch = firstPost.accountCacheEpoch,
            engineMuted = firstPost.engineMuted,
            lateCorrectionPermit = firstPost.lateCorrectionPermit,
        )
    }
}

/** Schedules generation-fenced late content or avatar correction work. */
private fun WhiteNoiseAppState.scheduleNotificationEnrichment(
    update: NotificationUpdateFfi,
    firstPost: NotificationFirstPost,
    receivedAtElapsedMs: Long,
) {
    notificationScope.launch(notificationDispatcher) {
        // Every successful first post re-reads the read state first, ahead of the enrichment fences and queue, so
        // a read that committed while the first write was in flight cancels the card at once (#2065). A read
        // after this point reaches a card that already exists, which its read-state change then reconciles.
        if (!isNotificationCandidateStillUnread(update, "enrich")) return@launch
        var latePicture: LateNotificationIdentity? = null
        notificationEnrichmentGate.withPermit {
            if (!isNotificationEnrichmentAllowed(withoutStaleQueuedReactionQuote(update, firstPost), firstPost)) {
                return@withPermit
            }
            val outcome = enrichPostedNotificationUpdate(update, firstPost) { latePicture = it }
            appStateDebug {
                "notification timing stage=late-correction " +
                    "elapsed_ms=${(SystemClock.elapsedRealtime() - receivedAtElapsedMs).coerceAtLeast(0L)} " +
                    "outcome=${outcome.timingValue}"
            }
        }
        // Reuse this enrichment pass's candidate result for its picture work. Runtime events reconcile
        // subsequent read/removal changes, and every picture write still checks the live generation fences.
        // Picture downloads run outside the permit and never delay another message's text correction.
        latePicture?.let { refreshLateNotificationIdentity(update, firstPost, it) }
    }
}

/**
 * Re-checks a posted candidate before detached work rewrites its card (#2065, #3323). A candidate that became
 * read since its post has that card cancelled while it still shows this message, so a newer sibling survives;
 * a deleted or hidden one was already retracted, and an unreadable state lets the rewrite proceed.
 */
private suspend fun WhiteNoiseAppState.isNotificationCandidateStillUnread(
    update: NotificationUpdateFfi,
    phase: String,
): Boolean {
    val state = notificationCandidateGate(update, phase)
    if (state == NotificationCandidateState.Read) {
        withContext(Dispatchers.Default) {
            localNotificationPresenter.cancelNotificationUpdateCardIfCurrent(update)
        }
    }
    // An unreadable state never cancels or freezes a card that already posted: its enrichment proceeds.
    return state == NotificationCandidateState.Unread || state == NotificationCandidateState.Unknown
}

/** Runs receipt scheduling beside the first post, so queueing media never delays the notification. */
private fun WhiteNoiseAppState.scheduleIncomingAttachmentDownloadMaintenance(update: NotificationUpdateFfi) {
    notificationScope.launch(notificationDispatcher) {
        scheduleIncomingAttachmentDownloads(update)
    }
}

private fun WhiteNoiseAppState.signalNotificationDrain() {
    notificationDrainSignals.tryEmit(notificationDrainSequence.incrementAndGet())
}

internal fun WhiteNoiseAppState.startNotificationListener() {
    if (networkNotificationRecoverySuppressed) return
    notificationJob.startIfInactive(::launchNotificationListenerLoop)
}

internal fun WhiteNoiseAppState.launchNotificationListenerLoop(): Job =
    notificationScope.launch(notificationDispatcher) {
        runNotificationListenerLoop(marmot())
    }

/**
 * Schedules the media of every received chat message from the runtime event stream, alongside the notification
 * stream. MDK resolves an ordinary message in a muted chat to no notification update, and refuses every update
 * while an account's notifications are off, so receipt scheduling that rode only that stream would leave their
 * attachments unscheduled, although #3308 holds them to the account's media policy. Whether a notification was
 * suppressed cannot be read back later from current settings, so every message is scheduled here, idempotently,
 * and the first of the two sources runs its bounded lookup. [initial] is the subscription the startup listener
 * opened before the runtime started, so no message delivered after start is missed. A stream that ends or
 * fails, and a failed open, are retried with the capped backoff for as long as the listener runs. Lookups run
 * as supervised children of this loop, so they end with it, and a failing one is logged without cancelling the
 * loop or the notification listener above it. The same stream carries MDK's accepted deletions, so each event
 * also retracts removed notification content, and each open reconciles the cards already shown (#3323). It also
 * tells the active account when MDK drops a queued group change for size, which nothing else surfaces, and tells
 * any signed-in account once when a chat could not return to its latest state and needs a restart.
 */
internal suspend fun WhiteNoiseAppState.runMutedChatReceiptLoop(
    marmot: MarmotInterface,
    initial: EventsSubscription? = null,
) = supervisorScope {
    var backoffMillis = NOTIFICATION_RETRY_INITIAL_BACKOFF_MILLIS
    var preopened = initial
    val readReconciliations =
        NotificationReadReconciliationCoordinator(
            scope = this,
            followUpMillis = NOTIFICATION_READ_RECONCILE_FOLLOW_UP_MILLIS,
        ) { account, group ->
            runCatchingCancellable { reconcileConversationNotificationCards(account, group, marmot) }
                .onFailure { appStateDebug(it) { "notification read reconcile failed" } }
        }
    while (isActive) {
        val subscription =
            preopened?.also { preopened = null } ?: runCatchingCancellable { marmot.subscribeEvents() }
                .onFailure {
                    val type = it.javaClass.simpleName
                    Log.w(INCOMING_ATTACHMENT_TAG, "muted_receipt_stream_unavailable type=$type")
                }.getOrNull()
        // A failed open is retried with the capped backoff for as long as the listener runs: muted chats and
        // notifications-off accounts get no notification update, so this loop is their only receipt source.
        if (subscription != null) {
            try {
                // Each open requests cleanup for cards left while the process or stream was down. It shares
                // read-state workers: a newer open marks a blocked pass dirty and retains its latest follow-up.
                runCatchingCancellable { requestActiveCardReconciliation(readReconciliations) }
                    .onFailure { appStateDebug(it) { "notification reconcile failed" } }
                // A chat whose restore failed before this stream opened still tells the user to restart.
                launch {
                    rewindRestoreFailuresOnOpen(marmot).forEach { failure ->
                        launch(Dispatchers.Main) { presentRewindRestoreFailure(failure) }
                    }
                }
                // A group change MDK gave up while no stream listened still tells the user, once.
                launch { runCatchingCancellable { presentRetainedGroupChange(marmot) } }
                runCatchingCancellable {
                    var event = subscription.next()
                    while (event != null) {
                        backoffMillis = NOTIFICATION_RETRY_INITIAL_BACKOFF_MILLIS
                        handleNotificationContentEvent(event, readReconciliations, scope = this)
                        presentEventNotices(event, marmot, scope = this)
                        val received = (event as? MarmotEventFfi.MessageReceived)?.received
                        // Only the cheap kind and sender check runs here; the suppression reads run with the
                        // lookup, as a child of this loop, so they never delay the next event.
                        if (received != null && received.mayCarryIncomingAttachments()) {
                            // A supervised child whose failure is logged, so one receipt can never cancel the
                            // loop or, through it, the notification listener.
                            launch(notificationDispatcher) {
                                runCatchingCancellable { scheduleRuntimeEventReceipt(received) }
                                    .onFailure { Log.w(INCOMING_ATTACHMENT_TAG, "muted_receipt_schedule_failed") }
                            }
                        }
                        event = subscription.next()
                    }
                }.onFailure { Log.w(INCOMING_ATTACHMENT_TAG, "muted_receipt_stream_failed") }
            } finally {
                withContext(NonCancellable) { runCatching { subscription.destroy() } }
            }
        }
        delay(backoffMillis)
        backoffMillis = nextRetryBackoffMillis(backoffMillis, NOTIFICATION_RETRY_MAX_BACKOFF_MILLIS)
    }
}

/**
 * Applies [event]'s notification consequences without delaying the receipt loop that delivered it (#3323,
 * #2065). A removal is fenced at once, so no queued post or correction can restore it, and its cards are
 * rewritten in a supervised child. Read-state changes share one worker per conversation, including a delayed
 * follow-up after its latest request so a card not yet listed by the platform is also corrected. An in-flight
 * native read finishes before a dirty worker reads again. Failures are logged without cancelling the loop.
 */
private fun WhiteNoiseAppState.handleNotificationContentEvent(
    event: MarmotEventFfi,
    readReconciliations: NotificationReadReconciliationCoordinator,
    scope: CoroutineScope,
) {
    removedNotificationMessages(event).forEach { message ->
        recordNotificationContentRemoval(message)
        scope.launch {
            removeMessageDisplayThumbnails(message.accountRef, message.groupIdHex, message.messageIdHex)
        }
        scope.launch {
            runCatchingCancellable { retractNotificationContent(message) }
                .onFailure { appStateDebug(it) { "notification retraction failed" } }
        }
    }
    readStateChangedConversation(event)?.let { (accountRef, groupIdHex) ->
        readReconciliations.request(accountRef, groupIdHex)
    }
}

/**
 * Reconciles one conversation's shown cards after its read state changed, so a message read anywhere this
 * account's MDK state reflects leaves the tray and a newer unread sibling stays (#2065).
 */
private suspend fun WhiteNoiseAppState.reconcileConversationNotificationCards(
    accountRef: String,
    groupIdHex: String,
    marmot: MarmotInterface? = null,
) {
    val cards =
        withContext(Dispatchers.Default) { localNotificationPresenter.activeConversationCards() }
            .filter { it.accountRef == accountRef && it.groupIdHex.equals(groupIdHex, ignoreCase = true) }
    if (cards.isNotEmpty()) reconcileConversationCards(accountRef, groupIdHex, cards, marmot)
}

/** Whether [this] is a chat message whose attachments this device may still have to fetch. */
private fun RuntimeMessageReceivedFfi.mayCarryIncomingAttachments(): Boolean = message.kind == MARMOT_CHAT_EVENT_KIND

/**
 * Schedules one received chat message's media from the runtime event stream, whatever its notification did. The
 * notification outcome cannot be inferred later from current settings: a chat muted when MDK suppressed the
 * update may be unmuted by the time this runs. Scheduling is idempotent, and [receiptClaims] lets only the first
 * of the two receipt sources run the message's bounded history lookup.
 */
private suspend fun WhiteNoiseAppState.scheduleRuntimeEventReceipt(received: RuntimeMessageReceivedFfi) {
    val message = received.message
    scheduleReceivedMessageAttachments(
        accountRef = received.accountLabel,
        groupIdHex = message.groupIdHex,
        messageIdHex = message.messageIdHex,
        receivedAtMillis = message.receivedAt.toLong() * MILLIS_PER_SECOND_LONG,
    )
}

/**
 * Opens the runtime event subscription the receipt loop reads, before [marmot] starts, so a message delivered
 * between start and the listener's own subscriptions still reaches a receipt scheduler. MDK's event firehose is a
 * broadcast receiver that needs no started runtime. A failure leaves the loop to open its own.
 */
internal fun WhiteNoiseAppState.openStartupReceiptSubscription(marmot: MarmotInterface) {
    val subscription = runCatching { marmot.subscribeEvents() }.getOrNull() ?: return
    synchronized(startupReceiptLock) {
        startupReceiptSubscription?.second?.let { runCatching { it.destroy() } }
        startupReceiptSubscription = marmot to subscription
    }
}

/** Hands the startup receipt subscription of [marmot] to its single consumer, or null when there is none. */
internal fun WhiteNoiseAppState.takeStartupReceiptSubscription(marmot: MarmotInterface): EventsSubscription? =
    synchronized(startupReceiptLock) {
        startupReceiptSubscription
            ?.takeIf { it.first === marmot }
            ?.second
            .also { if (it != null) startupReceiptSubscription = null }
    }

internal suspend fun WhiteNoiseAppState.runNotificationListenerLoop(marmot: MarmotInterface) {
    // Restart the subscription on any failure (or clean end-of-stream)
    // with exponential backoff, so a transient relay/binding error
    // doesn't permanently silence notifications. Backoff resets after
    // each received update; cancellation propagates and stops the loop.
    // See #56.
    var backoffMillis = NOTIFICATION_RETRY_INITIAL_BACKOFF_MILLIS
    var receiptLoop: Job? = null
    while (currentCoroutineContext().isActive) {
        val retryWakeGeneration = notificationReceiverRetryWake.value
        try {
            val subscription = notificationSubscriber(marmot)
            // Only once the notification subscription is open, so start's no-replay window stays unwidened, and
            // as a child of this listener on IO, so it stops with notifications without delaying them.
            // Started again whenever an earlier one ended, for example after its event stream kept failing to open.
            val listenerScope = CoroutineScope(currentCoroutineContext())
            receiptLoop =
                receiptLoop?.takeIf { it.isActive } ?: listenerScope.launch(Dispatchers.IO) {
                    // Contained, so a receipt failure is logged and never cancels notification delivery.
                    val startup = takeStartupReceiptSubscription(marmot)
                    runCatchingCancellable { runMutedChatReceiptLoop(marmot, startup) }
                        .onFailure { Log.w(INCOMING_ATTACHMENT_TAG, "muted_receipt_loop_failed") }
                }
            runtimeListenerTeardownOwner.withNotificationSubscription(subscription) {
                while (currentCoroutineContext().isActive) {
                    val update = subscription.next() ?: break
                    backoffMillis = NOTIFICATION_RETRY_INITIAL_BACKOFF_MILLIS
                    withContext(Dispatchers.Main.immediate) {
                        processNotificationUpdate(update)
                    }
                }
            }
        } catch (cancel: CancellationException) {
            throw cancel
        } catch (throwable: Throwable) {
            appStateDebug(throwable) {
                "notification listener error; retrying in ${backoffMillis}ms: ${throwable.readableMessage()}"
            }
        }
        if (!currentCoroutineContext().isActive) break
        awaitNotificationRetryWindow(notificationReceiverRetryWake, retryWakeGeneration, backoffMillis)
        backoffMillis = nextRetryBackoffMillis(backoffMillis, NOTIFICATION_RETRY_MAX_BACKOFF_MILLIS)
    }
}

/** Posts and enriches one update under a foreground/app-lock eligibility epoch. */
private suspend fun WhiteNoiseAppState.processNotificationUpdate(incoming: NotificationUpdateFfi) {
    val contentRemovalVersion = notificationContentInvalidations.removalVersion(incoming.accountRef)
    val update = withoutUnverifiableReactionQuote(incoming)
    PushWakeDiagnostics.event(PushWakeEvent.Candidate)
    val receivedAtElapsedMs = SystemClock.elapsedRealtime()
    recordNotificationFirstPostTiming(
        stage = NotificationFirstPostTimingStage.Received,
        receivedAtElapsedMs = receivedAtElapsedMs,
        outcome = "observed",
    )
    applyNotificationDisplayNameHint(update)
    scheduleIncomingAttachmentDownloadMaintenance(update)
    val postEpoch = notificationPostEpoch.capture()
    val accountCacheEpoch = profileCacheLifetime.capture()
    val engineMuted = engineNotificationMuted(update)
    val directConversation =
        update.isDm ||
            (update.trigger == NotificationTriggerFfi.NEW_MESSAGE && isStoredDirectChat(update))
    try {
        val shouldPost = shouldPostNotification(update, engineMuted, directConversation, reserveRepeat = true)
        recordNotificationFirstPostTiming(
            stage = NotificationFirstPostTimingStage.EligibilityComplete,
            receivedAtElapsedMs = receivedAtElapsedMs,
            outcome = "resolved",
        )
        val firstPost =
            resolveNotificationFirstPost(
                update,
                postEpoch,
                accountCacheEpoch,
                engineMuted,
                shouldPost,
                receivedAtElapsedMs,
            ).copy(
                contentRemovalVersion = contentRemovalVersion,
                profileDirectConversation = directConversation,
            )
        postBeforeNotificationEnrichment(
            post = { postInitialNotificationUpdate(update, firstPost, receivedAtElapsedMs) },
            scheduleEnrichment = {
                scheduleNotificationEnrichment(update, firstPost, receivedAtElapsedMs)
            },
        )
    } finally {
        notificationProfileRepeats.finish(update, posted = false, nowMs = System.currentTimeMillis())
    }
    schedulePostNotificationMaintenance(update)
}

/**
 * Drops opaque reaction quotes while a removal for this chat remains in the bounded process guard (#3323).
 * Already-queued quotes retain the account-version fence across identity eviction. Persisted hides alone do
 * not suppress quotes after restart; new targets after eviction/restart await MDK #2366. Reactions still post.
 */
private fun WhiteNoiseAppState.withoutUnverifiableReactionQuote(update: NotificationUpdateFfi): NotificationUpdateFfi {
    val removed = notificationContentInvalidations.hasRemovalInConversation(update.accountRef, update.groupIdHex)
    return if (LocalNotificationFormatter.isReaction(update) && removed) {
        update.copy(reactedToPreview = null)
    } else {
        update
    }
}

/** Resolved overrides must obey the same quote removal as the original typed update. */
private fun WhiteNoiseAppState.verifiedReactionPreview(
    update: NotificationUpdateFfi,
    preview: String?,
): String? {
    val quoteUnavailable = LocalNotificationFormatter.isReaction(update) && update.reactedToPreview.isNullOrBlank()
    return preview.takeUnless { quoteUnavailable }
}

/** Drops only an unverified quote; unrelated removals must not discard the reaction itself. */
private fun WhiteNoiseAppState.withoutStaleQueuedReactionQuote(
    update: NotificationUpdateFfi,
    firstPost: NotificationFirstPost,
): NotificationUpdateFfi =
    if (isQueuedReactionQuoteCurrent(update, firstPost)) {
        withoutUnverifiableReactionQuote(update)
    } else {
        update.copy(reactedToPreview = null)
    }

/** Retries once without its quote if a removal races the final write; every other fence is checked again. */
private suspend fun WhiteNoiseAppState.withVerifiedReactionQuote(
    update: NotificationUpdateFfi,
    firstPost: NotificationFirstPost,
    post: suspend (NotificationUpdateFfi, () -> Boolean) -> Boolean,
): Boolean =
    localNotificationPresenter.withShowLifetime(update) { isNotDismissed ->
        val current = withoutStaleQueuedReactionQuote(update, firstPost)
        if (!isNotDismissed()) return@withShowLifetime false
        if (post(current, isNotDismissed)) return@withShowLifetime true
        val unquoted = withoutStaleQueuedReactionQuote(current, firstPost)
        unquoted != current && isNotDismissed() && post(unquoted, isNotDismissed)
    }

/** Exercises the exact typed-update production path from JVM or instrumentation acceptance tests. */
internal suspend fun WhiteNoiseAppState.processNotificationUpdateForTest(update: NotificationUpdateFfi) {
    withContext(Dispatchers.Main.immediate) { processNotificationUpdate(update) }
}

/** Runs receipt scheduling to completion, so a test can assert that a receipt queued nothing. */
internal suspend fun WhiteNoiseAppState.scheduleIncomingAttachmentDownloadsForTest(update: NotificationUpdateFfi) {
    scheduleIncomingAttachmentDownloads(update)
}

/** Resolves all first-write content under the coordinator's one absolute budget. */
private suspend fun WhiteNoiseAppState.resolveNotificationFirstPost(
    update: NotificationUpdateFfi,
    postEpoch: Long,
    accountCacheEpoch: Long,
    engineMuted: Boolean,
    shouldPost: Boolean,
    receivedAtElapsedMs: Long,
): NotificationFirstPost {
    val stage = notificationFirstPostContentCoordinator.startStage()
    val result =
        if (shouldPost && !appLock.screenVisible) {
            notificationFirstPostContentCoordinator.resolve(stage) {
                // Every card draws its sender, in a group too (#3635), so their private picture is warmed.
                warmPrivateContactAvatar(update.accountRef, update.sender.accountIdHex)
                notificationContentResolution.firstPost.resolve(update, localOnly = true)
            }
        } else {
            null
        }
    result?.let {
        recordNotificationFirstPostTiming(
            stage = NotificationFirstPostTimingStage.ContentComplete,
            receivedAtElapsedMs = receivedAtElapsedMs,
            stageStartedAtElapsedMs = stage.startedAtElapsedMillis,
            outcome = it.timingOutcome(),
        )
    }
    val content =
        when (result) {
            is NotificationFirstPostContentResult.Resolved -> result.value
            else -> null
        }
    return NotificationFirstPost(
        epoch = postEpoch,
        accountCacheEpoch = accountCacheEpoch,
        engineMuted = engineMuted,
        shouldPost = shouldPost,
        content = content,
        presentation = notificationContentPresentation(appContext, update, content, ::cachedShortNpubOrUnknown),
        avatars = readyNotificationAvatars(update),
    )
}

/**
 * Snapshots decoded local imagery without hydration; the existing monogram covers misses. Every card's sender is
 * drawn, in a group too (#3635). The conversation picture prefers the pixels the chat list already decoded for the
 * row's stored selected avatar, before the URL and encrypted-image caches. The snapshot names the row's peer whose
 * private picture the row shows instead, but never holds that picture: the card and its shortcut apply the choice
 * current at each write.
 */
internal fun WhiteNoiseAppState.readyNotificationAvatars(update: NotificationUpdateFfi): PreWarmedNotificationAvatars {
    if (appLock.screenVisible) return PreWarmedNotificationAvatars(null, null)
    val senderUrl =
        synchronized(profilePresentationLock) {
            profilePresentations[update.sender.accountIdHex]?.avatarUrl
        } ?: update.sender.pictureUrl
    val item = notificationChatListItem(update)
    val group = item?.group
    val ready = readyNotificationAvatarSnapshot(senderUrl, group?.avatarUrl)
    val encryptedKey = group?.let { encryptedGroupAvatarCacheKey(update.accountRef, it) }
    return ready
        .copy(
            groupAvatarBitmap =
                ready.groupAvatarBitmap ?: GroupAvatarImageLoader.peek(encryptedKey)?.asAndroidBitmap(),
        ).withChatRow(item, update.accountRef)
}

/**
 * Reuses the receiver's bound row. A named group's message or reaction can arrive before the chat list
 * replaces its pending invite, which still shows the inviter instead of the group.
 */
internal fun WhiteNoiseAppState.notificationChatListItem(update: NotificationUpdateFfi): ChatListItem? =
    chatsController
        ?.takeIf { it.boundAccountRef == update.accountRef && update.accountRef == activeAccountRef }
        ?.items
        ?.firstOrNull { it.id.equals(update.groupIdHex, ignoreCase = true) }
        ?.takeUnless {
            it.group.pendingConfirmation &&
                update.trigger == NotificationTriggerFfi.NEW_MESSAGE &&
                !update.groupName.isNullOrBlank()
        }

/** Publishes one fixed-label timing event without notification identity or content. */
private fun WhiteNoiseAppState.recordNotificationFirstPostTiming(
    stage: NotificationFirstPostTimingStage,
    receivedAtElapsedMs: Long,
    stageStartedAtElapsedMs: Long? = null,
    outcome: String,
) {
    val now = SystemClock.elapsedRealtime()
    val event =
        NotificationFirstPostTimingEvent(
            stage = stage,
            observedAtElapsedRealtimeNanos = SystemClock.elapsedRealtimeNanos(),
            elapsedSinceReceiptMillis = (now - receivedAtElapsedMs).coerceAtLeast(0L),
            stageElapsedMillis = stageStartedAtElapsedMs?.let { (now - it).coerceAtLeast(0L) },
            outcome = outcome,
        )
    appStateDebug {
        "notification timing stage=${stage.name} elapsed_ms=${event.elapsedSinceReceiptMillis} " +
            "stage_ms=${event.stageElapsedMillis ?: -1L} outcome=$outcome"
    }
    notificationFirstPostTimingObserver?.invoke(event)
}

private fun WhiteNoiseAppState.schedulePostNotificationMaintenance(update: NotificationUpdateFfi) {
    if (networkNotificationRecoverySuppressed) return
    unreadRefreshScheduler.schedule(update.accountRef)
    signalNotificationDrain()
}

internal fun WhiteNoiseAppState.scheduleInviteNotificationIdentityRefresh(
    senderAccountIdHex: String,
    presentation: ProfilePresentation,
) {
    val candidates =
        inviteNotificationIdentityRefreshStore.refreshCandidates(
            senderAccountIdHex = senderAccountIdHex,
            resolvedName = presentation.displayName,
        )
    candidates.forEach(::launchInviteNotificationIdentityRefresh)
}

internal fun WhiteNoiseAppState.resumePendingInviteNotificationIdentityRefreshes() {
    inviteNotificationIdentityRefreshStore
        .claimPendingRefreshes()
        .forEach(::launchInviteNotificationIdentityRefresh)
}

/** Runs one invite-name correction under the original post generations and shared write permit. */
private fun WhiteNoiseAppState.launchInviteNotificationIdentityRefresh(initialCandidate: RefreshCandidate) {
    val update = initialCandidate.identity.asNotificationUpdate()
    profileScope.launch {
        inviteNotificationIdentityRefreshStore.runClaimedRefresh(initialCandidate) {
            refreshInviteNotificationIdentity(initialCandidate, update)
        }
    }
}

/** Applies stale, lock-deferred, single-writer, and active-card rules to one invite candidate. */
private suspend fun WhiteNoiseAppState.refreshInviteNotificationIdentity(
    candidate: RefreshCandidate,
    update: NotificationUpdateFfi,
) {
    when {
        !isInviteNotificationRefreshCurrent(candidate, update) ->
            inviteNotificationIdentityRefreshStore.forget(candidate)

        appLock.screenVisible -> deferInviteNotificationIdentityRefresh(candidate)

        !isNotificationEnrichmentAllowed(
            update,
            candidate.postEpoch,
            candidate.accountCacheEpoch,
            candidate.engineMuted,
        ) -> inviteNotificationIdentityRefreshStore.forget(candidate)

        !candidate.lateCorrectionPermit.acquire() ->
            inviteNotificationIdentityRefreshStore.forget(candidate)

        else -> postInviteNotificationIdentityCorrection(candidate, update)
    }
}

/** Requires both captured generations and the exact active invite card. */
private fun WhiteNoiseAppState.isInviteNotificationRefreshCurrent(
    candidate: RefreshCandidate,
    update: NotificationUpdateFfi,
): Boolean =
    inviteNotificationIdentityRefreshStore.isCurrent(candidate) &&
        isNotificationGenerationPostAllowed(
            update = update,
            postEpoch = candidate.postEpoch,
            accountCacheEpoch = candidate.accountCacheEpoch,
            engineMuted = candidate.engineMuted,
            requireUnlocked = false,
        ) &&
        localNotificationPresenter.isGroupInviteNotificationActive(update)

/** Releases a locked candidate and closes the unlock/reclaim race. */
private fun WhiteNoiseAppState.deferInviteNotificationIdentityRefresh(candidate: RefreshCandidate) {
    inviteNotificationIdentityRefreshStore.release(candidate)
    if (!appLock.screenVisible) resumePendingInviteNotificationIdentityRefreshes()
}

/** Consumes or restores the shared write permit according to the platform result. */
private suspend fun WhiteNoiseAppState.postInviteNotificationIdentityCorrection(
    candidate: RefreshCandidate,
    update: NotificationUpdateFfi,
) {
    var correctionPosted = false
    try {
        if (!isInviteNotificationRefreshCurrent(candidate, update)) {
            inviteNotificationIdentityRefreshStore.forget(candidate)
            return
        }
        val result =
            refreshActiveInviteNotificationIdentity(
                update = update,
                resolvedProfileName = candidate.resolvedName,
                postEpoch = candidate.postEpoch,
                accountCacheEpoch = candidate.accountCacheEpoch,
                engineMuted = candidate.engineMuted,
                isCandidateCurrent = { inviteNotificationIdentityRefreshStore.isCurrent(candidate) },
            ).also { correctionPosted = it.posted }
        when {
            result.posted -> inviteNotificationIdentityRefreshStore.forget(candidate)
            !localNotificationPresenter.isGroupInviteNotificationActive(update) ->
                inviteNotificationIdentityRefreshStore.forget(candidate)
            else -> inviteNotificationIdentityRefreshStore.release(candidate)
        }
    } finally {
        candidate.lateCorrectionPermit.complete(correctionPosted)
    }
}

/** Re-renders an active invite only if its captured account and notification generation remain current. */
private suspend fun WhiteNoiseAppState.refreshActiveInviteNotificationIdentity(
    update: NotificationUpdateFfi,
    resolvedProfileName: String?,
    postEpoch: Long,
    accountCacheEpoch: Long,
    engineMuted: Boolean,
    isCandidateCurrent: () -> Boolean,
): InviteNotificationIdentityRefreshResult {
    val skipEnrichmentForLock = appLock.screenVisible
    val resolvedName =
        if (skipEnrichmentForLock) {
            null
        } else {
            notificationContentResolution.identity.senderName(update)
                ?: resolvedProfileName
                ?: notificationDisplayNameHint(update.sender.displayName)
        }
    // A lock can arrive during the suspending lookup above. Re-check after
    // enrichment so the silent update cannot reveal identity behind the lock.
    val redactContent = skipEnrichmentForLock || appLock.screenVisible
    val displayedName = resolvedName.takeUnless { redactContent }
    val posted =
        localNotificationPresenter.show(
            update = update,
            senderNameOverride = displayedName,
            recipientAccountSubtext =
                if (redactContent) {
                    null
                } else {
                    notificationContentResolution.firstPost.recipientAccountSubtext(
                        update,
                        localOnly = false,
                    )
                },
            redactContent = redactContent,
            directShareEligible = !redactContent && update.accountRef == activeAccountRef,
            silentUpdate = true,
            shortNpub = ::shortNpub,
            isPostStillAllowed = {
                isCandidateCurrent() &&
                    isNotificationEnrichmentAllowed(
                        update,
                        postEpoch,
                        accountCacheEpoch,
                        engineMuted,
                    )
            },
        )
    return InviteNotificationIdentityRefreshResult(
        posted = posted,
        displayedName = displayedName,
        contentRedacted = redactContent,
    )
}
