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
import dev.ipf.whitenoise.android.state.AppFont
import dev.ipf.whitenoise.android.state.AppFontScale
import dev.ipf.whitenoise.android.state.BubbleTheme
import dev.ipf.whitenoise.android.ui.common.WhiteNoiseAlertDialog
import dev.ipf.whitenoise.android.ui.common.WhiteNoiseDialogChoiceRow
import dev.ipf.whitenoise.android.ui.common.fadingVerticalScroll
import dev.ipf.whitenoise.android.ui.common.rememberedClockTime
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
                FontAppearanceSample(theme, mineArgb, otherArgb, Modifier.testTag("font_size.preview"))
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

/** Font choices reuse the same live theme sample and persistent preference owner as font size. */
@Composable
@Suppress("FunctionNaming")
internal fun FontFamilyDialog(
    selected: AppFont,
    label: (AppFont) -> String,
    onSelect: (AppFont) -> Unit,
    onDismiss: () -> Unit,
    theme: BubbleTheme,
    mineArgb: Long?,
    otherArgb: Long?,
) {
    WhiteNoiseAlertDialog(
        modifier = Modifier.testTag("font_family.dialog"),
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.app_font)) },
        text = {
            Column(
                Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(stringResource(R.string.font_size_preview), style = MaterialTheme.typography.labelMedium)
                Column(
                    Modifier
                        .weight(1f, fill = false)
                        .testTag("font_family.preview.viewport")
                        .fadingVerticalScroll(rememberScrollState()),
                ) {
                    FontAppearanceSample(theme, mineArgb, otherArgb, Modifier.testTag("font_family.preview"))
                }
                Column(
                    Modifier
                        .weight(1f, fill = false)
                        .testTag("font_family.choices")
                        .fadingVerticalScroll(rememberScrollState())
                        .selectableGroup(),
                ) {
                    AppFont.entries.forEach { font ->
                        WhiteNoiseDialogChoiceRow(
                            title = label(font),
                            selected = selected == font,
                            highlightSelected = false,
                            onClick = { if (selected != font) onSelect(font) },
                            modifier = Modifier.testTag("font_family.${font.name}"),
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss, modifier = Modifier.testTag("font_family.done")) {
                Text(stringResource(R.string.done))
            }
        },
        dismissButton = {
            TextButton(onClick = { onSelect(AppFont.System) }, modifier = Modifier.testTag("font_family.system")) {
                Text(stringResource(R.string.appearance_font_system))
            }
        },
    )
}

/** Inert localized sample; inherits the current theme/font/density without applying scale a second time. */
@Composable
@Suppress("FunctionNaming")
internal fun FontAppearanceSample(
    theme: BubbleTheme,
    mineArgb: Long?,
    otherArgb: Long?,
    modifier: Modifier,
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
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        PreviewBubble(
            text = stringResource(R.string.font_size_sample_received),
            presentation = other,
            mine = false,
            modifier = Modifier.align(Alignment.Start),
            time = rememberedClockTime(FONT_SAMPLE_RECEIVED_TIMESTAMP),
        )
        PreviewBubble(
            text = stringResource(R.string.font_size_sample_sent),
            presentation = mine,
            mine = true,
            modifier = Modifier.align(Alignment.End),
            quote = stringResource(R.string.font_size_sample_quote),
            time = rememberedClockTime(FONT_SAMPLE_SENT_TIMESTAMP),
        )
    }
}

private const val FONT_SAMPLE_RECEIVED_TIMESTAMP = 38_520uL
private const val FONT_SAMPLE_SENT_TIMESTAMP = 38_580uL
