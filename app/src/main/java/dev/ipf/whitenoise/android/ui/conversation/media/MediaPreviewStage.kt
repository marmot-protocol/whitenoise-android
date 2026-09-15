@file:Suppress("FunctionNaming") // Jetpack Compose functions use UpperCamelCase.

package dev.ipf.whitenoise.android.ui.conversation.media

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.indication
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.PagerState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Description
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.ipf.whitenoise.android.R
import dev.ipf.whitenoise.android.media.MediaPipeline

private const val THUMBNAIL_MAX_EDGE_PX = 256
private val PAGE_SPACING = 24.dp
private val FILMSTRIP_HEIGHT = 72.dp
private val FILMSTRIP_TARGET = 56.dp
private val FILMSTRIP_THUMB = 48.dp
private val INCLUSION_TARGET = 48.dp
private val INCLUSION_CIRCLE = 22.dp
private val INCLUSION_INSET = 6.dp
private val INCLUSION_GLYPH = 14.dp
private val SWIPE_DISMISS_DISTANCE = 120.dp
private val VIDEO_CONTROLS_INSET = 48.dp

/**
 * One page per staged frame, letterboxed and swipe-down dismissible. Only the settled page plays,
 * so video decoding never overlaps between neighbouring pages.
 */
@Composable
@Suppress("LongParameterList")
internal fun MediaPreviewPager(
    items: List<StagedPreviewItem>,
    pagerState: PagerState,
    metadata: Map<android.net.Uri, LocalPreviewMetadata>,
    prepared: Map<String, PreparedPhotoPreview>,
    excluded: Set<String>,
    onIncludedChange: (String, Boolean) -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    HorizontalPager(
        state = pagerState,
        modifier =
            modifier
                .then(rememberPreviewDismissGesture(!pagerState.isScrollInProgress, onDismiss))
                .testTag("conversation.media.preview.pager"),
        pageSpacing = PAGE_SPACING,
        key = { page -> mediaPreviewItemKey(items[page], page) },
    ) { page ->
        val item = items[page]
        val key = mediaPreviewItemKey(item, page)
        MediaPreviewPage(
            item = item,
            page = page,
            metadata = metadata[item.uri],
            prepared = (item as? StagedPreviewItem.Media)?.let { prepared[it.slot.id] },
            included = key !in excluded,
            active = pagerState.settledPage == page && !pagerState.isScrollInProgress,
            onIncludedChange = { included -> onIncludedChange(key, included) },
        )
    }
}

/** Stable per-frame identity; duplicate URIs staged twice stay independently includable. */
internal fun mediaPreviewItemKey(
    item: StagedPreviewItem,
    index: Int,
): String =
    when (item) {
        is StagedPreviewItem.Media -> "media:${item.slot.id}"
        is StagedPreviewItem.Document -> "document:$index:${item.uri}"
    }

/** Drags the page down past a fixed distance to leave the preview, matching the full viewer's gesture. */
@Composable
private fun rememberPreviewDismissGesture(
    enabled: Boolean,
    onDismiss: () -> Unit,
): Modifier {
    val threshold = with(LocalDensity.current) { SWIPE_DISMISS_DISTANCE.toPx() }
    val travelled = remember { mutableFloatStateOf(0f) }
    return if (!enabled) {
        Modifier
    } else {
        Modifier.pointerInput(threshold) {
            detectVerticalDragGestures(
                onDragStart = { travelled.floatValue = 0f },
                onDragEnd = {
                    if (travelled.floatValue >= threshold) onDismiss()
                    travelled.floatValue = 0f
                },
                onDragCancel = { travelled.floatValue = 0f },
                onVerticalDrag = { _, delta -> travelled.floatValue += delta },
            )
        }
    }
}

