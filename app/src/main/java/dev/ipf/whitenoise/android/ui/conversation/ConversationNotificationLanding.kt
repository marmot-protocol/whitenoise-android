package dev.ipf.whitenoise.android.ui.conversation

import androidx.annotation.StringRes
import androidx.compose.runtime.withFrameNanos
import dev.ipf.whitenoise.android.R
import dev.ipf.whitenoise.android.core.MessageProjector
import dev.ipf.whitenoise.android.notifications.NotificationTarget
import dev.ipf.whitenoise.android.notifications.NotificationTargetKind
import dev.ipf.whitenoise.android.state.ConversationController
import dev.ipf.whitenoise.android.state.ConversationUnreadJumpState
import dev.ipf.whitenoise.android.state.MessageAvailability
import dev.ipf.whitenoise.android.state.TimelineMessage
import dev.ipf.whitenoise.android.state.isDerivedStateKind
import dev.ipf.whitenoise.android.state.loadMessageAvailability

/** Frames a hidden landing may spend retrying an unmeasurable row before the ordinary entry takes over. */
private const val MAX_LANDING_COMMIT_ATTEMPTS = 24

/**
 * A notification card's captured destination, paired with the account and group it was issued for so
 * a stale or foreign tap can never steer another conversation's scroll position.
 */
internal data class NotificationLandingTarget(
    val accountRef: String,
    val groupIdHex: String,
    val messageIdHex: String,
) {
    /** The message id only when this target was captured for exactly the bound account and group. */
    fun messageIdFor(
        boundAccountRef: String?,
        boundGroupIdHex: String,
    ): String? =
        messageIdHex.takeIf {
            it.isNotBlank() &&
                accountRef == boundAccountRef &&
                groupIdHex.equals(boundGroupIdHex, ignoreCase = true)
        }
}

/**
 * The landing destination of a plain message-card tap. A summary, an invite, a pinned shortcut and a
 * reply-draft handoff carry no destination message of their own, so they keep the existing entry.
 */
internal fun NotificationTarget.toLandingTarget(): NotificationLandingTarget? {
    val plainMessageCard = kind == NotificationTargetKind.MESSAGE && replyDraft == null && shortcutCapability == null
    return messageIdHex
        ?.takeIf { plainMessageCard && it.isNotBlank() }
        ?.let { NotificationLandingTarget(accountRef, groupIdHex, it) }
}

/** How a notified message's landing ended, which decides whether the ordinary entry still has to run. */
internal enum class NotificationLandingOutcome {
    /** The notified message's beginning is placed and the transcript may reveal. */
    LANDED,

    /** No landing happened, so the ordinary oldest-unread or tail entry positions the transcript. */
    FALLBACK,

    /** The owning screen left while the landing waited, so nothing may be written. */
    ABANDONED,
}

/** What the screen does with a landing's result, kept out of the entry effect so it stays presentation-only. */
internal data class ConversationNotificationLandingCallbacks(
    val beginNavigation: () -> MessageTargetNavigationOwner.Request = { MessageTargetNavigationOwner().begin() },
    val onLanded: (backlogMessageId: String?) -> Unit = {},
    val onUnavailable: (MessageAvailability) -> Unit = {},
)

/** The message a notification tap resolves to, or why the ordinary entry must keep the position. */
internal sealed interface NotificationLandingResolution {
    /** The row to land on, which is the reacted message when the card carried a reaction. */
    data class Resolved(
        val messageIdHex: String,
    ) : NotificationLandingResolution

    /** A group system row keeps the ordinary entry, which is where those notifications always opened. */
    data object KeepEntry : NotificationLandingResolution

    /** The message is gone or cannot be reached now, which the reader is told rather than guessed around. */
    data class Unavailable(
        val availability: MessageAvailability,
    ) : NotificationLandingResolution
}

/**
 * Resolves the card's message by identity through the exact MDK window jump and its bounded page
 * fallback, never through the unbounded first-unread loader. A reaction card carries the kind-7
 * event id, so a missing source is retried as the reacted message before it is called unavailable.
 */
