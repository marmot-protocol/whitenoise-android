package dev.ipf.whitenoise.android.ui.chats.newchat

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import dev.ipf.marmotkit.SearchUpdateTriggerFfi
import dev.ipf.marmotkit.UserDirectorySearchResultFfi
import dev.ipf.whitenoise.android.core.RecipientSearch
import dev.ipf.whitenoise.android.state.WhiteNoiseAppState
import dev.ipf.whitenoise.android.state.rethrowIfCancellation
import dev.ipf.whitenoise.android.ui.theme.Dimens
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.util.Locale

internal data class RecipientUserSearchState(
    val candidates: List<RecipientSearch.Candidate> = emptyList(),
    val isSearching: Boolean = false,
    val isIncomplete: Boolean = false,
    val failed: Boolean = false,
)

internal data class RecipientSearchProgress(
    val isIncomplete: Boolean = false,
    val failed: Boolean = false,
    val completed: Boolean = false,
)

internal fun RecipientSearchProgress.withTrigger(trigger: SearchUpdateTriggerFfi): RecipientSearchProgress =
    when (trigger) {
        is SearchUpdateTriggerFfi.RadiusTimeout,
        is SearchUpdateTriggerFfi.RadiusTruncated,
        -> copy(isIncomplete = true)
        is SearchUpdateTriggerFfi.Error -> copy(failed = true)
        SearchUpdateTriggerFfi.SearchCompleted -> copy(completed = true)
        else -> this
    }

internal suspend fun loadRecipientSearchFollowIds(load: suspend () -> List<String>): Set<String> =
    try {
        load().mapTo(HashSet()) { it.trim().lowercase(Locale.ROOT) }
    } catch (error: Throwable) {
        rethrowIfCancellation(error)
        emptySet()
    }

internal suspend fun <T : AutoCloseable, R> withClosedRecipientSearchSubscription(
    open: suspend () -> T,
    consume: suspend (T) -> R,
): R {
    val subscription = open()
    return try {
        consume(subscription)
    } finally {
        withContext(NonCancellable + Dispatchers.IO) { subscription.close() }
    }
}

@Composable
internal fun UserSearchStatusRow(
    @StringRes messageRes: Int,
    showProgress: Boolean = false,
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = Dimens.spaceLg, vertical = Dimens.spaceMd),
        horizontalArrangement = Arrangement.spacedBy(Dimens.spaceMd),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (showProgress) {
            CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
        }
        Text(
            stringResource(messageRes),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/**
 * Runs one lifecycle-bound Marmot user search for the current plain-text query.
 * Replacing the query or leaving the screen cancels the traversal and closes
 * its native subscription; no discovered people are persisted by Android.
 */
@Composable
internal fun rememberRecipientUserSearchState(
    query: String,
    appState: WhiteNoiseAppState,
): State<RecipientUserSearchState> {
    val trimmed = query.trim()
    val activeAccountRef = appState.activeAccountRef
    val activeAccountIdHex = appState.activeAccount?.accountIdHex
    return produceState(
        initialValue = RecipientUserSearchState(),
        key1 = trimmed,
        key2 = Triple(activeAccountRef, activeAccountIdHex, appState.relationshipRevision),
    ) {
        if (trimmed.isEmpty() || !isPlainNameQuery(trimmed) || activeAccountRef == null || activeAccountIdHex == null) {
            value = RecipientUserSearchState()
            return@produceState
        }

        delay(USER_SEARCH_DEBOUNCE_MILLIS)
        value = RecipientUserSearchState(isSearching = true)
        try {
            val followedIds =
                loadRecipientSearchFollowIds {
                    appState.marmotIo { accountFollows(activeAccountRef) }
                }
            withClosedRecipientSearchSubscription(
                open = {
                    appState.marmotIo {
                        searchUsers(
                            accountIdHex = activeAccountIdHex,
                            query = trimmed,
                            radiusStart = USER_SEARCH_RADIUS_START,
                            radiusEnd = USER_SEARCH_RADIUS_END,
                        )
                    }
                },
                consume = { activeSubscription ->
                    val aggregate = ArrayList<UserDirectorySearchResultFfi>()
                    var progress = RecipientSearchProgress()
                    while (true) {
                        val update = appState.marmotIo { activeSubscription.nextUpdate() } ?: break
                        aggregate += update.newResults
                        progress = progress.withTrigger(update.trigger)
                        value =
                            RecipientUserSearchState(
                                candidates = RecipientSearch.discoveredCandidates(aggregate, followedIds),
                                isSearching = !progress.completed,
                                isIncomplete = progress.isIncomplete,
                                failed = progress.failed,
                            )
                        if (progress.completed) break
                    }
                },
            )
            if (value.isSearching) value = value.copy(isSearching = false)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            value = value.copy(isSearching = false, failed = true)
        }
    }
}

private const val USER_SEARCH_DEBOUNCE_MILLIS = 300L
private val USER_SEARCH_RADIUS_START: UByte = 1u
private val USER_SEARCH_RADIUS_END: UByte = 2u
