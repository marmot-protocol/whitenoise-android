package dev.ipf.whitenoise.android.ui.settings

import android.content.res.Resources
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import dev.ipf.whitenoise.android.R
import dev.ipf.whitenoise.android.state.runCatchingCancellable
import dev.ipf.whitenoise.android.ui.common.WhiteNoiseLazyColumn
import dev.ipf.whitenoise.android.ui.common.WhiteNoiseListItemDefaults
import dev.ipf.whitenoise.android.ui.common.fadingVerticalScroll
import dev.ipf.whitenoise.android.ui.theme.WhiteNoiseSpacing
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Local, selectable notices retain the existing generated attribution without an external Activity. The list and one
 * notice are two Settings pages (#4095): the list passes [onSelect], and the notice page passes [selectedId]. A
 * notice that no longer exists, as after an update that dropped a library, returns to the list.
 */
@Suppress("FunctionNaming")
@Composable
internal fun OpenSourceLicensesScreen(
    onBack: () -> Unit,
    selectedId: String? = null,
    onSelect: (OpenSourceNotice) -> Unit = {},
    noticesLoader: suspend (Resources) -> List<OpenSourceNotice> = { readOpenSourceNotices(it) },
) {
    val context = LocalContext.current
    val loader by rememberUpdatedState(noticesLoader)
    var retry by rememberSaveable { mutableIntStateOf(0) }
    val notices by
        produceState<Result<List<OpenSourceNotice>>?>(null, retry, context.resources) {
            value =
                withContext(Dispatchers.IO) {
                    runCatchingCancellable { loader(context.resources) }
                }
        }
    val loaded = notices?.getOrNull()
    val selected = selectedId?.let { id -> loaded?.firstOrNull { it.id == id } }
    val currentOnBack by rememberUpdatedState(onBack)
    LaunchedEffect(selectedId, loaded) {
        if (selectedId != null && loaded != null && selected == null) currentOnBack()
    }
    val title = if (selectedId == null) stringResource(R.string.open_source_licenses) else selected?.name.orEmpty()
    SettingsScaffold(title = title, onBack = onBack) {
        OpenSourceLicensesContent(notices, selected, onSelect, { retry++ })
    }
}

/** Shows loading, a retryable failure, the selected notice's selectable text or the notice list. */
@Suppress("FunctionNaming")
@Composable
internal fun OpenSourceLicensesContent(
    notices: Result<List<OpenSourceNotice>>?,
    selected: OpenSourceNotice?,
    onSelect: (OpenSourceNotice) -> Unit,
    onRetry: () -> Unit,
) {
    when {
        notices == null -> CircularProgressIndicator(modifier = Modifier.padding(24.dp))
        notices.isFailure ->
            Column(modifier = Modifier.padding(24.dp).testTag("licenses.failed")) {
                Text(stringResource(R.string.licenses_open_failed_title))
                TextButton(onClick = onRetry) { Text(stringResource(R.string.retry)) }
            }
        selected != null ->
            SelectionContainer {
                Text(
                    text = selected.text,
                    style = MaterialTheme.typography.bodyMedium,
                    modifier =
                        Modifier
                            .fillMaxSize()
                            .fadingVerticalScroll(rememberScrollState())
                            .padding(24.dp)
                            .testTag("licenses.text"),
                )
            }
        else -> OpenSourceLicensesList(notices.getOrThrow(), onSelect)
    }
}

/** One continuous Settings-style group of navigation rows, composed lazily for the full notice set. */
@Suppress("FunctionNaming")
@Composable
private fun OpenSourceLicensesList(
    notices: List<OpenSourceNotice>,
    onSelect: (OpenSourceNotice) -> Unit,
) {
    WhiteNoiseLazyColumn(
        modifier = Modifier.fillMaxSize().testTag("licenses.list"),
        contentPadding = PaddingValues(top = WhiteNoiseSpacing.Related, bottom = WhiteNoiseSpacing.Section),
        verticalArrangement = Arrangement.spacedBy(WhiteNoiseListItemDefaults.segmentedGap),
    ) {
        itemsIndexed(notices, key = { _, notice -> notice.id }) { index, notice ->
            SettingsLink(
                settingsRowContext(index, notices.size),
                title = notice.name,
                explanation = stringResource(R.string.explanation_organize_license_notice),
                onClick = { onSelect(notice) },
                modifier = Modifier.padding(horizontal = WhiteNoiseSpacing.CompactScreenMargin),
                leading = { SettingsLeadingIcon(R.drawable.ic_license) },
            )
        }
    }
}
