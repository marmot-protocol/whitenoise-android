package dev.ipf.whitenoise.android.ui.settings

import android.content.res.AssetManager
import androidx.activity.compose.BackHandler
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
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import dev.ipf.marmotkit.MarkdownDocumentFfi
import dev.ipf.whitenoise.android.R
import dev.ipf.whitenoise.android.state.WhiteNoiseAppState
import dev.ipf.whitenoise.android.state.parseMarkdownOrEmpty
import dev.ipf.whitenoise.android.ui.MarkdownMessageBody
import dev.ipf.whitenoise.android.ui.common.WhiteNoiseOutlinedButton
import dev.ipf.whitenoise.android.ui.common.fadingVerticalScroll
import dev.ipf.whitenoise.android.ui.common.rememberSearchCollapse
import dev.ipf.whitenoise.android.ui.common.searchCollapseHost
import dev.ipf.whitenoise.android.ui.navigation.SettingsDetail
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.IOException

@Suppress("FunctionNaming")
@Composable
internal fun UserHelpRoute(
    detail: SettingsDetail,
    appState: WhiteNoiseAppState,
    onBack: () -> Unit,
    onDetailChange: (SettingsDetail) -> Unit,
    guideId: String?,
) {
    when (detail) {
        SettingsDetail.LearnWhiteNoise ->
            LearnWhiteNoiseScreen(onBack, { onDetailChange(SettingsDetail.Documentation) })
        SettingsDetail.Documentation ->
            DocumentationScreen(appState, onBack, { onDetailChange(SettingsDetail.ShareConnect) }, guideId)
        SettingsDetail.Help ->
            HelpScreen(
                onBack = onBack,
                onOpenBugReport = { onDetailChange(SettingsDetail.BugReport) },
                onOpenAbout = { onDetailChange(SettingsDetail.About) },
            )
        else -> error("Unexpected help destination: $detail")
    }
}

private const val GUIDE_DIRECTORY = "user-guides"
private const val DOCS_URL = "https://www.whitenoise.chat/docs/"

internal data class UserGuide(
    val id: String,
    val title: String,
    val body: String,
)

private fun AssetManager.readUserGuides(): List<UserGuide> =
    list(GUIDE_DIRECTORY)
        .orEmpty()
        .filter { it.endsWith(".md") }
        .map { file ->
            val contents = open("$GUIDE_DIRECTORY/$file").bufferedReader().use { it.readText() }
            UserGuide(
                id = file.removeSuffix(".md"),
                title = contents.lineSequence().first().removePrefix("# "),
                body = contents.substringAfter('\n'),
            )
        }.sortedBy { it.title }

internal fun UserGuide.matches(query: String): Boolean =
    query.trim().takeIf(String::isNotEmpty)?.let { title.contains(it, true) || body.contains(it, true) } == true

@Composable
internal fun rememberUserGuides(): List<UserGuide>? {
    val assets = LocalContext.current.assets
    var guides by remember(assets) { mutableStateOf<List<UserGuide>?>(null) }
    LaunchedEffect(assets) {
        guides =
            withContext(Dispatchers.IO) {
                try {
                    assets.readUserGuides()
                } catch (_: IOException) {
                    emptyList()
                }
            }
    }
    return guides
}

@Suppress("FunctionNaming")
@Composable
internal fun UserGuideButton(
    guide: UserGuide,
    onClick: () -> Unit,
) {
    WhiteNoiseOutlinedButton(onClick, Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
        Text(guide.title)
    }
}