@Suppress("ReturnCount") // Guard clauses keep the ordered availability, reaction and classification steps explicit.
internal suspend fun ConversationController.resolveLandingTarget(sourceId: String): NotificationLandingResolution {
    var availability = loadMessageAvailability(sourceId)
    var targetId = sourceId
    if (availability == MessageAvailability.MISSING) {
        loadScrollNavigationTarget(sourceId)?.let { reactedId ->
            targetId = reactedId
            availability = MessageAvailability.AVAILABLE
        }
    }
    if (availability != MessageAvailability.AVAILABLE) return NotificationLandingResolution.Unavailable(availability)
    val record =
        timeline.firstOrNull { it.record.messageIdHex == targetId && !MessageProjector.isEdit(it.record) }?.record
            ?: return NotificationLandingResolution.Unavailable(MessageAvailability.MISSING)
    return if (isDerivedStateKind(record.kind)) {
        NotificationLandingResolution.KeepEntry
    } else {
        NotificationLandingResolution.Resolved(targetId)
    }
}

/**
 * Plans a landing from the rendered window only, never from a cached or invented history. The anchor
 * offset is a placeholder until the measured settle reports where the row's beginning really sits.
 */
internal fun conversationViewportNotificationLandingPosition(
    rows: List<Pair<String, String>>,
    targetMessageId: String,
    trailingRowCount: Int,
): ConversationViewportInitialPosition? {
    val timelineIndex = rows.indexOfFirst { it.second == targetMessageId }
    if (timelineIndex < 0) return null
    return ConversationViewportInitialPosition(
        anchor =
            ConversationScrollAnchor(
                listIndex = conversationTimelineListIndex(timelineIndex, rows.size, trailingRowCount),
                pixelOffset = 0,
                itemId = rows[timelineIndex].first,
                messageId = targetMessageId,
            ),
        mode = ConversationScrollMode.ReadingHistory(targetMessageId, 0),
        reason = ConversationScrollReason.NotificationTarget,
        readingStart = true,
    )
}

/** Tells the reader why the card's message was not opened: gone for good, or not reachable right now. */
@StringRes
internal fun notificationLandingFeedback(availability: MessageAvailability): Int =
    if (availability == MessageAvailability.RETRYABLE) {
        R.string.error_loaded_content_kept
    } else {
        R.string.toast_original_message_unavailable
    }

/**
 * Seeds the two-stage jump button after a landing, so it still leads back to the older unread backlog
 * that the landing deliberately left above the viewport. Without a loaded backlog it changes nothing.
 */
internal fun ConversationUnreadJumpState.seedBacklogAfterLanding(backlogId: String?): ConversationUnreadJumpState =
    if (backlogId == null) {
        this
    } else {
        ConversationUnreadJumpState(pendingMessageId = backlogId, unreadStackActive = true, initialized = true)
    }

/** The signed reverse-list geometry a reading-start settle reads: the clear viewport end and the row's height. */
internal fun ConversationTimelineViewport.readingStartLayout(index: Int): ConversationMentionJumpLayout {
    val layout = readingLayoutInfo()
    return ConversationMentionJumpLayout(
        viewportEndOffsetPx = layout.viewportEndOffset,
        itemHeightPx = layout.visibleItemsInfo.firstOrNull { it.index == index }?.size,
    )
}

/**
 * One entry's initial positioning: the notified message's beginning when a verified card target is
 * present and reachable, otherwise the oldest-unread boundary or the tail. The unread snapshot is
 * frozen by the screen before this runs, and nothing here marks a message read.
 */
