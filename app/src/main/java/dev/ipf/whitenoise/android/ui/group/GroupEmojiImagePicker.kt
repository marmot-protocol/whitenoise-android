package dev.ipf.whitenoise.android.ui.group

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.EmojiEmotions
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SheetValue
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import dev.ipf.whitenoise.android.R
import dev.ipf.whitenoise.android.media.GroupEmojiImageException
import dev.ipf.whitenoise.android.media.GroupEmojiImageRenderer
import dev.ipf.whitenoise.android.media.ImageUploadDraft
import dev.ipf.whitenoise.android.media.addGroupEmojiSelection
import dev.ipf.whitenoise.android.ui.common.rememberImageUploadPreview
import dev.ipf.whitenoise.android.ui.conversation.composer.EmojiPickerContent
import dev.ipf.whitenoise.android.ui.conversation.composer.EmojiPickerPurpose
import dev.ipf.whitenoise.android.ui.theme.amoledSheetContainerColor
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private sealed interface GroupEmojiImageRenderState {
    data object Empty : GroupEmojiImageRenderState

    data class Loading(
        val emojis: List<String>,
    ) : GroupEmojiImageRenderState

    data class Ready(
        val emojis: List<String>,
        val draft: ImageUploadDraft,
    ) : GroupEmojiImageRenderState

    data class Unsupported(
        val emojis: List<String>,
    ) : GroupEmojiImageRenderState

    data class Failed(
        val emojis: List<String>,
    ) : GroupEmojiImageRenderState
}

internal const val GROUP_EMOJI_IMAGE_PICKER_TAG = "group_emoji_image_picker"

