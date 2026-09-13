package dev.ipf.whitenoise.android.ui.conversation.messages

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.core.os.ConfigurationCompat
import dev.ipf.whitenoise.android.R
import dev.ipf.whitenoise.android.core.EditState
import dev.ipf.whitenoise.android.ui.common.AdaptiveContent
import dev.ipf.whitenoise.android.ui.design.KeyboardPreservingBottomSheet
import dev.ipf.whitenoise.android.ui.theme.amoledOutlineBorder
import java.time.DateTimeException
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale

/** A native published revision or original; chronological source data stays untouched. */
private data class EditHistoryRow(
    val versionNumber: Int,
    val text: String,
    val recordedAt: ULong,
)

/** Full-height prototype history presentation keeps the native keyboard-preserving dismissal/focus owner. */
@OptIn(ExperimentalMaterial3Api::class)
@Suppress("FunctionNaming")
@Composable
internal fun EditHistorySheet(
    original: String,
    originalTimestamp: ULong,
    editState: EditState,
    onDismissRequest: () -> Unit,
) {
    val rows =
        remember(original, originalTimestamp, editState) {
            editState.versions
                .mapIndexed { index, version ->
                    EditHistoryRow(index + 1, version.text, version.recordedAt)
                }.reversed() + EditHistoryRow(0, original, originalTimestamp)
        }
    KeyboardPreservingBottomSheet(
        paneTitle = stringResource(R.string.edit_history),
        onDismissRequest = onDismissRequest,
        modifier = Modifier.fillMaxSize(),
    ) {
        Scaffold(
            modifier = Modifier.fillMaxSize().testTag("message.history"),
            topBar = {
                TopAppBar(
                    title = { Text(stringResource(R.string.edit_history)) },
                    navigationIcon = {
                        IconButton(onClick = onDismissRequest) {
                            Icon(painterResource(R.drawable.ic_arrow_back), stringResource(R.string.back))
                        }
                    },
                )
            },
        ) { padding ->
            AdaptiveContent(Modifier.fillMaxSize().padding(padding)) {
                Column(
                    modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    rows.forEach { row -> EditHistoryVersionRow(row) }
                }
            }
        }
    }
}

/** Selectable revision cards use exact localized timestamps and the prototype's simple stacked hierarchy. */
@Suppress("FunctionNaming")
@Composable
private fun EditHistoryVersionRow(row: EditHistoryRow) {
    val locale = ConfigurationCompat.getLocales(LocalConfiguration.current)[0] ?: Locale.ROOT
    val zone = ZoneId.systemDefault()
    val time = remember(row.recordedAt, locale, zone) { editHistoryRevisionTime(row.recordedAt, locale, zone) }
    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Text(
            text =
                if (row.versionNumber == 0) {
                    stringResource(R.string.edit_history_original)
                } else {
                    stringResource(R.string.conversation_edit_revision, row.versionNumber)
                },
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.semantics { heading() }.testTag("message.history.version.${row.versionNumber}"),
        )
        Text(time, style = MaterialTheme.typography.labelMedium)
        Surface(
            border = amoledOutlineBorder(),
            shape = MaterialTheme.shapes.large,
            color = MaterialTheme.colorScheme.surfaceContainerLow,
        ) {
            SelectionContainer {
                Text(
                    row.text,
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .padding(16.dp)
                            .testTag("message.history.body.${row.versionNumber}"),
                )
            }
        }
    }
}

/** Converts native epoch seconds without replacing real revision dates with relative or synthetic timestamps. */
internal fun editHistoryRevisionTime(
    seconds: ULong,
    locale: Locale,
    zone: ZoneId,
): String {
    if (seconds > Instant.MAX.epochSecond.toULong()) return ""
    return try {
        DateTimeFormatter
            .ofLocalizedDateTime(FormatStyle.MEDIUM)
            .withLocale(locale)
            .withZone(zone)
            .format(Instant.ofEpochSecond(seconds.toLong())) + " (${zone.id})"
    } catch (_: DateTimeException) {
        // A native timestamp outside the calendar/zone range has no truthful localized representation.
        ""
    }
}
