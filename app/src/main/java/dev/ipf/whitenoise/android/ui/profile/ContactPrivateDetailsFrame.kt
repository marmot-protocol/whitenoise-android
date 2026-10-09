package dev.ipf.whitenoise.android.ui.profile

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.SecureFlagPolicy
import dev.ipf.whitenoise.android.R
import dev.ipf.whitenoise.android.ui.common.fadingVerticalScroll
import dev.ipf.whitenoise.android.ui.testing.exposePerformanceTestTags
import dev.ipf.whitenoise.android.ui.theme.WhiteNoiseSpacing
import dev.ipf.whitenoise.android.ui.theme.amoledOutlineBorder

/** Owns the dialog insets once; only the form shrinks/scrolls when the IME reduces the safe viewport. */
@Composable
@Suppress("FunctionNaming")
internal fun ContactPrivateDetailsFrame(
    securePolicy: SecureFlagPolicy,
    onDismiss: () -> Unit,
    actions: @Composable () -> Unit,
    form: @Composable () -> Unit,
    viewportInsets: WindowInsets = WindowInsets.safeDrawing.union(WindowInsets.ime),
) {
    Dialog(
        onDismissRequest = onDismiss,
        properties =
            DialogProperties(
                securePolicy = securePolicy,
                usePlatformDefaultWidth = false,
                decorFitsSystemWindows = false,
            ),
    ) {
        BoxWithConstraints(
            Modifier
                .fillMaxSize()
                .windowInsetsPadding(viewportInsets)
                .padding(WhiteNoiseSpacing.Section)
                .exposePerformanceTestTags(),
            contentAlignment = Alignment.Center,
        ) {
            Surface(
                modifier = Modifier.widthIn(max = 560.dp).fillMaxWidth().heightIn(max = maxHeight),
                shape = MaterialTheme.shapes.extraLarge,
                color = MaterialTheme.colorScheme.surfaceContainerLow,
                contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                border = amoledOutlineBorder(),
            ) {
                Column(
                    Modifier.padding(WhiteNoiseSpacing.Section),
                    verticalArrangement = Arrangement.spacedBy(WhiteNoiseSpacing.FormField),
                ) {
                    Column(
                        Modifier
                            .weight(1f, fill = false)
                            .heightIn(max = 360.dp)
                            .fadingVerticalScroll(rememberScrollState()),
                        verticalArrangement = Arrangement.spacedBy(WhiteNoiseSpacing.FormField),
                    ) {
                        Text(
                            stringResource(R.string.profile_nickname_and_notes),
                            style = MaterialTheme.typography.headlineSmall,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                        form()
                    }
                    FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) { actions() }
                }
            }
        }
    }
}
