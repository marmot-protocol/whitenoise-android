package dev.ipf.whitenoise.android.ui.settings

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import dev.ipf.whitenoise.android.R
import dev.ipf.whitenoise.android.ui.common.fadingVerticalScroll
import kotlinx.coroutines.launch

@OptIn(ExperimentalFoundationApi::class, ExperimentalLayoutApi::class)
@Suppress("FunctionNaming")
@Composable
internal fun LearnWhiteNoiseScreen(onBack: () -> Unit) {
    val titles = listOf(R.string.learn_find_title, R.string.learn_share_title, R.string.learn_actions_title)
    val descriptions = listOf(R.string.learn_find_body, R.string.learn_share_body, R.string.learn_actions_body)
    val pager = rememberPagerState { titles.size }
    val scope = rememberCoroutineScope()
    var actionsVisible by rememberSaveable { mutableStateOf(false) }
    SettingsScaffold(title = stringResource(R.string.learn_white_noise), onBack = onBack) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            HorizontalPager(state = pager, modifier = Modifier.weight(1f)) { page ->
                Column(
                    Modifier.fillMaxWidth().fadingVerticalScroll(rememberScrollState()).padding(8.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Text(stringResource(titles[page]), style = MaterialTheme.typography.headlineSmall)
                    Text(stringResource(descriptions[page]))
                    if (page == titles.lastIndex) {
                        Text(
                            stringResource(R.string.learn_example_message),
                            Modifier
                                .combinedClickable(
                                    onClick = { actionsVisible = true },
                                    onLongClick = { actionsVisible = true },
                                    onLongClickLabel = stringResource(R.string.learn_show_actions),
                                ).padding(12.dp),
                        )
                        TextButton(onClick = { actionsVisible = !actionsVisible }) {
                            Text(stringResource(R.string.learn_show_actions))
                        }
                        if (actionsVisible) Text(stringResource(R.string.learn_actions_explanation))
                    }
                }
            }
            Text(stringResource(R.string.learn_page_number, pager.currentPage + 1, titles.size))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(
                    enabled = pager.currentPage > 0,
                    onClick = { scope.launch { pager.animateScrollToPage(pager.currentPage - 1) } },
                ) { Text(stringResource(R.string.learn_previous)) }
                TextButton(
                    enabled = pager.currentPage < titles.lastIndex,
                    onClick = { scope.launch { pager.animateScrollToPage(pager.currentPage + 1) } },
                ) { Text(stringResource(R.string.learn_next)) }
                TextButton(onClick = onBack) { Text(stringResource(R.string.learn_close)) }
            }
        }
    }
}
