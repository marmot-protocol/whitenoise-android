package dev.ipf.whitenoise.android.ui.settings

import androidx.activity.compose.BackHandler
import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import dev.ipf.whitenoise.android.R
import dev.ipf.whitenoise.android.core.WhiteNoiseUrls
import dev.ipf.whitenoise.android.ui.common.WhiteNoiseButton
import dev.ipf.whitenoise.android.ui.common.fadingVerticalScroll
import kotlinx.coroutines.launch

private enum class LearnTopic(
    @StringRes val title: Int,
    @StringRes val body: Int,
    @DrawableRes val artwork: Int,
    @StringRes val continueLabel: Int,
) {
    Invite(R.string.learn_share_title, R.string.learn_share_body, R.drawable.learn_share, R.string.learn_find_title),
    Connect(R.string.learn_find_title, R.string.learn_find_body, R.drawable.learn_connect, R.string.learn_group_title),
    Groups(
        R.string.learn_group_title,
        R.string.learn_group_body,
        R.drawable.learn_groups,
        R.string.learn_actions_title,
    ),
    Actions(
        R.string.learn_actions_title,
        R.string.learn_actions_body,
        R.drawable.learn_actions,
        R.string.learn_marmot_title,
    ),
    Interop(R.string.learn_marmot_title, R.string.learn_marmot_body, R.drawable.learn_marmot, R.string.done),
}

@Suppress("FunctionNaming")
@Composable
internal fun LearnWhiteNoiseScreen(
    onBack: () -> Unit,
    onShareProfile: () -> Unit,
) {
    val pager = rememberPagerState { LearnTopic.entries.size }
    val scope = rememberCoroutineScope()
    val goBack: () -> Unit = {
        if (pager.currentPage == 0) onBack() else scope.launch { pager.animateScrollToPage(pager.currentPage - 1) }
    }
    BackHandler(enabled = pager.currentPage > 0, onBack = goBack)
    SettingsScaffold(title = stringResource(R.string.learn_white_noise), onBack = goBack) {
        HorizontalPager(
            state = pager,
            modifier = Modifier.fillMaxSize(),
            verticalAlignment = Alignment.Top,
            userScrollEnabled = pager.currentPage != LearnTopic.Actions.ordinal,
        ) { index ->
            val topic = LearnTopic.entries[index]
            LearnTopicPage(
                topic = topic,
                onShareProfile = onShareProfile,
                onContinue = {
                    if (index == LearnTopic.entries.lastIndex) {
                        onBack()
                    } else {
                        scope.launch { pager.animateScrollToPage(index + 1) }
                    }
                },
            )
        }
    }
}

private val LightBackgroundArtwork =
    ColorFilter.colorMatrix(ColorMatrix().apply { setToScale(0.52f, 0.52f, 0.52f, 1f) })

@Suppress("FunctionNaming")
@Composable
private fun LearnTopicPage(
    topic: LearnTopic,
    onShareProfile: () -> Unit,
    onContinue: () -> Unit,
) {
    val lightBackground = MaterialTheme.colorScheme.surfaceContainerLow.luminance() > 0.5f
    Column(
        Modifier
            .fillMaxWidth()
            .fadingVerticalScroll(rememberScrollState())
            .padding(horizontal = 24.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Image(
            painter = painterResource(topic.artwork),
            contentDescription = null,
            modifier = Modifier.fillMaxWidth().heightIn(max = 220.dp),
            colorFilter = if (lightBackground) LightBackgroundArtwork else null,
        )
        Text(
            stringResource(topic.title),
            modifier = Modifier.semantics { heading() },
            style = MaterialTheme.typography.headlineMedium,
        )
        Text(
            stringResource(topic.body),
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (topic == LearnTopic.Actions) LearnMessageDemo()
        if (topic == LearnTopic.Interop) LearnMarmotLink()
        Row(
            Modifier.align(Alignment.CenterHorizontally).clearAndSetSemantics { },
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            LearnTopic.entries.forEach { item ->
                val color = if (item == topic) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline
                val width = if (item == topic) 16.dp else 6.dp
                Box(Modifier.size(width = width, height = 6.dp).background(color, CircleShape))
            }
        }
        if (topic == LearnTopic.Invite) {
            WhiteNoiseButton(onShareProfile, Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.learn_open_share))
            }
            OutlinedButton(onContinue, Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                Text(stringResource(topic.continueLabel))
            }
        } else {
            WhiteNoiseButton(onContinue, Modifier.fillMaxWidth()) {
                Text(stringResource(topic.continueLabel))
            }
        }
    }
}

@Suppress("FunctionNaming")
@Composable
private fun LearnMarmotLink() {
    val uriHandler = LocalUriHandler.current
    var openFailed by rememberSaveable { mutableStateOf(false) }
    OutlinedButton(
        onClick = {
            openFailed = runCatching { uriHandler.openUri(WhiteNoiseUrls.MARMOT_APPS) }.isFailure
        },
        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
    ) {
        Text(stringResource(R.string.learn_marmot_link))
    }
    if (openFailed) Text(stringResource(R.string.learn_marmot_link_failed), color = MaterialTheme.colorScheme.error)
}
