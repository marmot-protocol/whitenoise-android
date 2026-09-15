@file:Suppress("FunctionNaming")

package dev.ipf.whitenoise.android.ui.conversation.media

import android.graphics.ImageDecoder
import android.graphics.drawable.Drawable
import android.net.Uri
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import dev.ipf.whitenoise.android.R
import dev.ipf.whitenoise.android.ui.common.AppDivider
import dev.ipf.whitenoise.android.ui.conversation.composer.ComposerAccessoryRemoveButton
import dev.ipf.whitenoise.android.ui.theme.amoledOutlineBorder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException
import kotlin.math.roundToInt

private const val VISUAL_CARD_HEIGHT = 112
private const val UTILITY_CARD_HEIGHT = 72
private const val VISUAL_MIN_WIDTH = 68
private const val VISUAL_MAX_WIDTH = 200
private const val CONTACT_CARD_WIDTH = 104
private const val FILE_CARD_WIDTH = 160
private const val VISUAL_SHELF_HEIGHT = 128
private const val UTILITY_SHELF_HEIGHT = 88
private const val PREVIEW_DECODE_EDGE = 256
private const val DEFAULT_ASPECT_RATIO = 4f / 3f
private const val FILENAME_SUFFIX_STEM_LENGTH = 3
private const val MEDIA_BADGE_ALPHA = 0.88f
private val SHELF_GUTTER = 8.dp
private val SHELF_DIVIDER_INSET = 12.dp
private val CARD_SHAPE =
    RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp, bottomStart = 12.dp, bottomEnd = 12.dp)

/** The three card silhouettes the prototype shelf draws: visual media, a shared contact, any other file. */
internal enum class ComposerAttachmentCardKind {
    Visual,
    Contact,
    File,
}

/** Prototype card box for one shelf entry: visual cards scale with their ratio, the rest are fixed. */
internal fun composerAttachmentCardSize(
    kind: ComposerAttachmentCardKind,
    aspectRatio: Float = DEFAULT_ASPECT_RATIO,
): Pair<Int, Int> =
    when (kind) {
        ComposerAttachmentCardKind.Visual -> composerVisualAttachmentWidth(aspectRatio) to VISUAL_CARD_HEIGHT
        ComposerAttachmentCardKind.Contact -> CONTACT_CARD_WIDTH to UTILITY_CARD_HEIGHT
        ComposerAttachmentCardKind.File -> FILE_CARD_WIDTH to UTILITY_CARD_HEIGHT
    }

/** The shelf only grows to its visual height once a photo, video or GIF is staged. */
internal fun composerShelfHeight(hasVisualAttachment: Boolean): Int {
    val height = if (hasVisualAttachment) VISUAL_SHELF_HEIGHT else UTILITY_SHELF_HEIGHT
    return height
}

/** Prototype visual card width, bounded independently of the original asset's size. */
internal fun composerVisualAttachmentWidth(aspectRatio: Float): Int =
    (VISUAL_CARD_HEIGHT * (aspectRatio.takeIf { it.isFinite() && it > 0f } ?: DEFAULT_ASPECT_RATIO))
        .roundToInt()
        .coerceIn(VISUAL_MIN_WIDTH, VISUAL_MAX_WIDTH)

/** Leaves the final three stem characters and extension readable under filename truncation. */
internal fun composerFilenameParts(filename: String): Pair<String, String> {
    val trimmed = filename.trim()
    val dot = trimmed.lastIndexOf('.').takeIf { it in 1 until trimmed.lastIndex }
    val stem = if (dot == null) trimmed else trimmed.substring(0, dot)
    val extension = if (dot == null) "" else trimmed.substring(dot)
    return if (stem.length <= FILENAME_SUFFIX_STEM_LENGTH) {
        "" to (stem + extension)
    } else {
        stem.dropLast(FILENAME_SUFFIX_STEM_LENGTH) to (stem.takeLast(FILENAME_SUFFIX_STEM_LENGTH) + extension)
    }
}

