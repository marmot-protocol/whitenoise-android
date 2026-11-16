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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.input.InputTransformation
import androidx.compose.foundation.text.input.TextFieldLineLimits
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.text.input.maxLength
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalFocusManager
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
import dev.ipf.whitenoise.android.R
import dev.ipf.whitenoise.android.media.GIPHY_QUERY_MAX_LENGTH
import dev.ipf.whitenoise.android.media.GiphySearchClient
import dev.ipf.whitenoise.android.media.GiphySearchResult
import dev.ipf.whitenoise.android.ui.common.WhiteNoiseModalBottomSheet
import dev.ipf.whitenoise.android.ui.common.WhiteNoiseSheetHeader
import dev.ipf.whitenoise.android.ui.common.WhiteNoiseTextField
import dev.ipf.whitenoise.android.ui.theme.WhiteNoiseSpacing
import dev.ipf.whitenoise.android.ui.theme.amoledSheetContainerColor
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

private const val PREVIEW_EDGE_PX = 240
private const val CONCURRENT_PREVIEWS = 2
private const val SHEET_HEIGHT_SHARE = 0.82f
private const val RESULT_TILE_ASPECT_RATIO = 1.25f
private val ResultTileMinWidth = 132.dp

/** A distinct submission also permits retrying the same query. Its lifetime is the open sheet. */
private class GiphySearchRequest(
    val query: String,
)

internal sealed interface GiphySearchState {
    data object Idle : GiphySearchState

    data object Loading : GiphySearchState

    data object Failed : GiphySearchState

    data class Results(
        val items: List<GiphySearchResult>,
    ) : GiphySearchState
}

/** Opens an on-demand search and sends the selected GIF through the caller's conversation owner. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun GiphySearchSheet(
    apiKey: String,
    onDismiss: () -> Unit,
    onSend: (String) -> Unit,
) {
    val client = remember(apiKey) { GiphySearchClient(apiKey) }
    val configuration = LocalConfiguration.current
    val language = ConfigurationCompat.getLocales(configuration)[0]?.language.orEmpty()
    val focusManager = LocalFocusManager.current
    val searchField = remember { TextFieldState() }
    val query = searchField.text.toString().trim()
    var submitted by remember { mutableStateOf<GiphySearchRequest?>(null) }
    val activeRequest = submitted?.takeIf { it.query == query }
    var state by remember(activeRequest) { mutableStateOf<GiphySearchState>(GiphySearchState.Idle) }
    val previewGate = remember { Semaphore(CONCURRENT_PREVIEWS) }
    val submit = {
        if (query.isNotEmpty()) {
            focusManager.clearFocus()
            submitted = GiphySearchRequest(query)
        }
    }

    LaunchedEffect(query) {
        if (submitted?.query != query) submitted = null
    }
    LaunchedEffect(activeRequest, client, language) {
        if (activeRequest != null) {
            state = GiphySearchState.Loading
            val results = client.search(activeRequest.query, language)
            state = results?.let { GiphySearchState.Results(it) } ?: GiphySearchState.Failed
        }
    }

    WhiteNoiseModalBottomSheet(onDismissRequest = onDismiss, containerColor = amoledSheetContainerColor()) {
        WhiteNoiseSheetHeader(stringResource(R.string.giphy_search_title), onClose = onDismiss)
        GiphySearchContent(
            searchField = searchField,
            state = state,
            onSearch = submit,
            onSend = { onSend(it.media.wireText()) },
            preview = { result -> rememberGiphySearchPreview(result, previewGate) },
            modifier = Modifier.heightIn(max = configuration.screenHeightDp.dp * SHEET_HEIGHT_SHARE),
        )
    }
}

/** Renders the disclosure, search field and results in one scrollable area for compact and large-text windows. */
@Suppress("LongParameterList") // State, actions and reusable preview slot are independent.
@Composable
internal fun GiphySearchContent(
    searchField: TextFieldState,
    state: GiphySearchState,
    onSearch: () -> Unit,
    onSend: (GiphySearchResult) -> Unit,
    preview: @Composable (GiphySearchResult) -> ImageBitmap?,
    modifier: Modifier = Modifier,
) {
    LazyVerticalGrid(
        columns = GridCells.Adaptive(ResultTileMinWidth),
        modifier = modifier.fillMaxWidth(),
        contentPadding = PaddingValues(horizontal = WhiteNoiseSpacing.CompactScreenMargin, vertical = WhiteNoiseSpacing.Related),
        verticalArrangement = Arrangement.spacedBy(WhiteNoiseSpacing.Related),
        horizontalArrangement = Arrangement.spacedBy(WhiteNoiseSpacing.Related),
    ) {
        item(span = { GridItemSpan(maxLineSpan) }) {
            GiphySearchControls(searchField, onSearch)
        }
        val results = (state as? GiphySearchState.Results)?.items.orEmpty()
        if (results.isEmpty()) {
            item(span = { GridItemSpan(maxLineSpan) }) {
                GiphySearchStatus(state, onSearch)
            }
        }
        items(results, key = { it.media.url }) { result ->
            GiphySearchTile(result, preview(result)) { onSend(result) }
        }
    }
}

/** Gives the provider disclosure before any search and keeps the action available without an IME. */
@Composable
private fun GiphySearchControls(
    searchField: TextFieldState,
    onSearch: () -> Unit,
) {
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
            trailingIcon = {
                IconButton(onClick = onSearch, enabled = searchField.text.isNotBlank()) {
                    Icon(painterResource(R.drawable.ic_search), label)
                }
            },
            inputTransformation = InputTransformation.maxLength(GIPHY_QUERY_MAX_LENGTH),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            onKeyboardAction = { onSearch() },
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

/** A labeled send action remains available while the preview loads. */
@Composable
private fun GiphySearchTile(
    result: GiphySearchResult,
    image: ImageBitmap?,
    onSend: () -> Unit,
) {
    val title = result.title ?: stringResource(R.string.gif)
    Box(
        Modifier
            .aspectRatio(RESULT_TILE_ASPECT_RATIO)
            .clip(MaterialTheme.shapes.medium)
            .background(MaterialTheme.colorScheme.surfaceContainerHighest)
            .clickable(onClickLabel = stringResource(R.string.giphy_send_result, title), role = Role.Button, onClick = onSend),
        contentAlignment = Alignment.Center,
    ) {
        if (image == null) {
            Text(
                title,
                modifier = Modifier.padding(WhiteNoiseSpacing.Related),
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.Center,
            )
        } else {
            Image(image, title, Modifier.fillMaxSize(), contentScale = ContentScale.Fit)
        }
    }
}

/** Fetches and decodes a first frame only while a result is composed, using the isolated media decoder. */
@Composable
private fun rememberGiphySearchPreview(
    result: GiphySearchResult,
    gate: Semaphore,
): ImageBitmap? {
    val presentation =
        produceState<DecodedAttachmentPresentation?>(null, result.preview) {
            value =
                gate.withPermit {
                    val request = result.preview.imageRequest() ?: return@withPermit null
                    val bytes = fetchRemoteGiphyBytes(request) ?: return@withPermit null
                    decodeMessageAttachmentImage(bytes, request.mediaType, PREVIEW_EDGE_PX)
                }
        }
    OwnLatestAttachmentPresentation(result.preview, presentation)
    return (presentation.value as? DecodedAttachmentPresentation.Static)?.bitmap?.asImageBitmap()
}
