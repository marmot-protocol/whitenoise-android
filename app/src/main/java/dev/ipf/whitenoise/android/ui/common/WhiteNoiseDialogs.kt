package dev.ipf.whitenoise.android.ui.common

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredWidth
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import dev.ipf.whitenoise.android.R
import dev.ipf.whitenoise.android.ui.theme.amoledOutlineBorder
import dev.ipf.whitenoise.android.ui.theme.isAmoledSurfaceTheme

private object ChoiceDialogDefaults {
    /** AlertDialog owns a 24 dp content inset; rows extend 16 dp into it and keep an 8 dp gutter. */
    val RowOverhang = 16.dp

    /** Material's minimum two-line list row height keeps every choice a comfortable target. */
    val RowMinHeight = 56.dp

    /** Vertical breathing room around a choice's text inside the 56 dp minimum. */
    val RowTextPadding = 8.dp

    /** Supporting copy sits one form-field gap beneath the choices. */
    val SupportingTopSpacing = 16.dp

    /**
     * Material's BasicAlertDialog only bounds the surface to 280–560 dp, so filling content would reach
     * both screen edges on a 360 dp phone. A 312 dp surface (264 dp of content inside the 24 dp dialog
     * inset) keeps the customary 24 dp side margins.
     */
    val ContentMaxWidth = 264.dp

    /** AMOLED has no tonal surfaces, so the selected row uses a faint content-colour wash instead. */
    const val AmoledSelectionAlpha = 0.16f
}

/**
 * Dialog of radio choices. Selecting a value applies it through [onSelect] and the caller
 * closes the dialog; Cancel only dismisses. [label] and [labelFontFamily] receive resolved values so
 * callers precompute localized strings and preview typefaces in composition.
 */
@Suppress("FunctionNaming", "LongParameterList")
@Composable
internal fun <T> ChoiceDialog(
    title: String,
    values: List<T>,
    selected: T?,
    label: (T) -> String,
    onDismiss: () -> Unit,
    onSelect: (T) -> Unit,
    modifier: Modifier = Modifier,
    labelFontFamily: (T) -> FontFamily? = { null },
    supportingText: String? = null,
    subtitle: (T) -> String? = { null },
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        modifier = modifier,
        title = { Text(title) },
        text = {
            Column(
                Modifier
                    .widthIn(max = ChoiceDialogDefaults.ContentMaxWidth)
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
            ) {
                Column(Modifier.fillMaxWidth().selectableGroup()) {
                    values.forEach { value ->
                        WhiteNoiseDialogChoiceRow(
                            title = label(value),
                            selected = value == selected,
                            onClick = { onSelect(value) },
                            fontFamily = labelFontFamily(value),
                            subtitle = subtitle(value),
                        )
                    }
                }
                if (supportingText != null) {
                    Text(
                        text = supportingText,
                        modifier = Modifier.padding(top = ChoiceDialogDefaults.SupportingTopSpacing),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) }
        },
    )
}

