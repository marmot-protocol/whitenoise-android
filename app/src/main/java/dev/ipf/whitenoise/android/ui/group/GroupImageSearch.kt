package dev.ipf.whitenoise.android.ui.group

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.input.key.key
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.ipf.whitenoise.android.R
import dev.ipf.whitenoise.android.core.AvatarImageLoader
import dev.ipf.whitenoise.android.media.DuckDuckGoImageSearchClient
import dev.ipf.whitenoise.android.media.ImageSearchClient
import dev.ipf.whitenoise.android.media.ImageSearchException
import dev.ipf.whitenoise.android.media.ImageSearchResult
import dev.ipf.whitenoise.android.media.sanitizeHttpsAvatarUrl
import dev.ipf.whitenoise.android.ui.common.Avatar
import dev.ipf.whitenoise.android.ui.settings.subtitleRes
import dev.ipf.whitenoise.android.ui.theme.amoledSheetContainerColor
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/** Which `ImageSearchSheet` button is currently driving an in-flight
 *  mutation, so the sheet can place the spinner on it. */
private enum class GroupImageAction { Apply, Remove }

/**
 * Bottom sheet that lets an admin pick a new group avatar.
 *
 * Two entry points: (1) the URL TextField (manual HTTPS paste with live
 * preview + validation), (2) the search field (DuckDuckGo image search,
 * results shown as a grid of thumbnails). The sheet commits the change
 * itself via [onApply] — the caller wires that to the avatar-update FFI
 * and presents a success toast.
 *
 * When the group already has an avatar, a destructive "Remove image"
 * button is exposed, which calls [onApply] with `null` (caller maps that
 * to clearing the avatar).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ImageSearchSheet(
    initialUrl: String,
    header: String,
    title: String,
    seed: String,
    urlLabel: String,
    applyInFlight: Boolean,
    onApply: (String?) -> Unit,
    onDismiss: () -> Unit,
    searchClient: ImageSearchClient = remember { DuckDuckGoImageSearchClient() },
) {
    // Tracks which button initiated the current in-flight mutation, so the
    // spinner lands on THAT button and the other one just greys out. Local
    // to the sheet because the caller's `applyInFlight` is a binary
    // "anything running" flag. Reset to null when the mutation completes
    // (success closes the sheet via the caller; failure keeps the sheet
    // open and unlocks the buttons for a retry).
    var pendingAction by remember { mutableStateOf<GroupImageAction?>(null) }
    LaunchedEffect(applyInFlight) {
        if (!applyInFlight) pendingAction = null
    }
    val scope = rememberCoroutineScope()
    var urlDraft by remember(initialUrl) { mutableStateOf(initialUrl) }
    var queryDraft by remember { mutableStateOf("") }
    var results by remember { mutableStateOf<List<ImageSearchResult>>(emptyList()) }
    var isSearching by remember { mutableStateOf(false) }
    var searchErrorRes by remember { mutableStateOf<Int?>(null) }
    // Ticket counter for stale-result guarding. Each `launchSearch()` bumps
    // it and the in-flight coroutine captures that ticket value; on
    // completion, the coroutine only writes back to `results` /
    // `searchErrorRes` / `isSearching` if `requestId` still equals its
    // captured ticket. Without this, a slow first search can resolve AFTER
    // a faster second search has already populated the UI, clobbering the
    // new view with stale thumbnails. `Job.cancel()` alone isn't enough
    // because cancellation is cooperative — a returning coroutine still
    // executes its tail past the suspension point.
    var requestId by remember { mutableStateOf(0) }
    var searchJob by remember { mutableStateOf<Job?>(null) }
    val trimmedUrl = urlDraft.trim()
    val previewUrl =
        remember(trimmedUrl) {
            // Same HTTPS-only safety check the picker applies; an
            // unsanitized URL won't render in the preview avatar either.
            sanitizeHttpsAvatarUrl(trimmedUrl)
        }
    val emptyQueryRes = R.string.group_image_search_enter_query
    val missingTokenRes = R.string.group_image_search_unavailable
    val badResponseRes = R.string.group_image_search_bad_response
    val noResultsRes = R.string.group_image_search_no_results

    DisposableEffect(Unit) {
        onDispose { searchJob?.cancel() }
    }

    fun launchSearch() {
        searchJob?.cancel()
        val q = queryDraft.trim()
        if (q.isEmpty()) {
            searchErrorRes = emptyQueryRes
            results = emptyList()
            return
        }
        val ticket = requestId + 1
        requestId = ticket
        searchErrorRes = null
        results = emptyList()
        isSearching = true
        searchJob =
            scope.launch {
                try {
                    val hits = searchClient.search(q)
                    if (requestId != ticket) return@launch
                    results = hits
                    searchErrorRes = if (hits.isEmpty()) noResultsRes else null
                } catch (_: ImageSearchException.EmptyQuery) {
                    if (requestId == ticket) searchErrorRes = emptyQueryRes
                } catch (_: ImageSearchException.MissingToken) {
                    if (requestId == ticket) searchErrorRes = missingTokenRes
                } catch (e: CancellationException) {
                    // Rethrow the original so the cause chain stays
                    // intact for downstream loggers + structured
                    // concurrency tracking.
                    throw e
                } catch (_: Throwable) {
                    if (requestId == ticket) searchErrorRes = badResponseRes
                } finally {
                    if (requestId == ticket) isSearching = false
                }
            }
    }

    ModalBottomSheet(
        containerColor = amoledSheetContainerColor(),
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
    ) {
        Column(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .navigationBarsPadding()
                    .imePadding()
                    .padding(horizontal = 20.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Text(
                header,
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.SemiBold,
            )
            // Live preview row: avatar bubble seeded from the current draft
            // URL, plus the entity's name so the user knows what they're
            // editing.
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                Avatar(
                    title = title,
                    seed = seed,
                    size = 64.dp,
                    pictureUrl = previewUrl,
                )
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        title.ifBlank { header },
                        style = MaterialTheme.typography.titleMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    val subtitleRes =
                        when {
                            trimmedUrl.isEmpty() -> R.string.group_image_search_preview_subtitle_empty
                            previewUrl == null -> R.string.group_image_search_preview_subtitle_invalid
                            else -> R.string.group_image_search_preview_subtitle_ready
                        }
                    Text(
                        stringResource(subtitleRes),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            OutlinedTextField(
                value = urlDraft,
                onValueChange = { urlDraft = it },
                label = { Text(urlLabel) },
                placeholder = { Text("https://example.com/image.jpg") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
            )
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                OutlinedTextField(
                    value = queryDraft,
                    onValueChange = { queryDraft = it },
                    label = { Text(stringResource(R.string.group_image_search_query_label)) },
                    placeholder = { Text(stringResource(R.string.group_image_search_query_hint)) },
                    singleLine = true,
                    modifier = Modifier.weight(1f),
                    keyboardOptions =
                        KeyboardOptions(
                            imeAction = ImeAction.Search,
                            keyboardType = KeyboardType.Text,
                        ),
                    keyboardActions = KeyboardActions(onSearch = { launchSearch() }),
                )
                IconButton(
                    onClick = { launchSearch() },
                    enabled = !isSearching && queryDraft.trim().isNotEmpty(),
                ) {
                    if (isSearching) {
                        CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                    } else {
                        Icon(
                            Icons.Default.Search,
                            contentDescription = stringResource(R.string.group_image_search_action),
                        )
                    }
                }
            }
            searchErrorRes?.let { errRes ->
                Text(
                    stringResource(errRes),
                    style = MaterialTheme.typography.bodySmall,
                    color =
                        if (errRes == noResultsRes || errRes == emptyQueryRes) {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        } else {
                            MaterialTheme.colorScheme.error
                        },
                )
            }
            if (results.isNotEmpty()) {
                // Bounded height so the grid scrolls inside the sheet rather
                // than fighting the sheet's own gesture for vertical scrolling.
                LazyVerticalGrid(
                    columns = GridCells.Adaptive(minSize = 100.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .heightIn(max = 360.dp),
                ) {
                    items(results.distinctBy { it.imageUrl }, key = { it.imageUrl }) { hit ->
                        GroupImageSearchTile(
                            hit = hit,
                            isSelected = hit.imageUrl == trimmedUrl,
                            onTap = { urlDraft = hit.imageUrl },
                        )
                    }
                }
            }
            // Destructive "Remove image" only when the group ALREADY has an
            // avatar — for a group without one there's nothing to remove.
            // Greyed out while the mutation is running (no double-tap into
            // a silently no-op'd second call inside withMutationLockResult).
            if (initialUrl.isNotBlank()) {
                TextButton(
                    onClick = {
                        pendingAction = GroupImageAction.Remove
                        onApply(null)
                    },
                    enabled = !applyInFlight,
                    modifier = Modifier.fillMaxWidth(),
                    colors =
                        ButtonDefaults.textButtonColors(
                            contentColor = MaterialTheme.colorScheme.error,
                        ),
                ) {
                    if (pendingAction == GroupImageAction.Remove) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(18.dp),
                            strokeWidth = 2.dp,
                            color = MaterialTheme.colorScheme.error,
                        )
                    } else {
                        Icon(Icons.Default.Delete, contentDescription = null)
                    }
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(R.string.group_image_search_remove))
                }
            }
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.fillMaxWidth(),
            ) {
                OutlinedButton(
                    onClick = onDismiss,
                    enabled = !applyInFlight,
                    modifier = Modifier.weight(1f),
                ) {
                    Text(stringResource(R.string.cancel))
                }
                Button(
                    // Persist the SANITIZED URL so any normalization
                    // (schemeless `//host/path` upgrade, trim) survives
                    // through to storage — saving the raw draft would
                    // drop the work `sanitizeHttpsAvatarUrl` already did.
                    onClick = {
                        previewUrl?.let { sanitized ->
                            pendingAction = GroupImageAction.Apply
                            onApply(sanitized)
                        }
                    },
                    modifier = Modifier.weight(1f),
                    // Apply is the additive path ONLY. Clearing the avatar
                    // is exclusively the "Remove image" button's job — a
                    // primary button that doubles as a destructive action
                    // makes accidental avatar loss one mistap away.
                    enabled = previewUrl != null && !applyInFlight,
                ) {
                    if (pendingAction == GroupImageAction.Apply) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(18.dp),
                            strokeWidth = 2.dp,
                            color = MaterialTheme.colorScheme.onPrimary,
                        )
                    } else {
                        Text(stringResource(R.string.group_image_search_apply))
                    }
                }
            }
        }
    }
}

@Composable
private fun GroupImageSearchTile(
    hit: ImageSearchResult,
    isSelected: Boolean,
    onTap: () -> Unit,
) {
    val thumbnailKey = hit.thumbnailUrl ?: hit.imageUrl
    val thumbnail by produceState<ImageBitmap?>(
        initialValue = AvatarImageLoader.peek(thumbnailKey),
        key1 = thumbnailKey,
    ) {
        if (value == null) value = AvatarImageLoader.load(thumbnailKey)
    }
    val borderColor =
        if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline
    Surface(
        modifier =
            Modifier
                .aspectRatio(1f)
                // TalkBack: announce the current selection so users hear
                // which thumbnail is staged. Without this, the only cue is
                // the border color change, which is inaccessible.
                .semantics { selected = isSelected }
                .clickable(onClick = onTap),
        shape = RoundedCornerShape(10.dp),
        color = MaterialTheme.colorScheme.surfaceVariant,
        border = BorderStroke(if (isSelected) 2.dp else 1.dp, borderColor),
    ) {
        Box(contentAlignment = Alignment.Center) {
            val bmp = thumbnail
            if (bmp != null) {
                Image(
                    bitmap = bmp,
                    contentDescription = hit.title.ifBlank { hit.sourceHost ?: "" },
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                )
            } else {
                CircularProgressIndicator(
                    modifier = Modifier.size(20.dp),
                    strokeWidth = 2.dp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            // Footer strip: source host (left) and pixel dimensions (right).
            // Surfacing the host helps avoid picking trackable hotlinked
            // images by mistake; the dimensions hint at "is this big enough
            // to use as an avatar" before the user commits.
            val host = hit.sourceHost?.takeIf { it.isNotBlank() }
            val dims = hit.dimensionsLabel?.takeIf { it.isNotBlank() }
            if (host != null || dims != null) {
                Row(
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .align(Alignment.BottomCenter)
                            .background(Color.Black.copy(alpha = 0.5f))
                            .padding(horizontal = 6.dp, vertical = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    if (host != null) {
                        Text(
                            host,
                            color = Color.White,
                            style = MaterialTheme.typography.labelSmall,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f, fill = false),
                        )
                    }
                    if (dims != null) {
                        if (host != null) Spacer(Modifier.weight(1f))
                        Text(
                            dims,
                            color = Color.White,
                            style = MaterialTheme.typography.labelSmall,
                            maxLines = 1,
                        )
                    }
                }
            }
        }
    }
}
