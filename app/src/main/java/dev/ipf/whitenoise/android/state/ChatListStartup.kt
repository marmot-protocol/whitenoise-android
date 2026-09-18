package dev.ipf.whitenoise.android.state

import android.util.Log
import dev.ipf.whitenoise.android.BuildConfig
import dev.ipf.whitenoise.android.core.DiagnosticFormatter
import kotlinx.coroutines.delay

/** How long the chat list may sit on a bare spinner before it says the update is still finishing. */
internal const val CHAT_LIST_SLOW_START_MILLIS = 4_000L

/**
 * Whether a member snapshot failure can never succeed on retry. MarmotKit answers `UnknownGroup` for a
 * chat row whose group its member API no longer knows; retrying only repeats the refusal on the backoff
 * schedule, and the row must not keep tripping the batched initial projection either.
 */
internal fun isTerminalMemberFetchFailure(throwable: Throwable): Boolean {
    val variant = DiagnosticFormatter.marmotVariant(throwable)
    return variant == "UnknownGroup"
}

/**
 * Marks bind [epoch] as slow when the list is still loading after [delayMillis]. The chat list then swaps
 * its bare spinner for copy saying the update is still finishing, and one release-safe marker records that
 * the first window took longer than the threshold, because the release log otherwise says nothing here.
 */
internal suspend fun ChatsController.watchSlowChatListStartup(
    epoch: Long,
    delayMillis: Long = CHAT_LIST_SLOW_START_MILLIS,
) {
    delay(delayMillis)
    if (!isLoading || !isActiveBindEpoch(epoch)) return
    slowStartupEpoch = epoch
    Log.i("DMChats", "chat_list_first_window_slow ms=$delayMillis")
}

/** Debug-only so operational INFO logs don't ship in release logcat. See #39. */
internal inline fun chatsDebug(message: () -> String) {
    if (BuildConfig.DEBUG) Log.i("DMChats", message())
}

/** Logs a chat-list failure with its exception in debug builds and as a release-safe marker otherwise. */
internal inline fun chatsDebug(
    error: Throwable,
    message: () -> String,
) {
    if (BuildConfig.DEBUG) {
        Log.e("DMChats", message(), error)
    } else {
        Log.e("DMChats", releaseFailureMarker("CHATS", error, message()))
    }
}
