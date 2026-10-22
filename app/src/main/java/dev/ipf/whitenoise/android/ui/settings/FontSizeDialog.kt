package dev.ipf.whitenoise.android.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import dev.ipf.whitenoise.android.R
import dev.ipf.whitenoise.android.state.AppFontScale
import dev.ipf.whitenoise.android.state.BubbleTheme
import dev.ipf.whitenoise.android.ui.common.WhiteNoiseAlertDialog
import dev.ipf.whitenoise.android.ui.common.WhiteNoiseDialogChoiceRow
import dev.ipf.whitenoise.android.ui.common.fadingVerticalScroll
import dev.ipf.whitenoise.android.ui.conversation.messages.resolveBubblePresentationArgb

/** Four persistent choices reuse the app's existing scale owner and inherited system × app density. */
@Composable
@Suppress("FunctionNaming")
internal fun FontSizeDialog(
    selected: AppFontScale,
    onSelect: (AppFontScale) -> Unit,
    onDismiss: () -> Unit,
    theme: BubbleTheme,
    mineArgb: Long?,
    otherArgb: Long?,
) {
    WhiteNoiseAlertDialog(
        modifier = Modifier.testTag("font_size.dialog"),
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.font_size)) },
        text = {
            Column(
                Modifier.fillMaxWidth().fadingVerticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(stringResource(R.string.font_size_preview), style = MaterialTheme.typography.labelMedium)
                FontSizeSample(theme, mineArgb, otherArgb)
                Column(Modifier.selectableGroup()) {
                    AppFontScale.entries.forEach { scale ->
                        WhiteNoiseDialogChoiceRow(
                            title = stringResource(scale.labelRes),
                            selected = selected == scale,
                            highlightSelected = false,
                            onClick = { if (selected != scale) onSelect(scale) },
                            modifier = Modifier.testTag("font_size.${scale.name}"),
                        )
                    }
                }
                Text(stringResource(R.string.appearance_font_size_help), style = MaterialTheme.typography.bodyMedium)
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.done)) } },
        dismissButton = {
            TextButton(onClick = { onSelect(AppFontScale.Default) }) {
                Text(stringResource(R.string.font_scale_default))
            }
        },
    )
}

/** Inert localized sample; inherits the current theme/font/density without applying scale a second time. */
@Composable
@Suppress("FunctionNaming")
private fun FontSizeSample(
    theme: BubbleTheme,
    mineArgb: Long?,
    otherArgb: Long?,
) {
    val tokens = previewBubbleTokens()
    val amoled = theme == BubbleTheme.Amoled
    val other =
        resolveBubblePresentationArgb(
            deleted = false,
            amoled = amoled,
            mine = false,
            otherArgb,
            tokens,
        )
    val mine =
        resolveBubblePresentationArgb(
            deleted = false,
            amoled = amoled,
            mine = true,
            mineArgb,
            tokens,
        )
    Column(Modifier.fillMaxWidth().testTag("font_size.preview"), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        PreviewBubble(
            text = stringResource(R.string.font_size_sample_received),
            presentation = other,
            mine = false,
            modifier = Modifier.align(Alignment.Start),
            time = "10:42",
        )
        PreviewBubble(
            text = stringResource(R.string.font_size_sample_sent),
            presentation = mine,
            mine = true,
            modifier = Modifier.align(Alignment.End),
            quote = stringResource(R.string.font_size_sample_quote),
            time = "10:43",
        )
    }
}
