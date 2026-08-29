package dev.ipf.whitenoise.android.ui.conversation

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import kotlinx.coroutines.delay

private const val MILLIS_PER_SECOND = 1_000L
private val MAX_EXPIRY_SECONDS = Long.MAX_VALUE.toULong() / MILLIS_PER_SECOND.toULong()

@Composable
internal fun rememberForwardEligibilityNowSeconds(retentionExpiries: List<ULong>): ULong {
    var nowMillis by remember(retentionExpiries) { mutableLongStateOf(System.currentTimeMillis()) }
    val refreshDelayMillis =
        remember(retentionExpiries, nowMillis) {
            forwardEligibilityRefreshDelayMillis(retentionExpiries, nowMillis)
        }
    LaunchedEffect(retentionExpiries, refreshDelayMillis) {
        refreshDelayMillis?.let { delay(it) }
        if (refreshDelayMillis != null) nowMillis = System.currentTimeMillis()
    }
    return (nowMillis.coerceAtLeast(0L) / MILLIS_PER_SECOND).toULong()
}

internal fun forwardEligibilityRefreshDelayMillis(
    retentionExpiries: Iterable<ULong>,
    nowMillis: Long,
): Long? {
    val safeNowMillis = nowMillis.coerceAtLeast(0L)
    val nowSeconds = (safeNowMillis / MILLIS_PER_SECOND).toULong()
    val nextExpiry = retentionExpiries.filter { it > nowSeconds }.minOrNull()
    return nextExpiry
        ?.takeIf { it <= MAX_EXPIRY_SECONDS }
        ?.let { expiry ->
            (expiry.toLong() * MILLIS_PER_SECOND - safeNowMillis).coerceAtLeast(1L)
        }
}
