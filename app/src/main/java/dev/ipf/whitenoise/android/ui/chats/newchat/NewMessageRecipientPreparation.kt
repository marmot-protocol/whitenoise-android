package dev.ipf.whitenoise.android.ui.chats.newchat

import dev.ipf.whitenoise.android.state.ChatCreateOpenTiming
import dev.ipf.whitenoise.android.state.MarmotTraceSection
import dev.ipf.whitenoise.android.state.WhiteNoiseAppState
import dev.ipf.whitenoise.android.state.runCatchingCancellable
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.joinAll

/** Identifies one short-lived recipient preparation within its screen owner. */
internal data class NewMessageRecipientPreparationKey(
    val accountRef: String,
    val runtimeGeneration: Int,
    val query: String,
    val targetReference: String,
    val retryKey: Int,
    val chatRevision: Long = 0L,
)

/**
 * In-flight native work for one resolved New Message recipient. Results live only as long as the
 * screen and are never an Android-owned protocol cache.
 */
internal class NewMessageRecipientPreparation internal constructor(
    val key: NewMessageRecipientPreparationKey,
    private val prewarm: Deferred<Result<Unit>>,
    private val lookup: Deferred<Result<NewMessageDirectChatResolution>>,
) {
    /** An uncertain lookup fails closed so tapping cannot create a duplicate DM. */
    suspend fun directChatResolution(): NewMessageDirectChatResolution =
        lookup.await().getOrElse { NewMessageDirectChatResolution(item = null, createRequired = false) }

    suspend fun awaitCompletion() {
        joinAll(prewarm, lookup)
    }

    fun cancel() {
        prewarm.cancel()
        lookup.cancel()
    }
}

/** Only a definitive preparation can replace an authoritative tap-time lookup. */
internal suspend fun preparedLookupOrFresh(
    preparation: NewMessageRecipientPreparation?,
    fresh: suspend () -> NewMessageDirectChatResolution,
): NewMessageDirectChatResolution =
    preparation?.directChatResolution()?.takeIf { it.item != null || it.createRequired } ?: fresh()

/** Keeps exactly one query/account-scoped preparation and cancels replaced work. */
internal class NewMessageRecipientPreparationCoordinator {
    private var current: NewMessageRecipientPreparation? = null

    fun prepare(
        scope: CoroutineScope,
        key: NewMessageRecipientPreparationKey,
        prewarm: suspend () -> Unit,
        lookup: suspend () -> NewMessageDirectChatResolution,
        markStage: (String) -> Unit = {},
    ): NewMessageRecipientPreparation {
        current?.takeIf { it.key == key }?.let { return it }
        current?.cancel()
        val prewarmResult =
            scope.async {
                markStage(ChatCreateOpenTiming.STAGE_KEY_PACKAGE_PREWARM_START)
                runCatchingCancellable { prewarm() }.also {
                    markStage(
                        if (it.isSuccess) {
                            ChatCreateOpenTiming.STAGE_KEY_PACKAGE_PREWARM_RETURN
                        } else {
                            ChatCreateOpenTiming.STAGE_KEY_PACKAGE_PREWARM_FAILED
                        },
                    )
                }
            }
        val lookupResult =
            scope.async {
                markStage(ChatCreateOpenTiming.STAGE_EXISTING_DM_LOOKUP_START)
                runCatchingCancellable { lookup() }.also {
                    markStage(
                        if (it.isSuccess) {
                            ChatCreateOpenTiming.STAGE_EXISTING_DM_LOOKUP_RETURN
                        } else {
                            ChatCreateOpenTiming.STAGE_EXISTING_DM_LOOKUP_FAILED
                        },
                    )
                }
            }
        return NewMessageRecipientPreparation(key, prewarmResult, lookupResult).also { current = it }
    }

    fun current(key: NewMessageRecipientPreparationKey): NewMessageRecipientPreparation? =
        current?.takeIf { it.key == key }

    fun clear() {
        current?.cancel()
        current = null
    }
}

/** Prewarms without reserving a package; MarmotKit revalidates it during the eventual create. */
internal suspend fun WhiteNoiseAppState.prewarmNewMessageRecipient(
    accountRef: String,
    targetReference: String,
) {
    marmotIo(MarmotTraceSection.PREWARM_KEY_PACKAGES) {
        prewarmGroupMemberKeyPackages(accountRef, listOf(targetReference))
    }
}
