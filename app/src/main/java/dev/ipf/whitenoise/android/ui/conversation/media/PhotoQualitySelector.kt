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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.ui.unit.sp
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

/** Maps the selectable quality to the send tier. */
private fun MediaQuality.sendQualityTier(): PhotoSendQualityTier =
    if (selectablePhotoQuality() == MediaQuality.Standard) {
        PhotoSendQualityTier.Standard
    } else {
        PhotoSendQualityTier.Hd
    }

/**
 * Preserves stable-slot quality ownership; callers may opt into themed preview colors while native editor defaults
 * stay intact.
 */
@Composable
internal fun PhotoQualitySelector(
    slotId: String,
    qualities: Map<String, PreparedPhotoQuality>,
    enabled: Boolean,
    onSelect: (String, MediaQuality) -> Unit,
    tint: Color = Color.White,
    themedSheet: Boolean = false,
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
            tint = tint.copy(alpha = tint.alpha * if (enabled) 0.92f else 0.38f),
        )
    }
    val targetSlotId = sheetSlotId
    val targetQuality = targetSlotId?.let(qualities::get)
    if (targetSlotId != null && targetQuality != null) {
        val targetTier = targetQuality.selectedQuality.sendQualityTier()
        PhotoSendQualitySheet(
            quality = targetQuality,
            selectedTier = targetTier,
            themed = themedSheet,
            onSelect = { tier ->
                sheetSlotId = null
                if (tier != targetTier) onSelect(targetSlotId, tier.quality)
            },
            onDismiss = { sheetSlotId = null },
        )
    }
}

/** Draws a fixed-size decorative quality emblem; its parent exposes the localized accessible label. */
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
            style = MaterialTheme.typography.labelSmall.copy(fontSize = 11.sp),
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

/** Shows the same truthful two output tiers with scrollable content at large accessibility font sizes. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PhotoSendQualitySheet(
    quality: PreparedPhotoQuality,
    selectedTier: PhotoSendQualityTier,
    themed: Boolean,
    onSelect: (PhotoSendQualityTier) -> Unit,
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = if (themed) MaterialTheme.colorScheme.surfaceContainerLow else QUALITY_SHEET_BACKGROUND,
        contentColor = if (themed) MaterialTheme.colorScheme.onSurface else Color.White,
    ) {
        Column(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
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
                color =
                    if (themed) MaterialTheme.colorScheme.onSurfaceVariant else Color.White.copy(alpha = 0.64f),
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
                    themed = themed,
                    onSelect = onSelect,
                )
            }
        }
    }
}

/** Pairs each native output tier with the selected surface foreground and unchanged dimensions/selection callback. */
@Composable
@Suppress("LongMethod", "CyclomaticComplexMethod")
private fun PhotoQualityOption(
    tier: PhotoSendQualityTier,
    dimensions: String?,
    selected: Boolean,
    themed: Boolean,
    onSelect: (PhotoSendQualityTier) -> Unit,
) {
    Surface(
        onClick = { onSelect(tier) },
        color =
            if (selected) {
                if (themed) {
                    MaterialTheme.colorScheme.primaryContainer
                } else {
                    MaterialTheme.colorScheme.primary.copy(alpha = 0.18f)
                }
            } else {
                if (themed) MaterialTheme.colorScheme.surfaceContainerHigh else Color.White.copy(alpha = 0.045f)
            },
        contentColor =
            when {
                !themed -> Color.White
                selected -> MaterialTheme.colorScheme.onPrimaryContainer
                else -> MaterialTheme.colorScheme.onSurface
            },
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
                tint =
                    when {
                        themed && selected -> MaterialTheme.colorScheme.onPrimaryContainer
                        themed -> MaterialTheme.colorScheme.onSurfaceVariant
                        selected -> MaterialTheme.colorScheme.primary
                        else -> Color.White.copy(alpha = 0.82f)
                    },
            )
            Column(modifier = Modifier.weight(1f)) {
                Text(stringResource(tier.label), style = MaterialTheme.typography.titleMedium)
                Text(
                    text = stringResource(tier.description),
                    color = if (themed) MaterialTheme.colorScheme.onSurfaceVariant else Color.White.copy(alpha = 0.62f),
                    style = MaterialTheme.typography.bodySmall,
                )
                dimensions?.let {
                    Text(
                        text = it,
                        color =
                            if (themed) MaterialTheme.colorScheme.onSurfaceVariant else Color.White.copy(alpha = 0.5f),
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
                        unselectedColor =
                            if (themed) MaterialTheme.colorScheme.onSurfaceVariant else Color.White.copy(alpha = 0.5f),
                    ),
            )
        }
    }
}
