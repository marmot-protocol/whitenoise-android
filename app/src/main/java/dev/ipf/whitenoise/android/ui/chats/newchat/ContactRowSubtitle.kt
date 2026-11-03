package dev.ipf.whitenoise.android.ui.chats.newchat

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.style.TextOverflow
import dev.ipf.whitenoise.android.ui.common.PublicKeyCopyText

/**
 * The row's second line: a [stateOnly] subtitle is already announced by the row, a [publicKey] subtitle copies that
 * whole key on its own tap, and anything else is plain text.
 */
@Composable
@Suppress("FunctionNaming")
internal fun ContactRowSubtitle(
    subtitle: String,
    stateOnly: Boolean,
    publicKey: String?,
    enabled: Boolean,
) {
    val style = MaterialTheme.typography.bodyMedium
    val color = MaterialTheme.colorScheme.onSurfaceVariant
    if (publicKey != null && !stateOnly) {
        PublicKeyCopyText(subtitle, publicKey, style = style, color = color, enabled = enabled)
    } else {
        Text(
            subtitle,
            style = style,
            color = color,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = if (stateOnly) Modifier.clearAndSetSemantics { } else Modifier,
        )
    }
}
