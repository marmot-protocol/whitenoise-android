@file:Suppress("FunctionNaming")

package dev.ipf.whitenoise.android.ui.conversation.composer

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import dev.ipf.whitenoise.android.ui.common.BottomPanelSurface
import dev.ipf.whitenoise.android.ui.theme.Dimens
import dev.ipf.whitenoise.android.ui.theme.WhiteNoiseSpacing

/** Colour role of a [ComposerNotice]. */
internal enum class ComposerNoticeTone { Neutral, Error }

/**
 * The one read-only panel that replaces the composer (blocked DM, disbanded, frozen, removed member): shared
 * padding, colour role by [tone] and navigation-bar inset. [action] sits under the centred [text].
 */
@Composable
internal fun ComposerNotice(
    text: String,
    modifier: Modifier = Modifier,
    tone: ComposerNoticeTone = ComposerNoticeTone.Neutral,
    action: (@Composable ColumnScope.() -> Unit)? = null,
) {
    BottomPanelSurface(modifier = modifier.navigationBarsPadding()) {
        Column(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .padding(
                        horizontal = WhiteNoiseSpacing.CompactScreenMargin,
                        vertical = Dimens.spaceMd,
                    ),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                text = text,
                style = MaterialTheme.typography.bodyMedium,
                color =
                    when (tone) {
                        ComposerNoticeTone.Neutral -> MaterialTheme.colorScheme.onSurfaceVariant
                        ComposerNoticeTone.Error -> MaterialTheme.colorScheme.error
                    },
                textAlign = TextAlign.Center,
            )
            action?.invoke(this)
        }
    }
}
