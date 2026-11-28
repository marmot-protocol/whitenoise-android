@file:Suppress("FunctionNaming")

package dev.ipf.whitenoise.android.ui.conversation.media

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.input.InputTransformation
import androidx.compose.foundation.text.input.TextFieldLineLimits
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.text.input.maxLengthReject
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onVisibilityChanged
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.os.ConfigurationCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import dev.ipf.whitenoise.android.R
import dev.ipf.whitenoise.android.media.GIPHY_QUERY_MAX_LENGTH
import dev.ipf.whitenoise.android.media.GiphySearchClient
import dev.ipf.whitenoise.android.media.GiphySearchResult
import dev.ipf.whitenoise.android.ui.common.WhiteNoiseLazyVerticalGrid
import dev.ipf.whitenoise.android.ui.common.WhiteNoiseModalBottomSheet
import dev.ipf.whitenoise.android.ui.common.WhiteNoiseSheetHeader
import dev.ipf.whitenoise.android.ui.common.WhiteNoiseTextField
import dev.ipf.whitenoise.android.ui.common.rememberSystemAnimationsEnabled
import dev.ipf.whitenoise.android.ui.theme.WhiteNoiseSpacing
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive

internal const val GIPHY_SEARCH_DEBOUNCE_MS = 300L
private const val PREVIEW_VISIBLE_FRACTION = 0.05f
private const val SHEET_HEIGHT_SHARE = 0.82f
private const val RESULT_TILE_ASPECT_RATIO = 1.25f
private val ResultTileMinWidth = 132.dp
private val PreviewIndicatorSize = 24.dp

internal sealed interface GiphySearchState {
    data object Idle : GiphySearchState

    data object Loading : GiphySearchState

    data object Failed : GiphySearchState

    data class Results(
        val items: List<GiphySearchResult>,
    ) : GiphySearchState
}

/** Presents debounced GIPHY search and sends only the rendition validated by the picker. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun GiphySearchSheet(
    apiKey: String,
    onDismiss: () -> Unit,
    onSend: (String) -> Unit,
) {
    val client = remember(apiKey) { GiphySearchClient(apiKey) }
    val loader = remember { GiphyPreviewLoader() }
    val configuration = LocalConfiguration.current
    val language = ConfigurationCompat.getLocales(configuration)[0]?.language.orEmpty()
    val searchField = remember { TextFieldState() }
    val query = searchField.text.toString().trim()
    var retryAttempt by remember(query) { mutableIntStateOf(0) }
    val search: suspend (String) -> List<GiphySearchResult>? =
        remember(client, language) { { text -> client.search(text, language) } }
    val state = rememberGiphySearchState(query, retryAttempt, search)

    WhiteNoiseModalBottomSheet(onDismissRequest = onDismiss) {
        WhiteNoiseSheetHeader(stringResource(R.string.giphy_search_title), onClose = onDismiss)
        GiphySearchContent(
            searchField = searchField,
            state = state,
            onRetry = { retryAttempt++ },
            onSend = { result ->
                if (searchField.text.toString().trim() == query) onSend(result.media.wireText())
            },
            preview = { result, visible -> rememberGiphySearchPreview(result, loader, visible) },
            modifier = Modifier.heightIn(max = configuration.screenHeightDp.dp * SHEET_HEIGHT_SHARE),
        )
    }
}

/** Cancels requests and removes stale results as soon as the search text changes. */
@Composable
internal fun rememberGiphySearchState(
    query: String,
    retryAttempt: Int,
    search: suspend (String) -> List<GiphySearchResult>?,
): GiphySearchState {
    val state =
        remember(query, retryAttempt, search) {
            mutableStateOf<GiphySearchState>(if (query.isEmpty()) GiphySearchState.Idle else GiphySearchState.Loading)
        }
    LaunchedEffect(query, retryAttempt, search) {
        if (query.isNotEmpty()) {
            delay(GIPHY_SEARCH_DEBOUNCE_MS)
            val results = search(query)
            currentCoroutineContext().ensureActive()
            state.value = results?.let { GiphySearchState.Results(it) } ?: GiphySearchState.Failed
        }
    }
    return state.value
}

