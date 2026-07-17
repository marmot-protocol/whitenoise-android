package dev.ipf.whitenoise.android.ui.settings

import androidx.annotation.StringRes
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.unit.dp
import dev.ipf.whitenoise.android.R
import dev.ipf.whitenoise.android.state.AppFontScale
import dev.ipf.whitenoise.android.state.WhiteNoiseAppState
import dev.ipf.whitenoise.android.ui.common.SectionCard
import dev.ipf.whitenoise.android.ui.conversation.messages.messageBubbleBorder
import dev.ipf.whitenoise.android.ui.conversation.messages.messageBubbleFillColor

internal val AppFontScale.labelRes: Int
    @StringRes
    get() =
        when (this) {
            AppFontScale.Small -> R.string.font_scale_small
            AppFontScale.Default -> R.string.font_scale_default
            AppFontScale.Large -> R.string.font_scale_large
            AppFontScale.ExtraLarge -> R.string.font_scale_extra_large
        }

/**
 * Settings -> Appearance -> Font size (issue #403). A four-step in-app text
 * scale with a live message-bubble preview at the top. Tapping a step persists
 * immediately via [WhiteNoiseAppState.updateFontScale] — no Save button — and
 * the theme typography rescales on the spot, so the preview (rendered with the
 * ambient, already-scaled typography) updates live. The in-app factor
 * multiplies sp sizes, which already include the OS font scale, so the
 * effective size is system x app.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun FontSizeScreen(
    appState: WhiteNoiseAppState,
    onBack: () -> Unit,
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.font_size)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.back))
                    }
                },
            )
        },
    ) { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding).padding(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            item {
                SectionCard(title = stringResource(R.string.font_size_preview_title)) {
                    FontSizePreviewBubble(text = stringResource(R.string.font_size_preview_incoming), mine = false)
                    FontSizePreviewBubble(text = stringResource(R.string.font_size_preview_outgoing), mine = true)
                }
            }
            item {
                SectionCard(title = stringResource(R.string.font_size)) {
                    AppFontScale.entries.forEach { scale ->
                        SelectableSettingsRow(
                            title = stringResource(scale.labelRes),
                            selected = appState.fontScale == scale,
                            onClick = { appState.updateFontScale(scale) },
                        )
                    }
                }
            }
            item {
                Text(
                    text = stringResource(R.string.font_size_description),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

// Mirrors the conversation bubble treatment (color roles, 18dp radius, AMOLED
// border) so the preview tracks what real chat text looks like at the selected
// step. Body text uses the same bodyLarge style as message bubbles.
@Composable
internal fun FontSizePreviewBubble(
    text: String,
    mine: Boolean,
) {
    val bubbleColor = messageBubbleFillColor(invalidated = false, deleted = false, mine = mine)
    Box(Modifier.fillMaxWidth()) {
        Surface(
            modifier = Modifier.align(if (mine) Alignment.CenterEnd else Alignment.CenterStart),
            color = bubbleColor,
            shape = RoundedCornerShape(18.dp),
            border = messageBubbleBorder(highlighted = false, mine = mine),
            tonalElevation = if (mine) 1.dp else 0.dp,
        ) {
            Text(
                text = text,
                style = MaterialTheme.typography.bodyLarge,
                modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
            )
        }
    }
}