internal class ConversationEntryPositioning(
    private val controller: ConversationController,
    private val viewport: ConversationTimelineViewport,
    private val owner: ConversationViewportRestorationOwner,
    private val inputs: ConversationViewportRestorationInputs,
    private val callbacks: ConversationViewportRestorationCallbacks,
) {
    /** Lands on the notified message when possible, then falls back to the ordinary unread entry. */
    suspend fun run() {
        val sourceId = inputs.notificationTargetMessageId
        if (sourceId != null && land(sourceId) != NotificationLandingOutcome.FALLBACK) return
        enterAtUnreadBoundary()
    }

    /** Resolves the card's message under a latest-wins navigation request and lands on it if still current. */
    private suspend fun land(sourceId: String): NotificationLandingOutcome {
        val request = callbacks.notification.beginNavigation()
        val resolution = controller.resolveLandingTarget(sourceId)
        return when {
            !owner.isActive -> NotificationLandingOutcome.ABANDONED
            !request.isCurrent() -> NotificationLandingOutcome.FALLBACK
            resolution is NotificationLandingResolution.Resolved -> landOn(resolution.messageIdHex)
            else -> {
                if (resolution is NotificationLandingResolution.Unavailable) {
                    callbacks.notification.onUnavailable(resolution.availability)
                }
                NotificationLandingOutcome.FALLBACK
            }
        }
    }

    /** Commits the reading start of [targetId], then seeds the unread-jump button with the older backlog. */
    private suspend fun landOn(targetId: String): NotificationLandingOutcome {
        val rendered = renderedTimeline()
        val position =
            conversationViewportNotificationLandingPosition(
                rendered.map { it.id to it.record.messageIdHex },
                targetId,
                controller.conversationTrailingRowCount(rendered.size),
            ) ?: return NotificationLandingOutcome.FALLBACK
        val structure = controller.conversationTimelineStructure()
        val committed = commitLanding(position)
        return if (committed == NotificationLandingOutcome.LANDED) {
            finishLanding(position, structure, rendered)
        } else {
            committed
        }
    }

    /** Settles a committed landing, then points the unread-jump button back at the older backlog it left above. */
    private fun finishLanding(
        position: ConversationViewportInitialPosition,
        structure: ConversationTimelineStructure,
        rendered: List<TimelineMessage>,
    ): NotificationLandingOutcome {
        if (!complete(position, structure, rendered)) return NotificationLandingOutcome.ABANDONED
        val targetId = position.anchor.messageId
        val backlogId =
            inputs.entryUnread.firstUnreadMessageId?.takeIf { unreadId ->
                unreadId != targetId && rendered.any { it.record.messageIdHex == unreadId }
            }
        callbacks.notification.onLanded(backlogId)
        return NotificationLandingOutcome.LANDED
    }

    /** Retries across frames while the row is unmeasurable, within a bound so the reveal can never hang. */
    private suspend fun commitLanding(position: ConversationViewportInitialPosition): NotificationLandingOutcome {
        val probe =
            ConversationReadingStartProbe(
                resolveTargetIndex = { callbacks.navigation.resolveAnchor(position.anchor) },
                readLayout = viewport::readingStartLayout,
                traceSections = false,
            )
        val captureLayout = { viewport.initialAnchorLayout(position.index) }
        var attempts = 0
        var stopped: NotificationLandingOutcome? = null
        while (stopped == null && !owner.commitInitialPosition(position, captureLayout, readingStartProbe = probe)) {
            stopped =
                when {
                    !owner.isActive -> NotificationLandingOutcome.ABANDONED
                    ++attempts >= MAX_LANDING_COMMIT_ATTEMPTS -> NotificationLandingOutcome.FALLBACK
                    else -> null
                }
            if (stopped == null) withFrameNanos { }
        }
        return stopped ?: NotificationLandingOutcome.LANDED
    }

    /** The existing oldest-unread or tail entry, unchanged for every open that has no landing target. */
    @Suppress("ReturnCount") // Guard clauses keep the entry's ordered cancellation checks explicit.
    private suspend fun enterAtUnreadBoundary() {
        val unreadId =
            resolveConversationEntryUnreadMessageId(
                snapshot = inputs.entryUnread,
                timeline = { controller.timeline },
                loadUntilMessageAvailable = controller::loadConversationEntryUnreadMessageAvailable,
            )
        if (!owner.isActive) return
        val rendered = renderedTimeline()
        val structure = controller.conversationTimelineStructure()
        val position =
            conversationViewportEntryPosition(
                rendered.map { it.id to it.record.messageIdHex },
                unreadId,
                controller.conversationTrailingRowCount(rendered.size),
            ) ?: return
        if (hasSentMessageAfterUnreadBoundary(rendered, unreadId)) callbacks.retireUnreadDivider()
        while (!owner.commitInitialPosition(position, { viewport.initialAnchorLayout(position.index) })) {
            if (!owner.isActive) return
            withFrameNanos { }
        }
        complete(position, structure, rendered)
    }

    /** Settles the committed position and publishes the anchored timeline, false once the owner is gone. */
    private fun complete(
        position: ConversationViewportInitialPosition,
        structure: ConversationTimelineStructure,
        rendered: List<TimelineMessage>,
    ): Boolean {
        val committedStructure =
            structure.copy(groupRecoveryCount = if (controller.conversationGroupRecoveryRowVisible()) 1 else 0)
        val completed = owner.completeInitialPosition(position, committedStructure, viewport.height())
        if (completed) callbacks.onAnchored(rendered.lastOrNull()?.id)
        return completed
    }

    /** The edit-filtered rows the transcript actually renders, the same projection every scroll decision uses. */
    private fun renderedTimeline() = controller.timeline.filterNot { MessageProjector.isEdit(it.record) }
}
