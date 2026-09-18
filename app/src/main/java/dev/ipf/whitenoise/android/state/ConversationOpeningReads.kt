package dev.ipf.whitenoise.android.state

import dev.ipf.marmotkit.GroupRosterFfi
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async

/**
 * Starts a conversation's opening roster read on its own scope so it runs alongside the timeline and
 * group-state opens instead of behind them (#586). The answer is carried as a [Result] rather than
 * thrown, so a failing read waits to be interpreted where the roster is applied and cannot cancel the
 * conversation from a coroutine nobody is awaiting yet.
 */
internal fun CoroutineScope.prefetchGroupRoster(
    groupIdHex: String,
    account: String,
    read: suspend (String, String) -> GroupRosterFfi,
): Deferred<Result<GroupRosterFfi>> = async { runCatchingCancellable { read(account, groupIdHex) } }