internal sealed interface GiphyPreviewState {
    data object Loading : GiphyPreviewState

    data object Unavailable : GiphyPreviewState

    data class Playable(
        val preview: GiphyPreview,
    ) : GiphyPreviewState
}

/** Renders the disclosure, search field and results in one scrollable area for compact and large-text windows. */
@Suppress("LongParameterList") // State, actions and reusable preview slot are independent.
@Composable
internal fun GiphySearchContent(
    searchField: TextFieldState,
    state: GiphySearchState,
    onRetry: () -> Unit,
    onSend: (GiphySearchResult) -> Unit,
    preview: @Composable (GiphySearchResult, Boolean) -> GiphyPreviewState,
    modifier: Modifier = Modifier,
) {
    val motionEnabled = rememberSystemAnimationsEnabled()
    WhiteNoiseLazyVerticalGrid(
        columns = GridCells.Adaptive(ResultTileMinWidth),
        modifier = modifier.fillMaxWidth(),
        contentPadding =
            PaddingValues(
                horizontal = WhiteNoiseSpacing.CompactScreenMargin,
                vertical = WhiteNoiseSpacing.Related,
            ),
        verticalArrangement = Arrangement.spacedBy(WhiteNoiseSpacing.Related),
        horizontalArrangement = Arrangement.spacedBy(WhiteNoiseSpacing.Related),
    ) {
        item(span = { GridItemSpan(maxLineSpan) }) {
            GiphySearchControls(searchField)
        }
        val results = (state as? GiphySearchState.Results)?.items.orEmpty()
        if (results.isEmpty()) {
            item(span = { GridItemSpan(maxLineSpan) }) {
                GiphySearchStatus(state, onRetry)
            }
        }
        items(results, key = { it.media.url }) { result ->
            var visible by remember { mutableStateOf(false) }
            GiphySearchTile(
                result = result,
                preview = preview(result, visible),
                motionEnabled = motionEnabled,
                modifier = Modifier.onVisibilityChanged(minFractionVisible = PREVIEW_VISIBLE_FRACTION) { visible = it },
                onSend = { onSend(result) },
            )
        }
    }
}

/** Places the provider disclosure above the field before typing can trigger a request. */
@Composable
private fun GiphySearchControls(searchField: TextFieldState) {
    Column(verticalArrangement = Arrangement.spacedBy(WhiteNoiseSpacing.Related)) {
        Text(
            stringResource(R.string.giphy_search_privacy),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        val credit = stringResource(R.string.giphy_search_powered_by)
        Row(
            modifier = Modifier.clearAndSetSemantics { contentDescription = credit },
            horizontalArrangement = Arrangement.spacedBy(WhiteNoiseSpacing.Related),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                stringResource(R.string.giphy_search_powered_by_prefix),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Image(
                painter = painterResource(R.drawable.giphy_logo),
                contentDescription = null,
                modifier =
                    Modifier
                        .clip(MaterialTheme.shapes.extraSmall)
                        .background(Color.Black)
                        .padding(horizontal = 8.dp, vertical = 4.dp)
                        .width(80.dp),
            )
        }
        val label = stringResource(R.string.giphy_search_hint)
        WhiteNoiseTextField(
            state = searchField,
            modifier = Modifier.fillMaxWidth(),
            label = { Text(label) },
            leadingIcon = { Icon(painterResource(R.drawable.ic_search), contentDescription = null) },
            inputTransformation = InputTransformation.maxLengthReject(GIPHY_QUERY_MAX_LENGTH),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
            lineLimits = TextFieldLineLimits.SingleLine,
        )
    }
}

/** Keeps loading, empty results and request failure visible and makes failed requests retryable. */
@Composable
private fun GiphySearchStatus(
    state: GiphySearchState,
    onRetry: () -> Unit,
) {
    Box(
        Modifier.fillMaxWidth().padding(vertical = WhiteNoiseSpacing.Related),
        contentAlignment = Alignment.Center,
    ) {
        when (state) {
            GiphySearchState.Idle -> Unit
            GiphySearchState.Loading -> CircularProgressIndicator()
            GiphySearchState.Failed ->
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(stringResource(R.string.giphy_search_failed))
                    TextButton(onClick = onRetry) { Text(stringResource(R.string.retry)) }
                }
            is GiphySearchState.Results -> Text(stringResource(R.string.giphy_search_empty))
        }
    }
}

