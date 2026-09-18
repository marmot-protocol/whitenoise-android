package dev.ipf.whitenoise.android.state

import android.util.Log
import dev.ipf.marmotkit.AppGroupRecordFfi
import dev.ipf.marmotkit.ConversationOpenModeFfi
import dev.ipf.marmotkit.GroupStateSubscription
import dev.ipf.marmotkit.MarmotInterface
import dev.ipf.marmotkit.MarmotKitException
import dev.ipf.marmotkit.TimelineMessagesSubscription
import dev.ipf.marmotkit.TimelinePageFfi
import dev.ipf.whitenoise.android.state.TimelinePageOutcome.Advanced
import kotlinx.coroutines.CancellationException

/**
 * Lifecycle-shaped seam for conversation live subscriptions. Production binds
 * UniFFI handles; tests supply scripted implementations without subclassing FFI
 * types.
 */
internal interface ConversationTimelineSubscriptionHandle {
    /** Returns the initial authoritative window without waiting for a live update. */
    fun snapshot(): TimelinePageFfi?

    /** Returns MDK's next authoritative, ordered, and bounded timeline window. */
    suspend fun nextWindow(): TimelinePageFfi?

    /**
     * Loads the preceding bounded window while retaining the live subscription, reporting why the
     * window stayed put when it did.
     *
     * Seams without a real window (the legacy timeline subscription, test doubles) cannot tell a
     * deadline from exhausted history, so they report every page as having advanced — which is what
     * a page-shaped result already meant for them.
     */
    suspend fun paginateBackwards(count: UInt): TimelinePageOutcome

    /** Loads the following bounded window while retaining the live subscription. */
    suspend fun paginateForwards(count: UInt): TimelinePageOutcome

    /** Releases the underlying MDK subscription. */
    fun close()

    /** Sidecar of the newest installed window replacement; null on seams without a window. */
    fun latestWindowFrame(): ConversationWindowFrame? = latestInstalledWindow()?.frame

    /** Page and sidecar of the newest installed replacement as one revision; null on seams without a window. */
    fun latestInstalledWindow(): InstalledConversationWindow? = null

    /** Reports the row the reader sees; null when the seam has no window or nothing newer was installed. */
    suspend fun setVisibleAnchor(messageIdHex: String): TimelinePageFfi? = null

    /** Recenters on a retained message; throws `ConversationWindowMessageNotRetained` when MDK dropped it. */
    suspend fun jumpToMessage(messageIdHex: String): TimelinePageFfi? = null

    /** Resumes following the tail; null when the seam has no window or nothing newer was installed. */
    suspend fun returnToLatest(): TimelinePageFfi? = null

    /** Wakes pending window operations before [close]; a no-op on seams without a window. */
    suspend fun cancel() = Unit
}

internal interface ConversationGroupStateSubscriptionHandle {
    fun snapshot(): AppGroupRecordFfi?

    suspend fun next(): AppGroupRecordFfi?

    fun close()
}

internal class FfiConversationTimelineSubscriptionHandle(
    private val subscription: TimelineMessagesSubscription,
) : ConversationTimelineSubscriptionHandle {
    /** Delegates the initial-window read to the UniFFI subscription. */
    override fun snapshot(): TimelinePageFfi? = subscription.snapshot()

    /** Delegates the next complete-window read to MDK rather than consuming deltas. */
    override suspend fun nextWindow(): TimelinePageFfi? = subscription.next()

    /** Delegates backward pagination to the active MDK subscription. */
    override suspend fun paginateBackwards(count: UInt): TimelinePageOutcome {
        val page = subscription.paginateBackwards(count)
        return Advanced(page)
    }

    /** Delegates forward pagination to the active MDK subscription. */
    override suspend fun paginateForwards(count: UInt): TimelinePageOutcome {
        val page = subscription.paginateForwards(count)
        return Advanced(page)
    }

    /** Closes the UniFFI subscription handle. */
    override fun close() = subscription.close()
}

internal class FfiConversationGroupStateSubscriptionHandle(
    private val subscription: GroupStateSubscription,
) : ConversationGroupStateSubscriptionHandle {
    override fun snapshot(): AppGroupRecordFfi? = subscription.snapshot()

    override suspend fun next(): AppGroupRecordFfi? = subscription.next()

    override fun close() = subscription.close()
}

internal class ConversationLiveSubscriptions(
    val openTimeline: suspend (
        account: String,
        groupIdHex: String,
        limit: UInt,
    ) -> ConversationTimelineSubscriptionHandle,
    val openGroupState: suspend (
        account: String,
        groupIdHex: String,
    ) -> ConversationGroupStateSubscriptionHandle,
) {
    companion object {
        /** Binds production subscription seams to the app state's serialized MDK access. */
        fun bind(appState: WhiteNoiseAppState): ConversationLiveSubscriptions =
            ConversationLiveSubscriptions(
                openTimeline = { account, groupIdHex, limit ->
                    // Traced on its own: this open is the largest measured segment of a notification
                    // tap, and the route trace alone cannot separate it from the first page (#586).
                    appState.marmotIo(MarmotTraceSection.CONVERSATION_WINDOW_OPEN) {
                        openTimelineWithFallback(account, groupIdHex, limit)
                    }
                },
                openGroupState = { account, groupIdHex ->
                    appState.marmotIo {
                        FfiConversationGroupStateSubscriptionHandle(
                            subscribeGroupState(account, groupIdHex),
                        )
                    }
                },
            )
    }
}

/** Returns a test override when installed, otherwise the production MDK binding. */
internal fun WhiteNoiseAppState.conversationLiveSubscriptions(): ConversationLiveSubscriptions =
    liveSubscriptionOverrides.conversation ?: ConversationLiveSubscriptions.bind(this)

/**
 * Opens MarmotKit's conversation window, and when the engine refuses it falls back to the plain timeline
 * subscription the app used before 0.10.0. The screen then loses only the window-only extras (prepared
 * title, capabilities, anchors) and keeps its messages, instead of showing an error for every chat. The
 * refusal is logged as a release-safe marker so field reports name the engine error.
 *
 * Opening keeps MDK's own deadline. MarmotKit 0.10.1 returns the stored conversation before live recovery
 * (mdk#1873), so a healthy open no longer waits on hydration and a shorter app-side deadline would only
 * abandon opens that are about to succeed.
 */
internal suspend fun MarmotInterface.openTimelineWithFallback(
    account: String,
    groupIdHex: String,
    limit: UInt,
): ConversationTimelineSubscriptionHandle =
    try {
        val window =
            openConversationWindow(
                accountRef = account,
                groupIdHex = groupIdHex,
                mode = ConversationOpenModeFfi.AUTOMATIC,
                messageIdHex = null,
                initialRows = limit.coerceIn(1u, CONVERSATION_WINDOW_MAX_ROWS),
                timeoutMs = CONVERSATION_WINDOW_DEFAULT_DEADLINE,
            )
        FfiConversationWindowHandle(window, release = window::close)
    } catch (cancel: CancellationException) {
        throw cancel
    } catch (refused: MarmotKitException) {
        Log.e("DMConversation", releaseFailureMarker("CONVERSATION_WINDOW_OPEN", refused) + " fallback=timeline")
        FfiConversationTimelineSubscriptionHandle(subscribeTimelineMessages(account, groupIdHex, limit))
    }
