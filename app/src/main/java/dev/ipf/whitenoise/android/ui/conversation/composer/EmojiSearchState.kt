@file:Suppress("FunctionNaming")

package dev.ipf.whitenoise.android.ui.conversation.composer

import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import dev.ipf.whitenoise.android.R
import dev.ipf.whitenoise.android.ui.common.WhiteNoiseCompactSearchField
import dev.ipf.whitenoise.android.ui.theme.WhiteNoiseSpacing

internal const val EMOJI_PICKER_SEARCH_CLOSE_TEST_TAG = "emoji.picker.search_close"

/** Space above and below the composer's search field, kept tight so the matches sit close under it. */
private val ComposerEmojiSearchFieldInset = 4.dp

/**
 * Search field state. Behind the icon the field opens on demand, and leaving it (close action, Back,
 * keyboard hidden) keeps the field and matches in place until [finishClose] returns to browsing.
 */
@Stable
internal class EmojiSearchState(
    val behindIcon: Boolean,
    queryState: MutableState<String>,
) {
    var query by queryState
    var isOpen by mutableStateOf(!behindIcon)
        private set
    var closing by mutableStateOf(false)
        private set
    private var focused = false
    val focusRequester = FocusRequester()

    fun open() {
        isOpen = true
    }

    fun onFocusChanged(
        isFocused: Boolean,
        onActiveChange: (Boolean) -> Unit,
    ) {
        if (behindIcon && focused && !isFocused) return close(onActiveChange)
        if (isFocused) closing = false
        focused = isFocused
        onActiveChange(isFocused)
    }

    fun close(onActiveChange: (Boolean) -> Unit) {
        if (focused) {
            focused = false
            onActiveChange(false)
        }
        closing = true
    }

    fun finishClose() {
        query = ""
        isOpen = false
        closing = false
    }
}

@Composable
internal fun rememberEmojiSearchState(behindIcon: Boolean): EmojiSearchState {
    // The composer does not restore its pane, so a search behind the icon starts fresh on every open.
    val query = if (behindIcon) remember { mutableStateOf("") } else rememberSaveable { mutableStateOf("") }
    return remember(behindIcon, query) { EmojiSearchState(behindIcon, query) }
}

/** Opening the search from the icon focuses the field and raises the keyboard; closing returns to browsing. */
@Composable
internal fun EmojiSearchEffects(search: EmojiSearchState) {
    val keyboard = LocalSoftwareKeyboardController.current
    LaunchedEffect(search, search.isOpen) {
        if (search.behindIcon && search.isOpen) {
            search.focusRequester.requestFocus()
            keyboard?.show()
        }
    }
    LaunchedEffect(search, search.closing) {
        if (search.closing) search.finishClose()
    }
}

/**
 * Compact search field of the emoji picker. Behind the icon a leading back action closes the search,
 * and the trailing clear action only appears once there is text to clear.
 */
@Composable
internal fun EmojiSearchField(
    search: EmojiSearchState,
    onActiveChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    val focusManager = LocalFocusManager.current
    val placeholder = stringResource(R.string.emoji_search_hint)
    val fieldModifier =
        modifier
            .focusRequester(search.focusRequester)
            .onFocusChanged { search.onFocusChanged(it.isFocused, onActiveChange) }
            .testTag(EMOJI_PICKER_SEARCH_TEST_TAG)
    if (!search.behindIcon) {
        WhiteNoiseCompactSearchField(
            value = search.query,
            onValueChange = { search.query = it },
            placeholder = placeholder,
            modifier =
                Modifier
                    .padding(
                        start = WhiteNoiseSpacing.CompactScreenMargin,
                        end = WhiteNoiseSpacing.CompactScreenMargin,
                        bottom = WhiteNoiseSpacing.Related,
                    ).then(fieldModifier),
            outlined = true,
            clearDescription = stringResource(R.string.emoji_search_clear),
        )
        return
    }
    WhiteNoiseCompactSearchField(
        value = search.query,
        onValueChange = { search.query = it },
        placeholder = placeholder,
        modifier =
            Modifier
                .padding(horizontal = WhiteNoiseSpacing.CompactScreenMargin, vertical = ComposerEmojiSearchFieldInset)
                .then(fieldModifier),
        outlined = true,
        clearDescription = stringResource(R.string.emoji_search_clear),
        leadingIcon = {
            IconButton(
                onClick = {
                    focusManager.clearFocus()
                    search.close(onActiveChange)
                },
                modifier = Modifier.testTag(EMOJI_PICKER_SEARCH_CLOSE_TEST_TAG),
            ) {
                Icon(painterResource(R.drawable.ic_arrow_back), contentDescription = stringResource(R.string.back))
            }
        },
    )
}
