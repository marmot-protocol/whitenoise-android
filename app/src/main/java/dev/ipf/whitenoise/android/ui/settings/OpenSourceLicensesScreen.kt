package dev.ipf.whitenoise.android.ui.settings

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import dev.ipf.whitenoise.android.R
import dev.ipf.whitenoise.android.ui.common.WhiteNoiseLazyColumn
import dev.ipf.whitenoise.android.ui.common.fadingVerticalScroll
import dev.ipf.whitenoise.android.state.runCatchingCancellable
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Local, selectable notices retain the existing generated attribution without an external Activity. */
@Suppress("FunctionNaming")
@Composable
internal fun OpenSourceLicensesScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    var retry by rememberSaveable { mutableIntStateOf(0) }
    val notices by produceState<Result<List<OpenSourceNotice>>?>(null, retry, context.resources) {
        value = withContext(Dispatchers.IO) {
            runCatchingCancellable { readOpenSourceNotices(context.resources, context.packageName) }
        }
    }
    var selectedId by rememberSaveable { mutableStateOf<String?>(null) }
    val selected = notices?.getOrNull()?.firstOrNull { it.id == selectedId }
    val back = { if (selectedId != null) selectedId = null else onBack() }
    BackHandler(onBack = back)
    SettingsScaffold(title = selected?.name ?: stringResource(R.string.open_source_licenses), onBack = back) {
        OpenSourceLicensesContent(notices, selected, { selectedId = it.id }, { retry++ })
    }
}

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
        notices.isFailure -> Column(modifier = Modifier.padding(24.dp).testTag("licenses.failed")) {
            Text(stringResource(R.string.licenses_open_failed_title))
            TextButton(onClick = onRetry) { Text(stringResource(R.string.retry)) }
        }
        selected != null -> SelectionContainer {
            Text(
                text = selected.text,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.fillMaxSize().fadingVerticalScroll(rememberScrollState())
                    .padding(24.dp).testTag("licenses.text"),
            )
        }
        else -> WhiteNoiseLazyColumn(modifier = Modifier.fillMaxSize().testTag("licenses.list")) {
            items(notices.getOrThrow(), key = OpenSourceNotice::id) { notice ->
                Text(
                    text = notice.name,
                    style = MaterialTheme.typography.bodyLarge,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable(role = Role.Button) { onSelect(notice) }
                        .padding(24.dp),
                )
            }
        }
    }
}
