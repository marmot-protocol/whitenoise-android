package dev.ipf.whitenoise.android.state

import android.content.Context
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.edit
import dev.ipf.marmotkit.DeviceLinkEndReasonFfi
import dev.ipf.marmotkit.DeviceLinkPromptStateFfi
import dev.ipf.marmotkit.DeviceLinkSnapshotFfi
import dev.ipf.whitenoise.android.core.linkedDevicesAvailable
import kotlinx.coroutines.delay

private const val RETAINED_ATTEMPTS = 20
private const val HISTORY_SECONDS = 7 * 24 * 60 * 60
private const val MISSED_LINK_READ_RETRY_MS = 1_000L

internal class DeviceLinkRequestRecords(
    context: Context,
) {
    val unanswered = DeviceLinkRequestHistory(context)
    val attempts = DeviceLinkAttemptHistory(context)
}

/** A native prompt this installation reviewed or saw end without review. */
internal data class DeviceLinkAttempt(
    val id: String,
    val requestedAtSeconds: Long,
    val reviewed: Boolean,
)

/** Passive history from MDK snapshots, scoped to this installation and its signed-in profiles. */
@Stable
internal class DeviceLinkAttemptHistory(
    context: Context,
    private val nowSeconds: () -> Long = { System.currentTimeMillis() / 1_000L },
) {
    private val preferences = context.getSharedPreferences("missed_device_links", Context.MODE_PRIVATE)
    private var attempts by mutableStateOf(
        preferences.all.mapValues { (_, stored) -> (stored as? Set<*>).orEmpty().mapNotNull(::decodeAttempt) },
    )

    fun missed(accountRef: String): List<DeviceLinkAttempt> = recent(attempts[accountRef].orEmpty()).filterNot { it.reviewed }

    fun reviewed(request: DeviceLinkRequest) {
        val entry = DeviceLinkAttempt(request.promptId, request.requestedAtSeconds, reviewed = true)
        val previous = attempts[request.accountRef].orEmpty()
        update(attempts + (request.accountRef to recent(listOf(entry) + previous.filterNot { it.id == entry.id })))
    }

    fun observe(
        snapshots: Map<String, DeviceLinkSnapshotFfi>,
        watchedAccounts: Set<String>,
    ) {
        val next = attempts.filterKeys { it in watchedAccounts }.toMutableMap()
        for ((account, snapshot) in snapshots.filterKeys { it in watchedAccounts }) {
            val previous = next[account].orEmpty()
            val known = previous.mapTo(mutableSetOf(), DeviceLinkAttempt::id)
            val expired =
                snapshot.prompts.mapNotNull { prompt ->
                    val reason = (prompt.state as? DeviceLinkPromptStateFfi.Ended)?.reason
                    if (reason == DeviceLinkEndReasonFfi.TIMED_OUT && known.add(prompt.promptId)) {
                        DeviceLinkAttempt(prompt.promptId, prompt.requestedAt.toLong(), reviewed = false)
                    } else {
                        null
                    }
                }
            val kept = recent(previous + expired)
            if (kept.isNotEmpty()) next[account] = kept else next.remove(account)
        }
        update(next)
    }

    private fun recent(attempts: List<DeviceLinkAttempt>): List<DeviceLinkAttempt> {
        val now = nowSeconds()
        return attempts
            .filter { now - it.requestedAtSeconds in 0..HISTORY_SECONDS }
            .sortedByDescending(DeviceLinkAttempt::requestedAtSeconds)
            .take(RETAINED_ATTEMPTS)
    }

    private fun update(next: Map<String, List<DeviceLinkAttempt>>) {
        if (next == attempts) return
        attempts = next
        preferences.edit {
            clear()
            next.forEach { (account, entries) ->
                putStringSet(
                    account,
                    entries.mapTo(mutableSetOf()) { "${it.requestedAtSeconds}:${it.reviewed}:${it.id}" },
                )
            }
        }
    }
}

/** Records terminal snapshots even while the shell is stopped but the native worker is still running. */
internal suspend fun WhiteNoiseAppState.refreshMissedDeviceLinkAttempts(accountRef: String) {
    if (!linkedDevicesAvailable()) return
    val generation = runtimeGeneration
    val account = accounts.firstOrNull { it.label == accountRef && signInAlertsApply(it) } ?: return
    val snapshot =
        readDeviceLinkAttempts(accountRef) ?: run {
            delay(MISSED_LINK_READ_RETRY_MS)
            if (generation != runtimeGeneration) return
            readDeviceLinkAttempts(accountRef)
        } ?: return
    val watched = accounts.filter(::signInAlertsApply)
    val stillWatched = watched.any { it.label == accountRef && it.accountIdHex == account.accountIdHex }
    if (generation == runtimeGeneration && stillWatched) {
        deviceLinkRequests.attempts?.observe(mapOf(accountRef to snapshot), watched.mapTo(mutableSetOf()) { it.label })
    }
}

/** Recovers terminal prompts still retained by MDK after account loading or reconnecting the event stream. */
internal suspend fun WhiteNoiseAppState.refreshAllMissedDeviceLinkAttempts() {
    accounts.filter(::signInAlertsApply).forEach { refreshMissedDeviceLinkAttempts(it.label) }
}

private suspend fun WhiteNoiseAppState.readDeviceLinkAttempts(accountRef: String): DeviceLinkSnapshotFfi? =
    runCatchingCancellable { marmotIo { deviceLinkSnapshot(accountRef) } }.getOrNull()

private fun decodeAttempt(stored: Any?): DeviceLinkAttempt? {
    val fields = (stored as? String)?.split(':', limit = 3).orEmpty()
    val time = fields.firstOrNull()?.toLongOrNull() ?: return null
    val reviewed = fields.getOrNull(1)?.toBooleanStrictOrNull() ?: return null
    return fields.getOrNull(2)?.takeIf(String::isNotEmpty)?.let { DeviceLinkAttempt(it, time, reviewed) }
}
