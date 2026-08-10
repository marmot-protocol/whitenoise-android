package dev.ipf.whitenoise.android.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.error
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import dev.ipf.whitenoise.android.R
import dev.ipf.whitenoise.android.state.TtsRatePreferences
import java.math.RoundingMode
import java.text.NumberFormat
import java.text.ParsePosition
import java.util.Locale

internal fun parseTtsRateInput(
    input: String,
    locale: Locale,
): Float? {
    val candidate = input.trim()
    if (candidate.isEmpty()) return null

    val position = ParsePosition(0)
    val value = NumberFormat.getNumberInstance(locale).parse(candidate, position)?.toDouble()
    val minimum = TtsRatePreferences.MIN_RATE.toString().toDouble()
    val maximum = TtsRatePreferences.MAX_RATE.toString().toDouble()
    return value
        ?.takeIf { parsed ->
            position.index == candidate.length && parsed.isFinite() && parsed in minimum..maximum
        }?.toBigDecimal()
        ?.setScale(CUSTOM_RATE_FRACTION_DIGITS, RoundingMode.HALF_UP)
        ?.toFloat()
}

internal fun ttsRateInputValue(
    rate: Float,
    locale: Locale,
): String =
    NumberFormat
        .getNumberInstance(locale)
        .apply {
            isGroupingUsed = false
            minimumFractionDigits = CUSTOM_RATE_FRACTION_DIGITS
            maximumFractionDigits = CUSTOM_RATE_FRACTION_DIGITS
            roundingMode = RoundingMode.HALF_UP
        }.format(rate)

internal fun isTtsCustomRate(rateOverride: Float?): Boolean = rateOverride != null && isNotTtsPresetRate(rateOverride)

private fun isNotTtsPresetRate(rate: Float): Boolean = TtsRatePreferences.PRESET_RATES.none { it == rate }

@Suppress("FunctionNaming")
@Composable
internal fun TtsCustomRateDialog(
    initialRate: Float,
    onDismiss: () -> Unit,
    onRateSelected: (Float) -> Unit,
) {
    Dialog(onDismissRequest = onDismiss) {
        Surface(
            shape = MaterialTheme.shapes.extraLarge,
            tonalElevation = 6.dp,
        ) {
            TtsCustomRateEditor(
                initialRate = initialRate,
                onDismiss = onDismiss,
                onRateSelected = onRateSelected,
            )
        }
    }
}

@Suppress("FunctionNaming")
@Composable
internal fun TtsCustomRateEditor(
    initialRate: Float,
    onDismiss: () -> Unit,
    onRateSelected: (Float) -> Unit,
    modifier: Modifier = Modifier,
) {
    val locale = LocalConfiguration.current.locales[0]
    var input by remember(initialRate, locale) { mutableStateOf(ttsRateInputValue(initialRate, locale)) }
    val parsedRate = parseTtsRateInput(input, locale)
    val invalid = input.isNotBlank() && parsedRate == null
    val errorMessage = stringResource(R.string.tts_rate_custom_error)
    val applyRate: () -> Unit = {
        parsedRate?.let(onRateSelected)
    }

    Column(
        modifier = modifier.widthIn(min = 280.dp, max = 360.dp).padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text(
            text = stringResource(R.string.tts_rate_custom),
            style = MaterialTheme.typography.headlineSmall,
        )
        OutlinedTextField(
            value = input,
            onValueChange = { input = it },
            modifier =
                Modifier
                    .fillMaxWidth()
                    .semantics {
                        if (invalid) error(errorMessage)
                    },
            label = { Text(stringResource(R.string.tts_settings_rate_title)) },
            singleLine = true,
            isError = invalid,
            supportingText = {
                if (invalid) Text(errorMessage)
            },
            keyboardOptions =
                KeyboardOptions(
                    keyboardType = KeyboardType.Decimal,
                    imeAction = ImeAction.Done,
                ),
            keyboardActions = KeyboardActions(onDone = { applyRate() }),
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp, alignment = androidx.compose.ui.Alignment.End),
        ) {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.cancel))
            }
            TextButton(
                onClick = applyRate,
                enabled = parsedRate != null,
            ) {
                Text(stringResource(R.string.tts_rate_apply))
            }
        }
    }
}

private const val CUSTOM_RATE_FRACTION_DIGITS = 1
