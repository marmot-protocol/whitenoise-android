package dev.ipf.whitenoise.android.ui.conversation.media

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import dev.ipf.marmotkit.MediaAttachmentReferenceFfi
import dev.ipf.whitenoise.android.core.ForwardEligibility
import dev.ipf.whitenoise.android.core.ForwardMessagePayload
import dev.ipf.whitenoise.android.core.MessageProjector
import dev.ipf.whitenoise.android.core.usesPersistedFailurePresentation
import dev.ipf.whitenoise.android.state.ConversationController
import dev.ipf.whitenoise.android.state.MessageStatus
import dev.ipf.whitenoise.android.state.PendingForwardRequest
import dev.ipf.whitenoise.android.state.TimelineMessage
import dev.ipf.whitenoise.android.state.WhiteNoiseAppState
import dev.ipf.whitenoise.android.state.attachmentsFor
import dev.ipf.whitenoise.android.state.mediaReferencesFor
import dev.ipf.whitenoise.android.ui.conversation.messages.ForwardMessageSheet
import dev.ipf.whitenoise.android.ui.conversation.rememberForwardEligibilityNowSeconds
import java.util.UUID

/** Rejects detached or replaced-page callbacks, including changes to epoch, reference, and mine. */
internal class MediaViewerActionGate(
    private val owner: ConversationMediaViewerOwner,
) {
    var currentPage: MediaViewerPage? = null
    private var attached = true

    /** Detaches the actions from the viewer. */
    fun close() {
        attached = false
    }

    /** Runs [action] for the page when the owner is still current. */
    fun dispatch(
        page: MediaViewerPage,
        currentOwner: ConversationMediaViewerOwner,
        action: (MediaViewerPage) -> Unit,
    ) {
        val ownerMatches = attached && owner.accountRef != null && currentOwner == owner
        if (ownerMatches && currentPage == page) action(page)
    }
}

/** The viewer forwards a complete native source message, never a renumbered subset of its album. */
@Suppress("LongParameterList")
internal fun mediaViewerForwardPayload(
    page: MediaViewerPage,
    item: TimelineMessage,
    references: List<MediaAttachmentReferenceFfi>,
    available: Boolean,
    cachedAttachmentIndices: Set<Int>,
    nowSeconds: ULong,
    editedText: String? = null,
): ForwardMessagePayload? {
    val sourceMatches =
        item.record.messageIdHex == page.messageIdHex &&
            references.getOrNull(page.attachmentIndex) == page.reference
    val recordEpoch = item.record.sourceEpoch?.takeIf { it > 0uL }
    val epochMatches = recordEpoch == null || recordEpoch == page.reference.sourceEpoch
    val committed = item.status == MessageStatus.Sent || item.status == MessageStatus.Received
    val invalid =
        item.projected?.deleted == true ||
            item.projected?.invalidationStatus != null ||
            item.projected?.let(::usesPersistedFailurePresentation) == true
    val sourceIntact = sourceMatches && epochMatches && committed && !invalid
    if (!available || !sourceIntact) return null
    return (
        MessageProjector.forwardEligibility(
            message = item.record,
            mediaReferences = references,
            editedText = editedText,
            cachedAttachmentIndices = cachedAttachmentIndices,
            nowSeconds = nowSeconds,
        ) as? ForwardEligibility.Eligible
    )?.payload
}

/** A parent-owned picker admission boundary; true means the native picker accepted this exact source. */
internal class MediaViewerForwardActions(
    val canForward: (MediaViewerPage) -> Boolean,
    val forward: (MediaViewerPage) -> Boolean,
)

/**
 * Keeps the existing encrypted forward request and picker outside the transient media dialog.
 * Source changes cancel the request; native forwarding still owns destination selection, retention,
 * transport, retry, and process recreation. No saved-state Bundle receives message content.
 */
