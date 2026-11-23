package dev.ipf.whitenoise.android.ui.group

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.text.input.TextFieldLineLimits
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import dev.ipf.whitenoise.android.R
import dev.ipf.whitenoise.android.ui.common.EmojiSheetFieldAnchor
import dev.ipf.whitenoise.android.ui.common.GroupNameEmojiField
import dev.ipf.whitenoise.android.ui.common.WhiteNoiseTextField
import dev.ipf.whitenoise.android.ui.theme.WhiteNoiseSpacing

@Suppress("FunctionNaming", "LongParameterList")
@Composable
internal fun GroupEditFields(
    name: TextFieldState,
    description: TextFieldState,
    enabled: Boolean,
    nameEmojiAnchor: EmojiSheetFieldAnchor,
    emojiPickerOpen: Boolean,
    onEmojiPickerClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier =
            modifier
                .widthIn(max = 520.dp)
                .fillMaxWidth()
                .padding(horizontal = WhiteNoiseSpacing.CompactScreenMargin),
        verticalArrangement = Arrangement.spacedBy(WhiteNoiseSpacing.FormField),
    ) {
        GroupNameEmojiField(
            state = name,
            label = stringResource(R.string.group_name),
            emojiPickerOpen = emojiPickerOpen,
            onEmojiPickerClick = onEmojiPickerClick,
            enabled = enabled,
            modifier = Modifier.fillMaxWidth(),
            emojiAnchor = nameEmojiAnchor,
        )
        WhiteNoiseTextField(
            state = description,
            emojiAction = true,
            label = { Text(stringResource(R.string.description)) },
            lineLimits = TextFieldLineLimits.MultiLine(minHeightInLines = 1, maxHeightInLines = 6),
            enabled = enabled,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}
