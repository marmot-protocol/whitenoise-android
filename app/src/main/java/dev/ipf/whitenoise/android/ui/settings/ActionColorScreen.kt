package dev.ipf.whitenoise.android.ui.settings

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import dev.ipf.whitenoise.android.R
import dev.ipf.whitenoise.android.state.BubbleTheme
import dev.ipf.whitenoise.android.state.WhiteNoiseAppState
import dev.ipf.whitenoise.android.ui.common.SettingsGroup
import dev.ipf.whitenoise.android.ui.common.UnreadCountBadge
import dev.ipf.whitenoise.android.ui.common.accountActionColors

@OptIn(ExperimentalMaterial3Api::class)
@Composable
@Suppress("FunctionNaming")
internal fun ActionColorScreen(
    appState: WhiteNoiseAppState,
    onBack: () -> Unit,
) {
    val theme = BubbleTheme.resolve(appState.themeMode, isSystemInDarkTheme())
    val accountRef = appState.activeAccountRef?.takeIf(String::isNotBlank)
    val selectedArgb = appState.actionColorArgb(theme)
    val colors = accountActionColors(appState)

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.action_color)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.back))
                    }
                },
            )
        },
    ) { padding ->
        ActionColorContent(
            accountRef = accountRef,
            selectedArgb = selectedArgb,
            colors = colors,
            theme = theme,
            onColorSelected = { appState.updateActionColor(theme, it) },
            modifier = Modifier.fillMaxSize().padding(padding),
        )
    }
}

@Composable
@Suppress("FunctionNaming")
private fun ActionColorContent(
    accountRef: String?,
    selectedArgb: Long?,
    colors: dev.ipf.whitenoise.android.ui.common.AccountActionColors,
    theme: BubbleTheme,
    onColorSelected: (Long?) -> Unit,
    modifier: Modifier = Modifier,
) {
    LazyColumn(
        modifier = modifier.padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        item {
            Text(
                stringResource(
                    if (accountRef == null) {
                        R.string.action_color_no_active_account
                    } else {
                        R.string.action_color_subtitle
                    },
                ),
            )
        }
        if (accountRef == null) return@LazyColumn
        item {
            SettingsGroup(title = stringResource(R.string.bubble_color_preview)) {
                item {
                    ActionColorPreview(colors, Modifier.fillMaxWidth().padding(16.dp))
                }
            }
        }
        item {
            SettingsGroup(title = stringResource(R.string.action_color)) {
                item {
                    Column(Modifier.padding(16.dp)) {
                        TonalSwatchPicker(
                            selectedArgb = selectedArgb,
                            onColorSelected = onColorSelected,
                            scopeKey = "action:$accountRef",
                            theme = theme,
                            slotKey = "action",
                            swatchContentDescriptionRes = R.string.action_color_swatch_content_description,
                        )
                    }
                }
            }
        }
        item {
            TextButton(
                onClick = { onColorSelected(null) },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(stringResource(R.string.reset_to_default))
            }
        }
    }
}

@Composable
@Suppress("FunctionNaming")
internal fun ActionColorPreview(
    colors: dev.ipf.whitenoise.android.ui.common.AccountActionColors,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        FloatingActionButton(
            onClick = {},
            modifier = Modifier.size(48.dp),
            containerColor = colors.container,
            contentColor = colors.content,
        ) {
            Icon(Icons.Default.Edit, contentDescription = null)
        }
        FloatingActionButton(
            onClick = {},
            modifier = Modifier.size(48.dp),
            containerColor = colors.container,
            contentColor = colors.content,
        ) {
            Icon(Icons.AutoMirrored.Filled.Send, contentDescription = null)
        }
        UnreadCountBadge(unreadCount = 3u, actionColors = colors)
    }
}