@Composable
@Suppress("LongMethod", "CyclomaticComplexMethod")
internal fun rememberMediaViewerForwardActions(
    controller: ConversationController,
    appState: WhiteNoiseAppState,
): MediaViewerForwardActions {
    val owner =
        remember(controller) {
            ConversationMediaViewerOwner(
                controller.boundAccountRef,
                controller.group.groupIdHex,
                appState.runtimeGeneration,
            )
        }
    var attached by remember(controller) { mutableStateOf(true) }
    var request by remember(controller) { mutableStateOf<PendingForwardRequest?>(null) }
    var requestedPage by remember(controller) { mutableStateOf<MediaViewerPage?>(null) }
    val retentionExpiries =
        remember(controller.timeline) {
            controller.timeline.mapNotNull { it.record.retentionExpiresAt }
        }
    val nowSeconds = rememberForwardEligibilityNowSeconds(retentionExpiries)
    val mediaCacheRevision by appState.mediaCacheRevision.collectAsState()
    val hasCachedAttachment = remember(controller, mediaCacheRevision) { controller::hasCachedAttachment }

    /** True while the viewer's account and conversation are still the active ones. */
    fun ownerIsCurrent(): Boolean =
        attached &&
            owner.accountRef != null &&
            appState.activeAccountRef == owner.accountRef &&
            appState.runtimeGeneration == owner.runtimeGeneration &&
            controller.boundAccountRef == owner.accountRef &&
            controller.group.groupIdHex == owner.conversationId

    /** Forward payload for the page, or null when its message is gone or expired. */
    fun payloadFor(
        page: MediaViewerPage,
        atSeconds: ULong,
    ): ForwardMessagePayload? {
        val item = controller.timeline.firstOrNull { it.record.messageIdHex == page.messageIdHex }
        if (!ownerIsCurrent() || controller.group.pendingConfirmation || item == null) return null
        val references = controller.mediaReferencesFor(item)
        val available =
            item.record.groupIdHex == owner.conversationId &&
                controller.isMessageMine(item.record) == page.mine &&
                page.messageIdHex !in controller.deletedMessageIds &&
                page.messageIdHex !in controller.pendingTimelineRemovedMessageIds
        return mediaViewerForwardPayload(
            page,
            item,
            references,
            available,
            controller
                .attachmentsFor(item)
                .map { it.index }
                .filterTo(mutableSetOf()) { hasCachedAttachment(page.messageIdHex, it) },
            atSeconds,
            controller.editsByTarget[page.messageIdHex]?.latestText,
        )
    }

    DisposableEffect(controller) {
        onDispose {
            attached = false
            // A changed owner must not leave a request that can surface under another account/runtime.
            val changedOwner =
                appState.activeAccountRef != owner.accountRef ||
                    appState.runtimeGeneration != owner.runtimeGeneration
            if (changedOwner) {
                request?.let { appState.forwardRequestPersistence.discard(it.requestId) }
            }
        }
    }
    val activeRequest = request
    val currentPayload = requestedPage?.let { payloadFor(it, nowSeconds) }
    val requestValid = activeRequest != null && activeRequest.payloads.singleOrNull() == currentPayload
    LaunchedEffect(activeRequest?.requestId, requestValid) {
        if (activeRequest != null && !requestValid) {
            appState.forwardRequestPersistence.discard(activeRequest.requestId)
            request = null
            requestedPage = null
        }
    }
    if (activeRequest != null && requestValid) {
        ForwardMessageSheet(
            appState = appState,
            payloads = activeRequest.payloads,
            sourceAccountRef = owner.accountRef,
            originGroupIdHex = owner.conversationId,
            restoredRequest = activeRequest,
            onDismiss = {
                request = null
                requestedPage = null
            },
        )
    }
    return MediaViewerForwardActions(
        canForward = { request == null && payloadFor(it, nowSeconds) != null },
        forward = { page ->
            val payload = payloadFor(page, (System.currentTimeMillis().coerceAtLeast(0L) / 1_000L).toULong())
            if (request != null || payload == null) {
                false
            } else {
                requestedPage = page
                request =
                    PendingForwardRequest(
                        requestId = UUID.randomUUID().toString(),
                        sourceAccountRef = requireNotNull(owner.accountRef),
                        originGroupIdHex = owner.conversationId,
                        payloads = listOf(payload),
                        destinationAccountRef = null,
                        selectedGroupIds = emptyList(),
                    )
                true
            }
        },
    )
}
