package dev.ipf.whitenoise.android.ui.chats.newchat

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.LoadingIndicator
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
import dev.ipf.marmotkit.UserSearchUpdateFfi
import dev.ipf.whitenoise.android.core.RecipientSearch
import dev.ipf.whitenoise.android.state.WhiteNoiseAppState
import dev.ipf.whitenoise.android.state.rethrowIfCancellation
import dev.ipf.whitenoise.android.ui.theme.Dimens
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.util.Locale

internal data class RecipientUserSearchState(
    val candidates: List<RecipientSearch.Candidate> = emptyList(),
    val isSearching: Boolean = false,
    val isIncomplete: Boolean = false,
    val failed: Boolean = false,
    /** The account's whole follow list, so local contacts can be flagged too. */
    val followedAccountIds: Set<String> = emptySet(),
)

/** Identifies the query and account relationship snapshot owned by one search producer. */
internal data class RecipientSearchRequestKey(
    val query: String,
    val activeAccountRef: String?,
    val activeAccountIdHex: String?,
    val relationshipRevision: Long,
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
    runCatching { load() }
        .fold(
            onSuccess = { ids -> ids.mapTo(HashSet()) { it.trim().lowercase(Locale.ROOT) } },
            onFailure = { error ->
                rethrowIfCancellation(error)
                emptySet()
            },
        )

/** Rejects a value returned after the owning query or account coroutine was cancelled. */
internal suspend fun <T> awaitCurrentRecipientSearchValue(load: suspend () -> T): T {
    val value = load()
    currentCoroutineContext().ensureActive()
    return value
}

/** Fold the streamed batches into one aggregate view, emitting after each step. */
internal suspend fun aggregateRecipientSearchUpdates(
    nextUpdate: suspend () -> UserSearchUpdateFfi?,
    followedAccountIds: Set<String>,
    emit: (RecipientUserSearchState) -> Unit,
) {
    val aggregate = ArrayList<UserDirectorySearchResultFfi>()
    var progress = RecipientSearchProgress()
    while (!progress.completed) {
        val update = awaitCurrentRecipientSearchValue(nextUpdate) ?: break
        aggregate += update.newResults
        progress = progress.withTrigger(update.trigger)
        emit(
            RecipientUserSearchState(
                candidates = RecipientSearch.discoveredCandidates(aggregate, followedAccountIds),
                isSearching = !progress.completed,
                isIncomplete = progress.isIncomplete,
                failed = progress.failed,
                followedAccountIds = followedAccountIds,
            ),
        )
    }
}

/** Runs one native search subscription and closes it even when its owner is replaced or cancelled. */
internal suspend fun <T : AutoCloseable, R> withClosedRecipientSearchSubscription(
    open: suspend () -> T,
    consume: suspend (T) -> R,
): R {
    val subscription = open()
    val consumption =
        runCatching {
            currentCoroutineContext().ensureActive()
            consume(subscription)
        }
    val closeFailure =
        withContext(NonCancellable + Dispatchers.IO) {
            runCatching { subscription.close() }.exceptionOrNull()
        }
    val primaryFailure = consumption.exceptionOrNull()
    if (primaryFailure != null) {
        closeFailure?.let(primaryFailure::addSuppressed)
        throw primaryFailure
    }
    closeFailure?.let { throw it }
    return consumption.getOrThrow()
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
@Suppress("FunctionNaming")
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
            LoadingIndicator(modifier = Modifier.size(18.dp))
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
        key1 =
            RecipientSearchRequestKey(
                query = trimmed,
                activeAccountRef = activeAccountRef,
                activeAccountIdHex = activeAccountIdHex,
                relationshipRevision = appState.relationshipRevision,
            ),
    ) {
        if (trimmed.isEmpty() || !isPlainNameQuery(trimmed)) {
            value = RecipientUserSearchState()
            return@produceState
        }
        if (activeAccountRef == null || activeAccountIdHex == null) {
            value = RecipientUserSearchState()
            return@produceState
        }

        // Pending before the debounce elapses, not after — otherwise a query with
        // no local matches reads as a completed empty search for 300 ms.
        value = RecipientUserSearchState(isSearching = true)
        delay(USER_SEARCH_DEBOUNCE_MILLIS)
        try {
            val followedIds =
                awaitCurrentRecipientSearchValue {
                    loadRecipientSearchFollowIds {
                        appState.marmotIo { accountFollows(activeAccountRef) }
                    }
                }
            value = value.copy(followedAccountIds = followedIds)
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
                    aggregateRecipientSearchUpdates(
                        nextUpdate = { appState.marmotIo { activeSubscription.nextUpdate() } },
                        followedAccountIds = followedIds,
                        emit = { value = it },
                    )
                },
            )
            currentCoroutineContext().ensureActive()
            if (value.isSearching) value = value.copy(isSearching = false)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            value = value.copy(isSearching = false, failed = true)
        }
    }
}

private const val USER_SEARCH_DEBOUNCE_MILLIS = 300L
private const val USER_SEARCH_RADIUS_START: UByte = 1u
private const val USER_SEARCH_RADIUS_END: UByte = 2u