/** Dialog-owned insets, a rounded selected fill, and one radio accessibility target per row. */
@Suppress("FunctionNaming")
@Composable
internal fun WhiteNoiseDialogChoiceRow(
    title: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    fontFamily: FontFamily? = null,
    subtitle: String? = null,
    enabled: Boolean = true,
    accessibilityLabel: String? = null,
) {
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        Row(
            modifier =
                Modifier
                    .requiredWidth(maxWidth + ChoiceDialogDefaults.RowOverhang * 2)
                    .then(modifier)
                    .heightIn(min = ChoiceDialogDefaults.RowMinHeight)
                    .whiteNoiseDialogSelection(selected && enabled)
                    .selectable(selected = selected, enabled = enabled, role = Role.RadioButton, onClick = onClick)
                    .then(
                        if (accessibilityLabel == null) {
                            Modifier
                        } else {
                            Modifier.semantics(mergeDescendants = true) { contentDescription = accessibilityLabel }
                        },
                    ).padding(horizontal = ChoiceDialogDefaults.RowOverhang),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            RadioButton(
                selected = selected,
                onClick = null,
                enabled = enabled,
                modifier = Modifier.clearAndSetSemantics {},
            )
            Column(
                Modifier
                    .weight(1f)
                    .padding(start = ChoiceDialogDefaults.RowOverhang)
                    .padding(vertical = ChoiceDialogDefaults.RowTextPadding),
            ) {
                Text(
                    text = title,
                    color =
                        if (enabled) {
                            MaterialTheme.colorScheme.onSurface
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                    style = MaterialTheme.typography.bodyLarge,
                    fontFamily = fontFamily,
                )
                if (subtitle != null) {
                    Text(
                        text = subtitle,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }
        }
    }
}

/** Shared boundary for the selected fill and native input feedback in modal option rows. */
@Composable
internal fun Modifier.whiteNoiseDialogSelection(selected: Boolean): Modifier =
    clip(MaterialTheme.shapes.large).background(if (selected) dialogSelectionColor() else Color.Transparent)

/**
 * The selected choice fill. AlertDialog already sits on `surfaceContainerHigh`, so the tonal fill is one
 * step higher; the black AMOLED canvas has no tonal steps and uses a faint content wash instead.
 */
@Composable
private fun dialogSelectionColor(): Color =
    if (isAmoledSurfaceTheme()) {
        MaterialTheme.colorScheme.onSurface.copy(alpha = ChoiceDialogDefaults.AmoledSelectionAlpha)
    } else {
        MaterialTheme.colorScheme.surfaceContainerHighest
    }

/**
 * Material alert dialog on the prototype's dialog surface: low container, variant body text, an AMOLED outline, and
 * the lowest container for any text field inside.
 */
@Suppress("FunctionNaming", "LongParameterList")
@Composable
internal fun WhiteNoiseAlertDialog(
    onDismissRequest: () -> Unit,
    confirmButton: @Composable () -> Unit,
    modifier: Modifier = Modifier,
    dismissButton: (@Composable () -> Unit)? = null,
    icon: (@Composable () -> Unit)? = null,
    title: (@Composable () -> Unit)? = null,
    text: (@Composable () -> Unit)? = null,
) {
    val scheme = MaterialTheme.colorScheme
    val outline = amoledOutlineBorder()
    CompositionLocalProvider(LocalWhiteNoiseTextFieldContainerColor provides scheme.surfaceContainerLowest) {
        AlertDialog(
            onDismissRequest = onDismissRequest,
            confirmButton = confirmButton,
            modifier = if (outline != null) modifier.border(outline, MaterialTheme.shapes.extraLarge) else modifier,
            dismissButton = dismissButton,
            icon = icon,
            title = title,
            text = text,
            containerColor = scheme.surfaceContainerLow,
            iconContentColor = scheme.onSurfaceVariant,
            titleContentColor = scheme.onSurface,
            textContentColor = scheme.onSurfaceVariant,
        )
    }
}

/** One option in a speech picker: its own label, selected state, availability reason and action. */
internal data class SpeechChoice(
    val title: String,
    val selected: Boolean,
    val subtitle: String? = null,
    val enabled: Boolean = true,
    val accessibilityLabel: String? = null,
    val onClick: () -> Unit,
)

/**
 * Dialog of speech options. Unlike [ChoiceDialog] each row carries its own availability and action, because an
 * engine or voice can be listed while it cannot be chosen (not installed, network-only, ambiguous identity).
 */
@Suppress("FunctionNaming")
@Composable
internal fun SpeechChoiceDialog(
    title: String,
    choices: List<SpeechChoice>,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(
                Modifier
                    .widthIn(max = ChoiceDialogDefaults.ContentMaxWidth)
                    .fillMaxWidth()
                    .heightIn(max = SpeechChoiceDialogMaxHeight)
                    .verticalScroll(rememberScrollState())
                    .selectableGroup(),
            ) {
                choices.forEachIndexed { index, choice ->
                    WhiteNoiseDialogChoiceRow(
                        title = choice.title,
                        selected = choice.selected,
                        onClick = choice.onClick,
                        modifier = Modifier.testTag("speech.choice.$index"),
                        subtitle = choice.subtitle,
                        enabled = choice.enabled,
                        accessibilityLabel = choice.accessibilityLabel,
                    )
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}

private val SpeechChoiceDialogMaxHeight = 400.dp