/** A result becomes actionable only after a playable loop is available for the exact sent GIF. */
@Composable
private fun GiphySearchTile(
    result: GiphySearchResult,
    preview: GiphyPreviewState,
    motionEnabled: Boolean,
    modifier: Modifier,
    onSend: () -> Unit,
) {
    val title = result.title ?: stringResource(R.string.gif)
    val selectionEnabled = preview is GiphyPreviewState.Playable
    Box(
        modifier
            .aspectRatio(RESULT_TILE_ASPECT_RATIO)
            .clip(MaterialTheme.shapes.medium)
            .background(MaterialTheme.colorScheme.surfaceContainerHighest)
            .clickable(
                enabled = selectionEnabled,
                onClickLabel = stringResource(R.string.giphy_send_result, title),
                role = Role.Button,
                onClick = { if (selectionEnabled) onSend() },
            ),
        contentAlignment = Alignment.Center,
    ) {
        when (preview) {
            is GiphyPreviewState.Playable -> GiphyPreviewImage(preview.preview, title, motionEnabled)
            else -> GiphyPreviewPlaceholder(title, loading = preview == GiphyPreviewState.Loading)
        }
    }
}

/** Keeps loading and unavailable previews readable while their send action is disabled. */
@Composable
private fun GiphyPreviewPlaceholder(
    title: String,
    loading: Boolean,
) {
    Column(
        modifier = Modifier.padding(WhiteNoiseSpacing.Related),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(WhiteNoiseSpacing.Related),
    ) {
        if (loading) {
            CircularProgressIndicator(Modifier.size(PreviewIndicatorSize))
        }
        Text(title, maxLines = 2, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center)
        val status = if (loading) R.string.giphy_preview_loading else R.string.giphy_preview_unavailable
        Text(stringResource(status), style = MaterialTheme.typography.labelSmall, textAlign = TextAlign.Center)
    }
}

/** Loops already-decoded pixels and honors the system's motion preference. */
@Composable
private fun GiphyPreviewImage(
    preview: GiphyPreview,
    title: String,
    motionEnabled: Boolean,
) {
    var index by remember(preview, motionEnabled) { mutableIntStateOf(0) }
    LaunchedEffect(preview, motionEnabled) {
        if (motionEnabled) {
            while (true) {
                delay(preview.frames[index].durationMs)
                index = (index + 1) % preview.frames.size
            }
        }
    }
    Image(preview.frames[index].image, title, Modifier.fillMaxSize(), contentScale = ContentScale.Fit)
}

/** Retires the capture and its pixels as soon as the tile loses visibility, focus, or its result identity. */
@Composable
private fun rememberGiphySearchPreview(
    result: GiphySearchResult,
    loader: GiphyPreviewLoader,
    visible: Boolean,
): GiphyPreviewState {
    val lifecycle by LocalLifecycleOwner.current.lifecycle.currentStateFlow
        .collectAsState()
    val foreground = lifecycle.isAtLeast(Lifecycle.State.RESUMED)
    val active = visible && foreground && LocalWindowInfo.current.isWindowFocused
    val state = remember(result.media, active) { mutableStateOf<GiphyPreviewState>(GiphyPreviewState.Loading) }
    LaunchedEffect(state) {
        if (active) {
            val preview = loader.load(result.media)
            try {
                currentCoroutineContext().ensureActive()
                state.value = preview?.let { GiphyPreviewState.Playable(it) } ?: GiphyPreviewState.Unavailable
                if (preview != null) awaitCancellation()
            } finally {
                preview?.release()
            }
        }
    }
    return state.value
}