/** One staged frame: a video plays in place, a still is letterboxed, a document shows its name. */
@Composable
@Suppress("LongParameterList")
private fun MediaPreviewPage(
    item: StagedPreviewItem,
    page: Int,
    metadata: LocalPreviewMetadata?,
    prepared: PreparedPhotoPreview?,
    included: Boolean,
    active: Boolean,
    onIncludedChange: (Boolean) -> Unit,
) {
    val label = metadata?.displayName ?: item.uri.lastPathSegment.orEmpty()
    Box(
        Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background),
        contentAlignment = Alignment.Center,
    ) {
        if (metadata?.isVideo == true) {
            var controlsVisible by remember(item.uri) { mutableStateOf(true) }
            DraftVideoPreview(
                uri = item.uri,
                active = active,
                controlsVisible = controlsVisible,
                bottomControlsInset = VIDEO_CONTROLS_INSET,
                onToggleControls = { controlsVisible = !controlsVisible },
                onShowControls = { controlsVisible = true },
                modifier = Modifier.fillMaxSize().testTag("conversation.media.preview.video.$page"),
            )
            MediaPreviewInclusionButton(included, label, onIncludedChange, Modifier.align(Alignment.BottomEnd))
        } else if (item is StagedPreviewItem.Media) {
            MediaPreviewImagePage(item, page, metadata, prepared, included, label, onIncludedChange)
        } else {
            MediaPreviewDocumentPage(label)
            MediaPreviewInclusionButton(included, label, onIncludedChange, Modifier.align(Alignment.BottomEnd))
        }
    }
}

/** Letterboxes the still inside the page and pins its checkbox to the image's own bottom end. */
@Composable
@Suppress("LongParameterList")
private fun MediaPreviewImagePage(
    item: StagedPreviewItem.Media,
    page: Int,
    metadata: LocalPreviewMetadata?,
    prepared: PreparedPhotoPreview?,
    included: Boolean,
    label: String,
    onIncludedChange: (Boolean) -> Unit,
) {
    val bitmap =
        metadata?.let {
            rememberMediaPreviewBitmap(item.uri, it.isVideo, MediaPipeline.THUMBNAIL_MAX_EDGE_PX, prepared)
        }
    BoxWithConstraints(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        if (bitmap == null) {
            CircularProgressIndicator(
                modifier = Modifier.size(32.dp),
                strokeWidth = 2.dp,
                color = MaterialTheme.colorScheme.onBackground,
            )
            return@BoxWithConstraints
        }
        val ratio = bitmap.width.toFloat() / bitmap.height.coerceAtLeast(1)
        val available = if (maxHeight.value > 0f) maxWidth.value / maxHeight.value else ratio
        val mediaWidth = if (ratio >= available) maxWidth else maxHeight * ratio
        val mediaHeight = if (ratio >= available) maxWidth / ratio else maxHeight
        Box(
            Modifier
                .width(mediaWidth)
                .height(mediaHeight)
                .testTag("conversation.media.preview.image.$page"),
        ) {
            Image(
                bitmap = bitmap,
                contentDescription = prepared?.let { stringResource(R.string.photo_editor_prepared) },
                contentScale = ContentScale.Fit,
                modifier = Modifier.fillMaxSize(),
            )
            MediaPreviewInclusionButton(included, label, onIncludedChange, Modifier.align(Alignment.BottomEnd))
        }
    }
}

/** Documents have no frame to letterbox, so the page states their name instead. */
@Composable
private fun MediaPreviewDocumentPage(label: String) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(
            Icons.Default.Description,
            contentDescription = null,
            modifier = Modifier.size(56.dp),
            tint = MaterialTheme.colorScheme.onBackground,
        )
        Spacer(Modifier.height(12.dp))
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onBackground,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center,
        )
    }
}

