package dev.ipf.whitenoise.android.state

import dev.ipf.marmotkit.MarmotInterface
import dev.ipf.marmotkit.PresentedChatRowFfi
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async

/**
 * One local chat-list read for the remembered account, started beside `Marmot.start()` so a force-stop launch
 * does not read rows and decode first-frame avatars only after native startup returns. The rows only seed the
 * first frame — the chat list's live subscription replaces them right after it draws, as it does for a read made
 * after start. Nothing is stored, and the result is used at most once, by the startup activation of the same
 * account on the same runtime.
 */
internal class StartupChatListPrefetch(
    val accountRef: String,
    private val runtime: MarmotInterface,
    private val rows: Deferred<List<PresentedChatRowFfi>?>,
) {
    /** Whether this read belongs to [accountRef] on the [current] runtime. */
    fun matches(
        accountRef: String,
        current: MarmotInterface?,
    ): Boolean = this.accountRef == accountRef && runtime === current

    /** The prefetched rows, or null when the read failed and the caller should read again. */
    suspend fun await(): List<PresentedChatRowFfi>? = rows.await()

    /** Abandons a read nobody will consume, such as one for a runtime that failed to start. */
    fun cancel() = rows.cancel()
}

/** Holds at most one [StartupChatListPrefetch]; replacing or clearing it cancels the previous read. */
internal class StartupChatListPrefetchSlot {
    private var prefetch: StartupChatListPrefetch? = null

    /** Stores [next], cancelling any read it replaces. */
    @Synchronized
    fun replace(next: StartupChatListPrefetch?) {
        prefetch?.cancel()
        prefetch = next
    }

    /** Removes and returns the stored read. */
    @Synchronized
    fun take(): StartupChatListPrefetch? = prefetch.also { prefetch = null }
}

/** Starts the remembered account's read for [runtime] before it starts, replacing any earlier one. */
internal fun WhiteNoiseAppState.beginStartupChatListPrefetch(runtime: MarmotInterface) =
    accountSwitchHandoff.startupPrefetch.replace(launchStartupChatListPrefetch(runtime))

/** Discards rows from a failed startup or from before a local migration write. */
internal fun WhiteNoiseAppState.abandonStartupChatListPrefetch() = accountSwitchHandoff.startupPrefetch.replace(null)

/**
 * Starts [StartupChatListPrefetch] for the remembered active account before [runtime] starts. MDK serves this read
 * from account storage, not from an account worker, so it does not wait for `start()`. Any failure leaves the
 * ordinary post-start read in place.
 */
private fun WhiteNoiseAppState.launchStartupChatListPrefetch(runtime: MarmotInterface): StartupChatListPrefetch? {
    val accountRef = activeAccountRef ?: return null
    val rows =
        profileScope.async(Dispatchers.Default) {
            try {
                val rows =
                    marmotIo(MarmotTraceSection.CHAT_LIST_READ) {
                        check(this === runtime) { "Startup runtime changed before chat-list prefetch" }
                        presentedChatList(accountRef, includeArchived = true)
                    }.rows
                // Spend the avatar budget here. Activation collects the ready pixels once it adopts these rows.
                prewarmFirstFrameDurableAvatars(accountRef, rows)
                rows
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (
                @Suppress("TooGenericExceptionCaught") failure: Throwable,
            ) {
                appStateDebug(failure) { "startup chat-list prefetch failed: ${failure.readableMessage()}" }
                null
            }
        }
    return StartupChatListPrefetch(accountRef, runtime, rows)
}

/** Consumes the matching prefetch once and cancels it if activation stops waiting. */
internal suspend fun WhiteNoiseAppState.awaitStartupChatListPrefetch(accountRef: String): List<PresentedChatRowFfi>? {
    val prefetch = accountSwitchHandoff.startupPrefetch.take() ?: return null
    return try {
        if (prefetch.matches(accountRef, marmotOrNull())) prefetch.await() else null
    } finally {
        prefetch.cancel()
    }
}
