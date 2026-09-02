package dev.ipf.whitenoise.android.state

import dev.ipf.marmotkit.AppGroupRecordFfi
import dev.ipf.marmotkit.GroupStateSubscription
import dev.ipf.marmotkit.TimelineMessagesSubscription
import dev.ipf.marmotkit.TimelinePageFfi
import dev.ipf.marmotkit.TimelineSubscriptionUpdateFfi

/**
 * Lifecycle-shaped seam for conversation live subscriptions. Production binds
 * UniFFI handles; tests supply scripted implementations without subclassing FFI
 * types.
 */
internal interface ConversationTimelineSubscriptionHandle {
    fun snapshot(): TimelinePageFfi?

    suspend fun nextUpdate(): TimelineSubscriptionUpdateFfi?

    suspend fun paginateBackwards(count: UInt): TimelinePageFfi

    suspend fun paginateForwards(count: UInt): TimelinePageFfi

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
    override fun snapshot(): TimelinePageFfi? = subscription.snapshot()

    override suspend fun nextUpdate(): TimelineSubscriptionUpdateFfi? = subscription.nextUpdate()

    override suspend fun paginateBackwards(count: UInt): TimelinePageFfi = subscription.paginateBackwards(count)

    override suspend fun paginateForwards(count: UInt): TimelinePageFfi = subscription.paginateForwards(count)

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

internal fun WhiteNoiseAppState.conversationLiveSubscriptions(): ConversationLiveSubscriptions =
    liveSubscriptionOverrides.conversation ?: ConversationLiveSubscriptions.bind(this)