@Suppress("FunctionNaming", "LongMethod", "CyclomaticComplexMethod")
@Composable
internal fun DocumentationScreen(
    appState: WhiteNoiseAppState,
    onBack: () -> Unit,
    onOpenShare: () -> Unit,
    guideId: String? = null,
) {
    val guides = rememberUserGuides()
    var query by rememberSaveable { mutableStateOf("") }
    var searchOpen by rememberSaveable { mutableStateOf(false) }
    var selectedId by rememberSaveable(guideId) { mutableStateOf(guideId) }
    val selected = guides?.firstOrNull { it.id == selectedId }
    val showingSearch = searchOpen && selectedId == null
    val searchCollapse = rememberSearchCollapse(showingSearch)
    val back: () -> Unit = {
        when {
            guideId != null -> onBack()
            selectedId != null -> selectedId = null
            searchOpen -> {
                searchOpen = false
                query = ""
            }
            else -> onBack()
        }
    }
    BackHandler(enabled = showingSearch || selectedId != null, onBack = back)
    val searchLabel = R.string.search_documentation
    SettingsScaffold(
        title = selected?.title ?: stringResource(R.string.user_documentation),
        onBack = back,
        titleContent = settingsSearchTitle(showingSearch, query, { query = it.take(SETTINGS_SEARCH_MAX_QUERY) }, searchCollapse, searchLabel),
        topBarModifier = Modifier.searchCollapseHost(searchCollapse),
        topBarActions = {
            if (selectedId == null) {
                SettingsSearchAction(showingSearch, searchLabel, "documentation.search.open") {
                    searchOpen = true
                }
            }
        },
    ) {
        if (selected != null) {
            GuideArticle(appState, selected, onOpenShare) { url ->
                val linkedId =
                    url
                        .takeIf { it.startsWith(DOCS_URL) }
                        ?.removePrefix(DOCS_URL)
                        ?.substringBefore('#')
                        ?.trimEnd('/')
                        ?.replace('/', '-')
                val target = guides?.firstOrNull { it.id == linkedId }
                if (target != null) selectedId = target.id
                target != null
            }
        } else {
            val results = guides?.let { loaded -> if (query.isBlank()) loaded else loaded.filter { it.matches(query) } }
            SettingsList {
                if (results == null) {
                    item { LinearProgressIndicator(Modifier.fillMaxWidth()) }
                } else if (results.isEmpty()) {
                    item {
                        Text(
                            text =
                                stringResource(
                                    if (guides.isNullOrEmpty()) {
                                        R.string.documentation_unavailable
                                    } else {
                                        R.string.no_results
                                    },
                                ),
                            modifier = Modifier.padding(16.dp),
                        )
                    }
                } else {
                    items(results.size, key = { results[it].id }) { index ->
                        val guide = results[index]
                        UserGuideButton(guide) { selectedId = guide.id }
                    }
                }
            }
        }
    }
}

@Suppress("FunctionNaming")
@Composable
private fun GuideArticle(
    appState: WhiteNoiseAppState,
    guide: UserGuide,
    onOpenShare: () -> Unit,
    onOpenLink: (String) -> Boolean,
) {
    var document by remember(guide) { mutableStateOf<MarkdownDocumentFfi?>(null) }
    LaunchedEffect(appState, guide) {
        document = appState.parseMarkdownOrEmpty(guide.body.replace("](/docs/", "]($DOCS_URL"))
    }
    key(guide.id) {
        Column(
            modifier = Modifier.fillMaxWidth().fadingVerticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (guide.id == "using-profiles-android") {
                TextButton(onClick = onOpenShare) { Text(stringResource(R.string.help_open_share_connect)) }
            }
            val parsed = document
            when {
                parsed == null -> LinearProgressIndicator(Modifier.fillMaxWidth())
                parsed.blocks.isEmpty() -> Text(guide.body)
                else -> MarkdownMessageBody(parsed, useDecorativeBackgrounds = false, onWebLinkActivated = onOpenLink)
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class, ExperimentalLayoutApi::class)
@Suppress("FunctionNaming")
@Composable
internal fun LearnWhiteNoiseScreen(
    onBack: () -> Unit,
    onOpenDocumentation: () -> Unit,
) {
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
            TextButton(onClick = onOpenDocumentation) { Text(stringResource(R.string.learn_view_topics)) }
        }
    }
}