/** The same staged slots and prepared artifacts drive this shelf and the native preview/editor. */
@Composable
@Suppress("LongParameterList")
internal fun ComposerAttachmentShelf(
    mediaSlots: List<PendingMediaSlot>,
    documentUris: List<Uri>,
    prepared: Map<String, PreparedPhotoPreview>,
    onPreview: (Int) -> Unit,
    onRemoveMedia: (PendingMediaSlot) -> Unit,
    onRemoveDocument: (Int) -> Unit,
    enabled: Boolean = true,
) {
    val items = remember(mediaSlots, documentUris) { stagedPreviewItems(mediaSlots, documentUris) }
    val metadata = rememberPreviewMetadata(items)
    val hasVisual = items.any { it is StagedPreviewItem.Media }
    Column(Modifier.fillMaxWidth()) {
        LazyRow(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .height(composerShelfHeight(hasVisual).dp)
                    .testTag("conversation.composer.attachments"),
            contentPadding = PaddingValues(SHELF_GUTTER),
            horizontalArrangement = Arrangement.spacedBy(SHELF_GUTTER),
            verticalAlignment = Alignment.Bottom,
        ) {
            itemsIndexed(items, key = { index, item ->
                when (item) {
                    is StagedPreviewItem.Media -> item.slot.id
                    is StagedPreviewItem.Document -> "document:$index:${item.uri}"
                }
            }) { index, item ->
                ComposerAttachmentShelfItem(
                    item = item,
                    index = index,
                    metadata = metadata[item.uri],
                    prepared = prepared,
                    enabled = enabled,
                    onPreview = { onPreview(index) },
                    onRemove = {
                        val media = item as? StagedPreviewItem.Media
                        if (media != null) onRemoveMedia(media.slot) else onRemoveDocument(index - mediaSlots.size)
                    },
                )
            }
        }
        // The prototype only rules off the shelf once it carries visual media.
        if (hasVisual) AppDivider(Modifier.padding(horizontal = SHELF_DIVIDER_INSET))
    }
}

/** One shelf entry: decodes its own thumbnail or animation, then hands the prototype card its geometry. */
@Composable
@Suppress("LongParameterList")
private fun ComposerAttachmentShelfItem(
    item: StagedPreviewItem,
    index: Int,
    metadata: LocalPreviewMetadata?,
    prepared: Map<String, PreparedPhotoPreview>,
    enabled: Boolean,
    onPreview: () -> Unit,
    onRemove: () -> Unit,
) {
    val media = item as? StagedPreviewItem.Media
    val bitmap =
        if (media != null && metadata != null && !metadata.isGif) {
            rememberMediaPreviewBitmap(item.uri, metadata.isVideo, PREVIEW_DECODE_EDGE, prepared[media.slot.id])
        } else {
            null
        }
    val label = metadata?.displayName ?: item.uri.lastPathSegment ?: stringResource(R.string.reply_media_document)
    ComposerAttachmentShelfCard(
        label = label,
        enabled = enabled,
        kind = if (media != null) ComposerAttachmentCardKind.Visual else ComposerAttachmentCardKind.File,
        video = metadata?.isVideo == true,
        bitmap = bitmap,
        animation = if (media != null && metadata?.isGif == true) rememberShelfAnimation(item.uri) else null,
        gif = metadata?.isGif == true,
        onPreview = onPreview,
        onRemove = onRemove,
        modifier = Modifier.testTag("conversation.composer.attachment.$index"),
    )
}

/** Pure shelf presentation: only visual cards open the preview, and the 48dp removal target keeps its own action. */
@Composable
@Suppress("LongParameterList")
internal fun ComposerAttachmentShelfCard(
    label: String,
    kind: ComposerAttachmentCardKind,
    video: Boolean,
    bitmap: ImageBitmap?,
    onPreview: () -> Unit,
    onRemove: () -> Unit,
    modifier: Modifier = Modifier,
    animation: Drawable? = null,
    gif: Boolean = false,
    enabled: Boolean = true,
) {
    val visual = kind == ComposerAttachmentCardKind.Visual
    val ratio =
        animation?.takeIf { it.intrinsicHeight > 0 }?.let { it.intrinsicWidth.toFloat() / it.intrinsicHeight }
            ?: bitmap?.let { it.width.toFloat() / it.height } ?: DEFAULT_ASPECT_RATIO
    val (cardWidth, cardHeight) = composerAttachmentCardSize(kind, ratio)
    Surface(
        modifier =
            modifier
                .width(cardWidth.dp)
                .height(cardHeight.dp)
                .then(if (visual) Modifier.clickable(enabled = enabled, onClick = onPreview) else Modifier)
                .semantics { contentDescription = label },
        shape = CARD_SHAPE,
        color = MaterialTheme.colorScheme.surfaceContainer,
        border = amoledOutlineBorder(),
    ) {
        Box {
            if (visual) {
                ComposerAttachmentVisual(label, bitmap, animation, video, gif)
            } else {
                ComposerAttachmentFilename(label, Modifier.fillMaxSize())
            }
            ComposerAccessoryRemoveButton(
                onClick = onRemove,
                enabled = enabled,
                description = stringResource(R.string.media_attachment_remove) + ": " + label,
                highContrast = visual,
                modifier = Modifier.align(Alignment.TopEnd),
            )
        }
    }
}

