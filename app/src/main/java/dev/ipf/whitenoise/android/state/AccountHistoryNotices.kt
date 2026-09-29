package dev.ipf.whitenoise.android.state

import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import dev.ipf.marmotkit.HistoryNoticeFfi
import dev.ipf.marmotkit.MarmotEventFfi
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Presents one account's account-wide "history may be incomplete" notices.
 *
 * The runtime owns the notices durably; this holder only mirrors the latest read
 * for the chat list. Group-scoped occurrences are left to that conversation's
 * recovery status, so each notice appears on exactly one surface. Notice ids
 * change whenever recovery re-arms, so they are never kept past the next read.
 */
@Stable
internal class AccountHistoryNotices(
    private val accountRef: String,
    private val readNotices: suspend (accountRef: String) -> List<HistoryNoticeFfi>,
    private val dismissNotice: suspend (accountRef: String, noticeId: String) -> Boolean,
    private val reportDismissFailure: (Throwable) -> Unit = {},
) {
    var notices by mutableStateOf<List<HistoryNoticeFfi>>(emptyList())
        private set

    var dismissing by mutableStateOf(false)
        private set

    // Serializes read-and-publish so an older read can never replace a newer one.
    private val reads = Mutex()

    /** Re-reads the account's notices; a failed advisory read keeps what is shown. */
    @Suppress("TooGenericExceptionCaught") // Every non-cancellation native failure leaves the prior list.
    suspend fun refresh() {
        reads.withLock {
            val current =
                try {
                    readNotices(accountRef)
                } catch (cancel: CancellationException) {
                    throw cancel
                } catch (_: Throwable) {
                    return
                }
            notices = current.filter { it.groupIdHex == null }
        }
    }

    /** Reads once, then re-reads whenever the runtime reports this account's notices changed. */
    @Suppress("TooGenericExceptionCaught") // A closed subscription is retried by the lifecycle owner.
    suspend fun observe(nextEvent: suspend () -> MarmotEventFfi?) {
        refresh()
        try {
            while (true) {
                val event = nextEvent() ?: return
                if (event is MarmotEventFfi.HistoryNoticesChanged && event.accountLabel == accountRef) refresh()
            }
        } catch (cancel: CancellationException) {
            throw cancel
        } catch (_: Throwable) {
            return
        }
    }

    /**
     * Records dismissal of the shown ids and refreshes before enabling the action.
     * A false result means the id was already gone; native failures are reported.
     */
    suspend fun dismissAll() {
        if (dismissing) return
        val noticeIds = notices.map { it.noticeId }
        if (noticeIds.isEmpty()) return
        dismissing = true
        try {
            val firstFailure = dismissHistoryNoticeIds(noticeIds) { noticeId -> dismissNotice(accountRef, noticeId) }
            refresh()
            firstFailure?.let(reportDismissFailure)
        } finally {
            dismissing = false
        }
    }
}