// Fraction of the screen the sheet claims, tall enough for the emoji grid
// while leaving the underlying screen context visible at the top.
private const val PICKER_SHEET_HEIGHT_FRACTION = 0.92f

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun GroupEmojiImagePickerSheet(
    applyInFlight: Boolean,
    recentEmojis: List<String>,
    onEmojiUsed: (String) -> Unit,
    onApply: (ImageUploadDraft) -> Unit,
    onDismiss: () -> Unit,
    renderer: (List<String>) -> ImageUploadDraft = { GroupEmojiImageRenderer.render(it) },
) {
    var selectedEmojis by remember { mutableStateOf(emptyList<String>()) }
    var limitReached by remember { mutableStateOf(false) }
    val renderState by
        produceState<GroupEmojiImageRenderState>(
            initialValue = GroupEmojiImageRenderState.Empty,
            key1 = selectedEmojis,
        ) {
            if (selectedEmojis.isEmpty()) {
                value = GroupEmojiImageRenderState.Empty
                return@produceState
            }
            val renderEmojis = selectedEmojis
            value = GroupEmojiImageRenderState.Loading(renderEmojis)
            value =
                withContext(Dispatchers.Default) {
                    try {
                        GroupEmojiImageRenderState.Ready(
                            emojis = renderEmojis,
                            draft = renderer(renderEmojis),
                        )
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (_: GroupEmojiImageException.UnsupportedGlyph) {
                        GroupEmojiImageRenderState.Unsupported(renderEmojis)
                    } catch (_: Exception) {
                        GroupEmojiImageRenderState.Failed(renderEmojis)
                    }
                }
        }
    val currentRenderState = renderState
    val readyState =
        (currentRenderState as? GroupEmojiImageRenderState.Ready)
            ?.takeIf { it.emojis == selectedEmojis }
    val readyDraft = readyState?.draft
    val preview = rememberImageUploadPreview(readyDraft)
    val previewEmojiLabel = readyState?.emojis.orEmpty().joinToString(separator = " ")
    val currentApplyInFlight by rememberUpdatedState(applyInFlight)

    ModalBottomSheet(
        containerColor = amoledSheetContainerColor(),
        onDismissRequest = { if (!applyInFlight) onDismiss() },
        sheetState =
            rememberModalBottomSheetState(
                skipPartiallyExpanded = true,
                confirmValueChange = { value ->
                    value != SheetValue.Hidden || !currentApplyInFlight
                },
            ),
    ) {
        Column(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .fillMaxHeight(PICKER_SHEET_HEIGHT_FRACTION)
                    .navigationBarsPadding()
                    .imePadding()
                    .testTag(GROUP_EMOJI_IMAGE_PICKER_TAG)
                    .padding(horizontal = 18.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(
                text = stringResource(R.string.group_image_emoji_title),
                style = MaterialTheme.typography.titleLarge,
            )
            Text(
                text = stringResource(R.string.group_image_emoji_hint),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    modifier =
                        Modifier
                            .size(88.dp)
                            .clip(CircleShape)
                            .background(MaterialTheme.colorScheme.surfaceContainerHigh),
                    contentAlignment = Alignment.Center,
                ) {
                    when {
                        preview != null ->
                            Image(
                                bitmap = preview,
                                contentDescription =
                                    stringResource(
                                        R.string.group_image_emoji_preview_description,
                                        previewEmojiLabel,
                                    ),
                                contentScale = ContentScale.Crop,
                                modifier = Modifier.matchParentSize(),
                            )
                        currentRenderState is GroupEmojiImageRenderState.Loading &&
                            currentRenderState.emojis == selectedEmojis ->
                            CircularProgressIndicator(modifier = Modifier.size(26.dp), strokeWidth = 2.dp)
                        else ->
                            Icon(
                                Icons.Default.EmojiEmotions,
                                contentDescription = null,
                                modifier = Modifier.size(36.dp),
                            )
                    }
                }
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    if (selectedEmojis.isEmpty()) {
                        Text(
                            text = stringResource(R.string.group_image_emoji_none_selected),
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    } else {
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            selectedEmojis.forEachIndexed { index, emoji ->
                                OutlinedButton(
                                    onClick = {
                                        selectedEmojis = selectedEmojis.toMutableList().also { it.removeAt(index) }
                                        limitReached = false
                                    },
                                    enabled = !applyInFlight,
                                ) {
                                    Text(
                                        text = "$emoji ×",
                                        textAlign = TextAlign.Center,
                                    )
                                }
                            }
                        }
                    }
                    Text(
                        text = stringResource(R.string.group_image_emoji_private),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            val errorText =
                when {
                    limitReached -> stringResource(R.string.group_image_emoji_limit)
                    currentRenderState is GroupEmojiImageRenderState.Unsupported &&
                        currentRenderState.emojis == selectedEmojis ->
                        stringResource(R.string.group_image_emoji_unsupported)
                    currentRenderState is GroupEmojiImageRenderState.Failed &&
                        currentRenderState.emojis == selectedEmojis ->
                        stringResource(R.string.toast_couldnt_prepare_image)
                    else -> null
                }
            errorText?.let {
                Text(
                    text = it,
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            EmojiPickerContent(
                onEmojiPicked = { emoji ->
                    val update = addGroupEmojiSelection(selectedEmojis, emoji)
                    // Record recent-emoji usage only for accepted picks — a
                    // rejected third tap or a blank emoji must not pollute the
                    // shared recents list with something the user never applied.
                    if (update.emojis.size > selectedEmojis.size) onEmojiUsed(emoji)
                    selectedEmojis = update.emojis
                    limitReached = update.limitReached
                },
                purpose = EmojiPickerPurpose.USE,
                recentEmojis = recentEmojis,
                searchFieldAlwaysVisible = true,
                selectionEnabled = !applyInFlight,
                modifier = Modifier.fillMaxWidth().weight(1f),
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                TextButton(
                    onClick = onDismiss,
                    enabled = !applyInFlight,
                    modifier = Modifier.weight(1f),
                ) {
                    Text(stringResource(R.string.cancel))
                }
                Button(
                    onClick = { readyDraft?.let(onApply) },
                    enabled = readyDraft != null && !applyInFlight,
                    modifier = Modifier.weight(1f),
                ) {
                    if (applyInFlight) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(18.dp),
                            strokeWidth = 2.dp,
                            color = MaterialTheme.colorScheme.onPrimary,
                        )
                        Spacer(Modifier.width(8.dp))
                    }
                    Text(stringResource(R.string.group_image_emoji_use))
                }
            }
        }
    }
}
