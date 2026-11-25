package dev.ipf.whitenoise.android.ui.common

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import dev.ipf.whitenoise.android.ui.theme.Dimens
import dev.ipf.whitenoise.android.ui.theme.WhiteNoiseSpacing

/** Default insets of a [SectionHeader]: the 16 dp screen margin, with the gap above and below a group. */
private val DefaultSectionHeaderPadding =
    PaddingValues(
        start = Dimens.spaceLg,
        top = WhiteNoiseSpacing.FormField,
        end = Dimens.spaceLg,
        bottom = WhiteNoiseSpacing.Related,
    )

/**
 * The one label above a group of rows, cards or tiles: quiet label style, announced to TalkBack as a heading.
 * [modifier] applies to the heading text, or to the row around it when a [trailing] action is shown.
 */
@Suppress("FunctionNaming")
@Composable
fun SectionHeader(
    text: String,
    modifier: Modifier = Modifier,
    contentPadding: PaddingValues = DefaultSectionHeaderPadding,
    trailing: (@Composable RowScope.() -> Unit)? = null,
) {
    val heading = Modifier.semantics { heading() }
    val colour = MaterialTheme.colorScheme.onSurfaceVariant
    val style = MaterialTheme.typography.labelLarge
    if (trailing == null) {
        // Heading semantics sit outside the padding so the focus bounds keep the full inset.
        Text(
            text = text,
            modifier = modifier.fillMaxWidth().then(heading).padding(contentPadding),
            color = colour,
            style = style,
        )
    } else {
        Row(
            modifier = modifier.fillMaxWidth().padding(contentPadding),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(text = text, modifier = Modifier.weight(1f).then(heading), color = colour, style = style)
            trailing()
        }
    }
}
