package dev.ipf.whitenoise.android.state

import dev.ipf.marmotkit.AppGroupRecordFfi
import dev.ipf.marmotkit.GroupStateSubscription
import dev.ipf.marmotkit.TimelineMessagesSubscription
import dev.ipf.marmotkit.TimelinePageFfi

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

    /** Loads the preceding bounded window while retaining the live subscription. */
    suspend fun paginateBackwards(count: UInt): TimelinePageFfi

    /** Loads the following bounded window while retaining the live subscription. */
    suspend fun paginateForwards(count: UInt): TimelinePageFfi

    /** Releases the underlying MDK subscription. */
    fun close()
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
    override suspend fun paginateBackwards(count: UInt): TimelinePageFfi = subscription.paginateBackwards(count)

    /** Delegates forward pagination to the active MDK subscription. */
    override suspend fun paginateForwards(count: UInt): TimelinePageFfi = subscription.paginateForwards(count)

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
                    appState.marmotIo {
                        FfiConversationTimelineSubscriptionHandle(
                            subscribeTimelineMessages(account, groupIdHex, limit),
                        )
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