/** Visual media fills its card over the highest container tone, badged for video playback or GIF animation. */
@Composable
private fun ComposerAttachmentVisual(
    label: String,
    bitmap: ImageBitmap?,
    animation: Drawable?,
    video: Boolean,
    gif: Boolean,
) {
    Box(
        Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.surfaceContainerHighest),
    ) {
        when {
            animation != null ->
                AnimatedDrawableAttachmentImage(animation, null, ContentScale.Crop, Modifier.fillMaxSize())
            bitmap != null -> Image(bitmap, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
            else ->
                Text(
                    label,
                    Modifier.align(Alignment.Center).padding(SHELF_GUTTER),
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                    textAlign = TextAlign.Center,
                )
        }
        if (gif) {
            Surface(
                modifier = Modifier.align(Alignment.BottomStart).padding(SHELF_GUTTER),
                shape = CircleShape,
                color = MaterialTheme.colorScheme.inverseSurface.copy(alpha = MEDIA_BADGE_ALPHA),
                contentColor = MaterialTheme.colorScheme.inverseOnSurface,
            ) {
                Text(
                    stringResource(R.string.gif),
                    Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.Bold,
                )
            }
        }
        if (video) {
            Surface(
                modifier = Modifier.align(Alignment.Center),
                shape = CircleShape,
                color = MaterialTheme.colorScheme.inverseSurface.copy(alpha = MEDIA_BADGE_ALPHA),
                contentColor = MaterialTheme.colorScheme.inverseOnSurface,
            ) {
                Icon(
                    painterResource(R.drawable.ic_play_arrow),
                    stringResource(R.string.reply_media_video),
                    Modifier.padding(SHELF_GUTTER).size(20.dp),
                )
            }
        }
    }
}

/** Filename direction remains LTR even in RTL chats so its extension stays at the physical suffix. */
@Composable
private fun ComposerAttachmentFilename(
    label: String,
    modifier: Modifier,
) {
    val (leading, suffix) = remember(label) { composerFilenameParts(label) }
    Column(
        modifier = modifier.padding(horizontal = SHELF_GUTTER),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(
            painterResource(R.drawable.ic_description),
            null,
            Modifier.size(28.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(4.dp))
        CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
                if (leading.isNotEmpty()) {
                    Text(
                        leading,
                        Modifier.weight(1f, fill = false),
                        style = MaterialTheme.typography.labelSmall,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Text(suffix, style = MaterialTheme.typography.labelSmall, maxLines = 1)
            }
        }
    }
}

/** Decode the chosen local animation at thumbnail size; the shared drawable renderer owns playback/disposal. */
@Composable
private fun rememberShelfAnimation(uri: Uri): Drawable? {
    val resolver = LocalContext.current.contentResolver
    return key(resolver, uri) {
        val drawable by produceState<Drawable?>(null, resolver, uri) {
            value =
                withContext(Dispatchers.IO) {
                    try {
                        ImageDecoder.decodeDrawable(ImageDecoder.createSource(resolver, uri)) { decoder, info, _ ->
                            val largestEdge = maxOf(info.size.width, info.size.height).coerceAtLeast(1)
                            val scale = minOf(1f, PREVIEW_DECODE_EDGE.toFloat() / largestEdge)
                            decoder.setTargetSize(
                                (info.size.width * scale).roundToInt().coerceAtLeast(1),
                                (info.size.height * scale).roundToInt().coerceAtLeast(1),
                            )
                        }
                    } catch (_: IOException) {
                        null
                    } catch (_: SecurityException) {
                        null
                    }
                }
        }
        drawable
    }
}
