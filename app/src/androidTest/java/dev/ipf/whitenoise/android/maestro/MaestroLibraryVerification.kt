package dev.ipf.whitenoise.android.maestro

import dev.ipf.marmotkit.Marmot
import dev.ipf.marmotkit.TimelineMessageQueryFfi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout

/** An empty browser must agree with both complete disposable native timelines, not just an empty grid. */
internal suspend fun verifyMaestroEmptyLibrary(
    native: Marmot,
    baseline: MaestroMessageBaseline?,
    postcondition: String?,
): Boolean {
    if (postcondition != "global-library-empty") return false
    val original = checkNotNull(baseline)
    return withTimeout(15_000L) {
        withContext(Dispatchers.IO) {
            for (account in listOf(original.account, original.peer)) {
                val query = TimelineMessageQueryFfi(original.group, null, null, null, null, null, 100u)
                val page = native.timelineMessages(account, query)
                check(!page.hasMoreBefore) { "Disposable timeline exceeded the complete empty-library proof" }
                check(page.messages.isNotEmpty())
                check(page.messages.all { it.media.isEmpty() }) { "Actual fixture contains media" }
            }
            true
        }
    }
}
