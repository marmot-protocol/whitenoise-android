package dev.ipf.whitenoise.android.ui.conversation.messages

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import dev.ipf.whitenoise.android.R
import dev.ipf.whitenoise.android.core.EditState
import dev.ipf.whitenoise.android.ui.common.rememberedRelativeTime
import dev.ipf.whitenoise.android.ui.theme.amoledSheetContainerColor
import dev.ipf.whitenoise.android.ui.theme.amoledSurfaceBorderStroke

private data class EditHistoryRow(
    val versionNumber: Int,
    val text: String,
    val recordedAt: ULong,
    val isLatest: Boolean,
    val isOriginal: Boolean,
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun EditHistorySheet(
    original: String,
    originalTimestamp: ULong,
    editState: EditState,
    onDismissRequest: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    // Newest first reads as "this is what's shown now ← earlier revisions ← original".
    val rows =
        remember(original, originalTimestamp, editState) {
            buildList {
                editState.versions.reversed().forEachIndexed { reversedIndex, version ->
                    val versionNumber = editState.versions.size - reversedIndex
                    add(
                        EditHistoryRow(
                            versionNumber = versionNumber,
                            text = version.text,
                            recordedAt = version.recordedAt,
                            isLatest = reversedIndex == 0,
                            isOriginal = false,
                        ),
                    )
                }
                add(
                    EditHistoryRow(
                        versionNumber = 0,
                        text = original,
                        recordedAt = originalTimestamp,
                        isLatest = false,
                        isOriginal = true,
                    ),
                )
            }
        }
    ModalBottomSheet(
        onDismissRequest = onDismissRequest,
        sheetState = sheetState,
        containerColor = amoledSheetContainerColor(),
    ) {
        // The header is anchored above the scroll region so the title and
        // count chip remain visible while the user pages through a long edit
        // chain. The rail keeps its visual continuity because every row is
        // a child of the same Column — a LazyColumn would compose each row
        // independently and break the dot-to-dot line through the rail.
        Column(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 4.dp)
                .padding(bottom = 16.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Row(
                Modifier.fillMaxWidth().padding(bottom = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    text = stringResource(R.string.edit_history),
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.weight(1f),
                )
                Surface(
                    color = MaterialTheme.colorScheme.surfaceContainerHigh,
                    shape = RoundedCornerShape(999.dp),
                    border = amoledSurfaceBorderStroke(),
                ) {
                    Text(
                        text = stringResource(R.string.edited_count, editState.versions.size),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                    )
                }
            }
            Column(
                Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                rows.forEachIndexed { index, row ->
                    EditHistoryVersionRow(
                        row = row,
                        isFirst = index == 0,
                        isLast = index == rows.lastIndex,
                    )
                }
            }
        }
    }
}

@Composable
private fun EditHistoryVersionRow(
    row: EditHistoryRow,
    isFirst: Boolean,
    isLast: Boolean,
) {
    Row(
        Modifier.fillMaxWidth().height(IntrinsicSize.Min),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        // Leading rail: dot anchored to the label row + a vertical line
        // connecting consecutive dots so the column reads as a single
        // timeline rather than disconnected cards.
        Column(
            Modifier.width(16.dp).fillMaxHeight(),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Spacer(
                Modifier
                    .height(10.dp)
                    .width(2.dp)
                    .background(
                        if (isFirst) Color.Transparent else MaterialTheme.colorScheme.outlineVariant,
                    ),
            )
            val dotColor =
                when {
                    row.isLatest -> MaterialTheme.colorScheme.primary
                    row.isOriginal -> MaterialTheme.colorScheme.outline
                    else -> MaterialTheme.colorScheme.onSurfaceVariant
                }
            Box(Modifier.size(10.dp).background(dotColor, shape = CircleShape))
            Spacer(
                Modifier
                    .weight(1f)
                    .width(2.dp)
                    .background(
                        if (isLast) Color.Transparent else MaterialTheme.colorScheme.outlineVariant,
                    ),
            )
        }
        Column(
            Modifier.fillMaxWidth().padding(bottom = if (isLast) 0.dp else 12.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                if (row.isLatest) {
                    Surface(
                        color = MaterialTheme.colorScheme.primaryContainer,
                        shape = RoundedCornerShape(999.dp),
                    ) {
                        Text(
                            text = stringResource(R.string.edit_history_version_label, row.versionNumber),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onPrimaryContainer,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
                        )
                    }
                } else {
                    Text(
                        text =
                            if (row.isOriginal) {
                                stringResource(R.string.edit_history_original)
                            } else {
                                stringResource(R.string.edit_history_version_label, row.versionNumber)
                            },
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Text(
                    text = rememberedRelativeTime(row.recordedAt),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Surface(
                color =
                    if (row.isOriginal) {
                        MaterialTheme.colorScheme.surfaceContainer
                    } else {
                        MaterialTheme.colorScheme.surfaceContainerHigh
                    },
                shape = RoundedCornerShape(14.dp),
                border = amoledSurfaceBorderStroke(),
            ) {
                Text(
                    text = row.text,
                    style = MaterialTheme.typography.bodyMedium,
                    color =
                        if (row.isOriginal) {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        } else {
                            MaterialTheme.colorScheme.onSurface
                        },
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                )
            }
        }
    }
}
