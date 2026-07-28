@file:Suppress("FunctionNaming")

package dev.ipf.whitenoise.android.ui.conversation.composer

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import dev.ipf.whitenoise.android.R
import dev.ipf.whitenoise.android.ui.theme.amoledSurfaceBorderStroke

/**
 * Composer replacement while a disband converges or after it lands. Unlike
 * the frozen-group notice this is not an error state — the group ended by
 * design — so it reads in the neutral variant color.
 */
@Composable
internal fun DisbandedGroupComposerNotice(
    disbanded: Boolean,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier =
            modifier
                .fillMaxWidth()
                .navigationBarsPadding(),
        color = MaterialTheme.colorScheme.surface,
        border = amoledSurfaceBorderStroke(),
        tonalElevation = 3.dp,
    ) {
        Text(
            text =
                stringResource(
                    if (disbanded) {
                        R.string.conversation_disbanded_notice
                    } else {
                        R.string.conversation_disbanding_notice
                    },
                ),
            modifier =
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp, vertical = 14.dp),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
    }
}
