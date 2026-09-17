package dev.ipf.whitenoise.android.ui.conversation.messages

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.os.ConfigurationCompat
import dev.ipf.whitenoise.android.R
import dev.ipf.whitenoise.android.core.EditState
import dev.ipf.whitenoise.android.core.EditVersion
import dev.ipf.whitenoise.android.ui.common.AdaptiveContent
import dev.ipf.whitenoise.android.ui.theme.WhiteNoiseSpacing
import dev.ipf.whitenoise.android.ui.theme.amoledOutlineBorder
import java.time.DateTimeException
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale

private data class EditHistoryRow(
    val versionNumber: Int,
    val text: String,
    val recordedAt: ULong,
)

/** The prototype's edit history: a full-screen dialog listing the newest revision first, the original last. */
@OptIn(ExperimentalMaterial3Api::class)
@Suppress("FunctionNaming")
@Composable
internal fun EditHistoryDialog(
    original: String,
    originalTimestamp: ULong,
    editState: EditState,
    onDismissRequest: () -> Unit,
    // MarmotKit 0.10.1 owns accepted edits, so its history is complete where the loaded window's is not.
    // Null means the engine could not answer and the window's own edits stand in.
    loadAuthoritativeHistory: (suspend () -> List<EditVersion>?)? = null,
) {
    var authoritative by remember(editState) { mutableStateOf<List<EditVersion>?>(null) }
    LaunchedEffect(editState, loadAuthoritativeHistory) {
        authoritative = loadAuthoritativeHistory?.invoke()
    }
    val versions = authoritative ?: editState.versions
    val rows =
        remember(original, originalTimestamp, versions) {
            versions
                .mapIndexed { index, version ->
                    EditHistoryRow(index + 1, version.text, version.recordedAt)
                }.reversed() + EditHistoryRow(0, original, originalTimestamp)
        }
    Dialog(
        onDismissRequest = onDismissRequest,
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false),
    ) {
        Scaffold(
            modifier = Modifier.fillMaxSize().testTag("message.history"),
            contentWindowInsets = WindowInsets.safeDrawing,
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
                    modifier =
                        Modifier
                            .fillMaxSize()
                            .verticalScroll(rememberScrollState())
                            .padding(WhiteNoiseSpacing.CompactScreenMargin),
                    verticalArrangement = Arrangement.spacedBy(WhiteNoiseSpacing.FormField),
                ) {
                    rows.forEach { row -> EditHistoryVersionRow(row) }
                }
            }
        }
    }
}

/** One revision: its label, exact time and selectable text. */
@Suppress("FunctionNaming")
@Composable
private fun EditHistoryVersionRow(row: EditHistoryRow) {
    val locale = ConfigurationCompat.getLocales(LocalConfiguration.current)[0] ?: Locale.ROOT
    val zone = ZoneId.systemDefault()
    val time = remember(row.recordedAt, locale, zone) { editHistoryRevisionTime(row.recordedAt, locale, zone) }
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
                        .padding(WhiteNoiseSpacing.CompactScreenMargin)
                        .testTag("message.history.body.${row.versionNumber}"),
            )
        }
    }
}

/** Localized medium date-time with the zone id, the prototype's exact-time format. */
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
        ""
    }
}
