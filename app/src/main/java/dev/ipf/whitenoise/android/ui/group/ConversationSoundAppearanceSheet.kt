@file:Suppress("FunctionNaming") // Compose UI entry points intentionally use PascalCase.

package dev.ipf.whitenoise.android.ui.group

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import dev.ipf.whitenoise.android.R
import dev.ipf.whitenoise.android.ui.common.WhiteNoiseModalBottomSheet
import dev.ipf.whitenoise.android.ui.common.WhiteNoiseSheetHeader
import dev.ipf.whitenoise.android.ui.common.fadingVerticalScroll
import dev.ipf.whitenoise.android.ui.theme.WhiteNoiseSpacing

internal const val SOUND_APPEARANCE_OPEN_TAG = "conversation-sound-appearance-open"
internal const val SOUND_APPEARANCE_CONTENT_TAG = "conversation-sound-appearance-content"
private const val SHEET_HEIGHT_SHARE = 0.85f

/** Shared group/DM sound drawer, with a pinned close header and scrollable category settings. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ConversationSoundAppearanceSheet(
    onDismiss: () -> Unit,
    content: @Composable () -> Unit,
) {
    val windowHeight = LocalWindowInfo.current.containerSize.height
    val maxHeight = with(LocalDensity.current) { (windowHeight * SHEET_HEIGHT_SHARE).toDp() }
    WhiteNoiseModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.fillMaxWidth().heightIn(max = maxHeight)) {
            WhiteNoiseSheetHeader(stringResource(R.string.notification_sound_appearance), onClose = onDismiss)
            Box(
                Modifier
                    .weight(1f, fill = false)
                    .fillMaxWidth()
                    .fadingVerticalScroll(rememberScrollState())
                    .padding(horizontal = WhiteNoiseSpacing.Section, vertical = WhiteNoiseSpacing.Related)
                    .testTag(SOUND_APPEARANCE_CONTENT_TAG),
            ) {
                content()
            }
        }
    }
}
