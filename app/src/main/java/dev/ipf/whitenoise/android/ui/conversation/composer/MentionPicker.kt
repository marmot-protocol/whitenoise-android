package dev.ipf.whitenoise.android.ui.conversation.composer

import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.key
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.ipf.whitenoise.android.R
import dev.ipf.whitenoise.android.core.MentionComposer
import dev.ipf.whitenoise.android.ui.common.AppDivider
import dev.ipf.whitenoise.android.ui.common.Avatar
import dev.ipf.whitenoise.android.ui.theme.amoledSurfaceBorderStroke

/**
 * Floating member picker for the group composer's `@`-mention flow (#414).
 * Anchored directly above the composer input row (it's the child placed just
 * before the input [Row] in [ComposerBar]); capped at ~50% of the viewport
 * height so the keyboard and a long roster both stay reachable. Tapping a row
 * inserts that member as a chip via [MentionComposer.insertMention].
 */
@Composable
internal fun MentionPicker(
    candidates: List<MentionComposer.Candidate>,
    onPick: (MentionComposer.Candidate) -> Unit,
) {
    val maxHeight = (LocalConfiguration.current.screenHeightDp * 0.5f).dp
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surface,
        border = amoledSurfaceBorderStroke(),
        tonalElevation = 3.dp,
        shadowElevation = 6.dp,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.heightIn(max = maxHeight)) {
            Text(
                stringResource(R.string.mention_picker_title),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            )
            AppDivider()
            LazyColumn(Modifier.fillMaxWidth()) {
                items(candidates, key = { it.accountIdHex }) { candidate ->
                    val mentionLabel = stringResource(R.string.mention_picker_member, candidate.displayName)
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier =
                            Modifier
                                .fillMaxWidth()
                                .clickable { onPick(candidate) }
                                .semantics { contentDescription = mentionLabel }
                                .padding(horizontal = 16.dp, vertical = 10.dp),
                    ) {
                        Avatar(
                            title = candidate.displayName,
                            seed = candidate.accountIdHex,
                            size = 36.dp,
                            pictureUrl = candidate.avatarUrl,
                        )
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text(
                                candidate.displayName,
                                style = MaterialTheme.typography.bodyLarge,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            val subtitle = candidate.nip05?.takeIf { it.isNotBlank() }
                            if (subtitle != null) {
                                Text(
                                    subtitle,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}
