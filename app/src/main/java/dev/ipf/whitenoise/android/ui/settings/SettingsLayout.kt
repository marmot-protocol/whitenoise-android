package dev.ipf.whitenoise.android.ui.settings

import androidx.annotation.DrawableRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyItemScope
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import dev.ipf.whitenoise.android.R
import dev.ipf.whitenoise.android.ui.common.AdaptiveContent
import dev.ipf.whitenoise.android.ui.common.LocalWhiteNoiseTextFieldContainerColor
import dev.ipf.whitenoise.android.ui.common.WhiteNoiseCallout
import dev.ipf.whitenoise.android.ui.common.WhiteNoiseScaffold
import dev.ipf.whitenoise.android.ui.common.WhiteNoiseTopBar
import dev.ipf.whitenoise.android.ui.theme.WhiteNoiseSpacing

/** True inside a [SettingsList], where the list owns the spacing between headings, groups and helpers. */
internal val LocalSettingsList = staticCompositionLocalOf { false }

/** Settings destination frame: low-container canvas, matching top bar, fields on the lowest surface. */
@Suppress("FunctionNaming", "LongParameterList")
@Composable
internal fun SettingsScaffold(
    title: String,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    contentWindowInsets: WindowInsets = WindowInsets.safeDrawing,
    prominentTitle: Boolean = false,
    containerColor: Color = MaterialTheme.colorScheme.surfaceContainerLow,
    topBarContainerColor: Color = MaterialTheme.colorScheme.surfaceContainerLow,
    topBarScrolledContainerColor: Color = MaterialTheme.colorScheme.surfaceContainer,
    topBarActions: @Composable RowScope.() -> Unit = {},
    bottomBar: @Composable () -> Unit = {},
    content: @Composable () -> Unit,
) {
    WhiteNoiseScaffold(
        modifier = modifier.fillMaxSize(),
        contentWindowInsets = contentWindowInsets,
        topBar = {
            WhiteNoiseTopBar(
                title = title,
                onBack = onBack,
                titleStyle =
                    if (prominentTitle) {
                        MaterialTheme.typography.headlineMedium
                    } else {
                        MaterialTheme.typography.titleLarge
                    },
                containerColor = topBarContainerColor,
                scrolledContainerColor = topBarScrolledContainerColor,
                actions = topBarActions,
            )
        },
        bottomBar = bottomBar,
        containerColor = containerColor,
    ) { padding ->
        CompositionLocalProvider(
            LocalWhiteNoiseTextFieldContainerColor provides MaterialTheme.colorScheme.surfaceContainerLowest,
        ) {
            AdaptiveContent(modifier = Modifier.fillMaxSize().padding(padding)) { content() }
        }
    }
}

/** Lazy settings column with 8 dp top and peer spacing, 24 dp bottom clearance, 8 dp between roots in one item. */
@Suppress("FunctionNaming")
@Composable
internal fun SettingsList(
    state: LazyListState = rememberLazyListState(),
    content: LazyListScope.() -> Unit,
) {
    CompositionLocalProvider(LocalSettingsList provides true) {
        LazyColumn(
            modifier = Modifier.fillMaxSize().testTag("settings.list"),
            state = state,
            contentPadding = PaddingValues(top = WhiteNoiseSpacing.Related, bottom = WhiteNoiseSpacing.Section),
            verticalArrangement = Arrangement.spacedBy(WhiteNoiseSpacing.Related),
            content = {
                val list = this
                val scope =
                    object : LazyListScope by list {
                        override fun item(
                            key: Any?,
                            contentType: Any?,
                            content: @Composable LazyItemScope.() -> Unit,
                        ) {
                            list.item(key, contentType) {
                                Column(verticalArrangement = Arrangement.spacedBy(WhiteNoiseSpacing.Related)) {
                                    content()
                                }
                            }
                        }
                    }
                scope.content()
            },
        )
    }
}

/** Section label above a settings group at the 32 dp heading line, announced as a heading. */
@Suppress("FunctionNaming")
@Composable
internal fun SettingsSection(title: String) {
    Text(
        text = title,
        modifier =
            Modifier
                .fillMaxWidth()
                .semantics { heading() }
                .padding(
                    start = WhiteNoiseSpacing.SettingsSectionInset,
                    end = WhiteNoiseSpacing.SettingsSectionInset,
                    top = if (LocalSettingsList.current) WhiteNoiseSpacing.FormField else WhiteNoiseSpacing.Section,
                    bottom = if (LocalSettingsList.current) 0.dp else WhiteNoiseSpacing.Related,
                ),
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        style = MaterialTheme.typography.labelLarge,
    )
}

/** Helper copy beneath a group at the same 32 dp content line, in the quiet supporting style. */
@Suppress("FunctionNaming")
@Composable
internal fun SettingsExplainer(text: String) {
    Column(
        modifier =
            Modifier
                .fillMaxWidth()
                .padding(
                    start = WhiteNoiseSpacing.SettingsSectionInset,
                    top = if (LocalSettingsList.current) 0.dp else WhiteNoiseSpacing.Related,
                    end = WhiteNoiseSpacing.SettingsSectionInset,
                ),
    ) {
        Text(text, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
    }
}

/** Centered version line that closes the Settings home; the version name is the only build detail shown here. */
@Suppress("FunctionNaming")
@Composable
internal fun SettingsVersionFooter(versionName: String) {
    Box(
        modifier =
            Modifier
                .fillMaxWidth()
                .padding(WhiteNoiseSpacing.CompactScreenMargin)
                .testTag("settings.version_footer"),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = stringResource(R.string.settings_version_label, versionName),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.bodySmall,
            textAlign = TextAlign.Center,
        )
    }
}

/** A callout at the screen margin inside a settings list; error callouts use the error container. */
@Suppress("FunctionNaming", "LongParameterList")
@Composable
internal fun SettingsCallout(
    text: String,
    modifier: Modifier = Modifier,
    title: String? = null,
    isError: Boolean = false,
    leading: (@Composable () -> Unit)? = null,
    @DrawableRes icon: Int = if (isError) R.drawable.ic_error else R.drawable.ic_info,
) {
    WhiteNoiseCallout(
        modifier = modifier.fillMaxWidth().padding(horizontal = WhiteNoiseSpacing.CompactScreenMargin),
        text = text,
        title = title,
        icon = icon,
        isError = isError,
        leading = leading,
    )
}
