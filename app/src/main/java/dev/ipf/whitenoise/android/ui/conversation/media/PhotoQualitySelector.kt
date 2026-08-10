@file:Suppress("FunctionNaming") // Jetpack Compose functions use UpperCamelCase.

package dev.ipf.whitenoise.android.ui.conversation.media

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.RadioButton
import androidx.compose.material3.RadioButtonDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import dev.ipf.whitenoise.android.R
import dev.ipf.whitenoise.android.state.MediaQuality

private const val BADGE_SLASH_START_X = 0.16f
private const val BADGE_SLASH_START_Y = 0.86f
private const val BADGE_SLASH_END_X = 0.84f
private const val BADGE_SLASH_END_Y = 0.14f
private const val BADGE_SLASH_WIDTH_DP = 1.8f
private val QUALITY_SHEET_BACKGROUND = Color(0xFF17191B)

internal data class PreparedPhotoQuality(
    val selectedQuality: MediaQuality,
    val standardDimensions: String?,
    val hdDimensions: String?,
)

/** Keep the selected tier truthful when Low or Original bytes are retained unchanged. */
internal fun photoApprovalOutputQuality(
    selectedQuality: MediaQuality,
    optionQuality: MediaQuality,
): MediaQuality =
    if (selectedQuality.sendQualityTier() == optionQuality.sendQualityTier()) {
        selectedQuality
    } else {
        optionQuality
    }

internal fun MediaQuality.selectablePhotoQuality(): MediaQuality =
    when (this) {
        MediaQuality.Low,
        MediaQuality.Standard,
        -> MediaQuality.Standard
        MediaQuality.High,
        MediaQuality.Original,
        -> MediaQuality.High
    }

private enum class PhotoSendQualityTier(
    val quality: MediaQuality,
    val label: Int,
    val description: Int,
) {
    Standard(
        quality = MediaQuality.Standard,
        label = R.string.photo_editor_quality_standard,
        description = R.string.photo_editor_quality_standard_description,
    ),
    Hd(
        quality = MediaQuality.High,
        label = R.string.photo_editor_quality_hd,
        description = R.string.photo_editor_quality_hd_description,
    ),
}

private fun MediaQuality.sendQualityTier(): PhotoSendQualityTier =
    if (selectablePhotoQuality() == MediaQuality.Standard) {
        PhotoSendQualityTier.Standard
    } else {
        PhotoSendQualityTier.Hd
    }

@Composable
internal fun PhotoQualitySelector(
    slotId: String,
    qualities: Map<String, PreparedPhotoQuality>,
    enabled: Boolean,
    onSelect: (String, MediaQuality) -> Unit,
) {
    val quality = qualities[slotId] ?: return
    val selectedTier = quality.selectedQuality.sendQualityTier()
    var sheetSlotId by rememberSaveable { mutableStateOf<String?>(null) }
    val label = stringResource(selectedTier.label)
    val description = stringResource(R.string.photo_editor_announcement_quality, label)
    IconButton(
        onClick = { sheetSlotId = slotId },
        enabled = enabled,
        modifier =
            Modifier
                .size(48.dp)
                .semantics {
                    contentDescription = description
                },
    ) {
        PhotoQualityBadge(
            tier = selectedTier,
            tint = Color.White.copy(alpha = if (enabled) 0.92f else 0.38f),
        )
    }
    val targetSlotId = sheetSlotId
    val targetQuality = targetSlotId?.let(qualities::get)
    if (targetSlotId != null && targetQuality != null) {
        val targetTier = targetQuality.selectedQuality.sendQualityTier()
        PhotoSendQualitySheet(
            quality = targetQuality,
            selectedTier = targetTier,
            onSelect = { tier ->
                sheetSlotId = null
                if (tier != targetTier) onSelect(targetSlotId, tier.quality)
            },
            onDismiss = { sheetSlotId = null },
        )
    }
}

@Composable
private fun PhotoQualityBadge(
    tier: PhotoSendQualityTier,
    tint: Color,
) {
    Box(
        modifier =
            Modifier
                .size(width = 30.dp, height = 22.dp)
                .border(1.5.dp, tint, RoundedCornerShape(5.dp))
                .clearAndSetSemantics { },
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = stringResource(R.string.photo_editor_quality_hd),
            color = tint,
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.Bold,
        )
        if (tier == PhotoSendQualityTier.Standard) {
            Canvas(Modifier.fillMaxSize()) {
                drawLine(
                    color = tint,
                    start = Offset(size.width * BADGE_SLASH_START_X, size.height * BADGE_SLASH_START_Y),
                    end = Offset(size.width * BADGE_SLASH_END_X, size.height * BADGE_SLASH_END_Y),
                    strokeWidth = BADGE_SLASH_WIDTH_DP.dp.toPx(),
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PhotoSendQualitySheet(
    quality: PreparedPhotoQuality,
    selectedTier: PhotoSendQualityTier,
    onSelect: (PhotoSendQualityTier) -> Unit,
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = QUALITY_SHEET_BACKGROUND,
        contentColor = Color.White,
    ) {
        Column(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .navigationBarsPadding()
                    .padding(start = 16.dp, end = 16.dp, bottom = 20.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = stringResource(R.string.photo_editor_quality),
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.padding(horizontal = 8.dp),
            )
            Text(
                text = stringResource(R.string.photo_editor_quality_sheet_description),
                style = MaterialTheme.typography.bodyMedium,
                color = Color.White.copy(alpha = 0.64f),
                modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
            )
            PhotoSendQualityTier.entries.forEach { tier ->
                PhotoQualityOption(
                    tier = tier,
                    dimensions =
                        if (tier == PhotoSendQualityTier.Standard) {
                            quality.standardDimensions
                        } else {
                            quality.hdDimensions
                        },
                    selected = tier == selectedTier,
                    onSelect = onSelect,
                )
            }
        }
    }
}

@Composable
private fun PhotoQualityOption(
    tier: PhotoSendQualityTier,
    dimensions: String?,
    selected: Boolean,
    onSelect: (PhotoSendQualityTier) -> Unit,
) {
    Surface(
        onClick = { onSelect(tier) },
        color =
            if (selected) {
                MaterialTheme.colorScheme.primary.copy(alpha = 0.18f)
            } else {
                Color.White.copy(alpha = 0.045f)
            },
        contentColor = Color.White,
        shape = RoundedCornerShape(18.dp),
        modifier =
            Modifier
                .fillMaxWidth()
                .heightIn(min = 76.dp)
                .semantics {
                    this.selected = selected
                    role = Role.RadioButton
                },
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            PhotoQualityBadge(
                tier = tier,
                tint = if (selected) MaterialTheme.colorScheme.primary else Color.White.copy(alpha = 0.82f),
            )
            Column(modifier = Modifier.weight(1f)) {
                Text(stringResource(tier.label), style = MaterialTheme.typography.titleMedium)
                Text(
                    text = stringResource(tier.description),
                    color = Color.White.copy(alpha = 0.62f),
                    style = MaterialTheme.typography.bodySmall,
                )
                dimensions?.let {
                    Text(
                        text = it,
                        color = Color.White.copy(alpha = 0.5f),
                        style = MaterialTheme.typography.labelMedium,
                    )
                }
            }
            RadioButton(
                selected = selected,
                onClick = null,
                colors =
                    RadioButtonDefaults.colors(
                        selectedColor = MaterialTheme.colorScheme.primary,
                        unselectedColor = Color.White.copy(alpha = 0.5f),
                    ),
            )
        }
    }
}