/** Unchecking a frame drops it from the draft when Done is pressed; the target stays a full 48dp. */
@Composable
internal fun MediaPreviewInclusionButton(
    included: Boolean,
    label: String,
    onIncludedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val state = stringResource(if (included) R.string.media_included else R.string.media_excluded)
    Box(
        modifier =
            modifier
                .size(INCLUSION_TARGET)
                .toggleable(
                    value = included,
                    interactionSource = interactionSource,
                    indication = null,
                    role = Role.Checkbox,
                    onValueChange = onIncludedChange,
                ).semantics {
                    contentDescription = label
                    stateDescription = state
                }.testTag("conversation.media.inclusion.target"),
        contentAlignment = Alignment.BottomEnd,
    ) {
        Box(
            modifier =
                Modifier
                    .padding(end = INCLUSION_INSET, bottom = INCLUSION_INSET)
                    .size(INCLUSION_CIRCLE)
                    .clip(CircleShape)
                    .background(
                        if (included) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.surfaceContainerHighest
                        },
                    ).then(
                        if (included) {
                            Modifier
                        } else {
                            Modifier.border(1.dp, MaterialTheme.colorScheme.outline, CircleShape)
                        },
                    ).indication(interactionSource, ripple(radius = INCLUSION_CIRCLE / 2))
                    .testTag("conversation.media.inclusion.visual"),
            contentAlignment = Alignment.Center,
        ) {
            if (included) {
                Icon(
                    painter = painterResource(R.drawable.ic_check),
                    contentDescription = null,
                    modifier = Modifier.size(INCLUSION_GLYPH),
                    tint = MaterialTheme.colorScheme.onPrimary,
                )
            }
        }
    }
}

/** Frame chooser under the pager, shown only once more than one frame is staged. */
@Composable
@Suppress("LongParameterList")
internal fun MediaPreviewFilmstrip(
    items: List<StagedPreviewItem>,
    metadata: Map<android.net.Uri, LocalPreviewMetadata>,
    prepared: Map<String, PreparedPhotoPreview>,
    selectedIndex: Int,
    enabled: Boolean,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
    trailingContent: (@Composable () -> Unit)? = null,
) {
    LazyRow(
        modifier = modifier.fillMaxWidth().height(FILMSTRIP_HEIGHT),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
    ) {
        itemsIndexed(items, key = { index, item -> mediaPreviewItemKey(item, index) }) { index, item ->
            MediaPreviewThumb(
                item = item,
                metadata = metadata[item.uri],
                prepared = (item as? StagedPreviewItem.Media)?.let { prepared[it.slot.id] },
                position = index + 1,
                selected = index == selectedIndex,
                enabled = enabled,
                onClick = { onSelect(index) },
            )
        }
        trailingContent?.let { content -> item(key = "media_preview_trailing") { content() } }
    }
}

/** One 56dp target around a 48dp thumbnail; the current frame is the only one outlined. */
@Composable
@Suppress("LongParameterList")
private fun MediaPreviewThumb(
    item: StagedPreviewItem,
    metadata: LocalPreviewMetadata?,
    prepared: PreparedPhotoPreview?,
    position: Int,
    selected: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    val positionDescription = stringResource(R.string.media_preview_position_badge, position)
    Box(
        modifier =
            Modifier
                .size(FILMSTRIP_TARGET)
                .selectable(selected = selected, enabled = enabled, onClick = onClick)
                .semantics { contentDescription = positionDescription }
                .testTag("conversation.media.thumbnail.target"),
        contentAlignment = Alignment.Center,
    ) {
        val thumbModifier =
            Modifier
                .size(FILMSTRIP_THUMB)
                .then(
                    if (selected) {
                        Modifier.border(1.dp, MaterialTheme.colorScheme.onBackground, MaterialTheme.shapes.small)
                    } else {
                        Modifier
                    },
                ).clip(MaterialTheme.shapes.small)
        val bitmap =
            (item as? StagedPreviewItem.Media)?.let { media ->
                metadata?.let { rememberMediaPreviewBitmap(media.uri, it.isVideo, THUMBNAIL_MAX_EDGE_PX, prepared) }
            }
        if (bitmap != null) {
            Image(
                bitmap = bitmap,
                contentDescription = prepared?.let { stringResource(R.string.photo_editor_prepared) },
                contentScale = ContentScale.Crop,
                modifier = thumbModifier,
            )
        } else {
            Box(
                modifier = thumbModifier.background(MaterialTheme.colorScheme.surfaceContainerHigh),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    Icons.Default.Description,
                    contentDescription = null,
                    modifier = Modifier.size(20.dp),
                    tint = MaterialTheme.colorScheme.onBackground,
                )
            }
        }
    }
}
