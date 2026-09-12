package dev.ipf.whitenoise.android.ui.settings

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import dev.ipf.whitenoise.android.R
import dev.ipf.whitenoise.android.state.BubbleSide
import dev.ipf.whitenoise.android.state.BubbleTheme
import dev.ipf.whitenoise.android.state.WhiteNoiseAppState
import dev.ipf.whitenoise.android.ui.common.WhiteNoiseButton
import dev.ipf.whitenoise.android.ui.common.WhiteNoiseDropdownMenu
import dev.ipf.whitenoise.android.ui.common.WhiteNoiseMenuItem
import dev.ipf.whitenoise.android.ui.conversation.messages.BubblePresentationTokens
import dev.ipf.whitenoise.android.ui.conversation.messages.colorFromArgb
import dev.ipf.whitenoise.android.ui.conversation.messages.resolveBubblePresentationArgb
import dev.ipf.whitenoise.android.ui.theme.amoledOutlineBorder

/**
 * Chat bubble colour editor for the account's theme defaults or, with [groupIdHex], one conversation's override.
 * Both sides are drafted locally, previewed in the pinned header and written together on Save.
 */
@Suppress("FunctionNaming")
@Composable
internal fun ChatBubbleColorsScreen(
    appState: WhiteNoiseAppState,
    onBack: () -> Unit,
    groupIdHex: String? = null,
) {
    val bubbleTheme = BubbleTheme.resolve(appState.themeMode, isSystemInDarkTheme())
    if (bubbleTheme == BubbleTheme.Amoled) {
        OutlineColorNotice(stringResource(R.string.chat_bubble_colors), onBack)
        return
    }
    val accountScope = appState.activeAccountRef?.trim()?.takeIf(String::isNotEmpty) ?: "none"
    val global =
        BubbleColorPair(
            mine = appState.globalBubbleColorArgb(bubbleTheme, BubbleSide.Mine),
            other = appState.globalBubbleColorArgb(bubbleTheme, BubbleSide.Other),
        )
    val initial =
        if (groupIdHex == null) {
            global
        } else {
            BubbleColorPair(
                mine = appState.chatBubbleColorArgb(groupIdHex, BubbleSide.Mine),
                other = appState.chatBubbleColorArgb(groupIdHex, BubbleSide.Other),
            )
        }
    val inherited = if (groupIdHex == null) BubbleColorPair(null, null) else global
    // Source changes start a fresh editor; local drafts never mutate these inputs.
    key(accountScope, groupIdHex, bubbleTheme, initial, inherited) {
        ChatBubbleColorEditor(
            perChat = groupIdHex != null,
            theme = bubbleTheme,
            source = BubbleColorSource(initial, inherited),
            onBack = onBack,
            onSave = { pair ->
                if (groupIdHex == null) {
                    appState.updateGlobalBubbleColor(bubbleTheme, BubbleSide.Mine, pair.mine)
                    appState.updateGlobalBubbleColor(bubbleTheme, BubbleSide.Other, pair.other)
                } else {
                    appState.updateChatBubbleColor(groupIdHex, BubbleSide.Mine, pair.mine)
                    appState.updateChatBubbleColor(groupIdHex, BubbleSide.Other, pair.other)
                }
            },
        )
    }
}

@Suppress("FunctionNaming", "LongMethod", "CyclomaticComplexMethod")
@Composable
private fun ChatBubbleColorEditor(
    perChat: Boolean,
    theme: BubbleTheme,
    source: BubbleColorSource,
    onBack: () -> Unit,
    onSave: (BubbleColorPair) -> Unit,
) {
    val (initial, inherited) = source
    var mine by rememberSaveable { mutableStateOf(initial.mine) }
    var other by rememberSaveable { mutableStateOf(initial.other) }
    var reset by rememberSaveable { mutableStateOf(false) }
    var resetRevision by rememberSaveable { mutableIntStateOf(0) }
    var mineValid by rememberSaveable { mutableStateOf(true) }
    var otherValid by rememberSaveable { mutableStateOf(true) }
    var menuOpen by remember { mutableStateOf(false) }
    val mineSelected = (if (reset) null else initial.mine) ?: inherited.mine
    val otherSelected = (if (reset) null else initial.other) ?: inherited.other
    val canReset = mine != null || other != null || !mineValid || !otherValid
    val changed = mine != initial.mine || other != initial.other
    val defaults = defaultBubbleColors()
    SettingsScaffold(
        title = stringResource(R.string.chat_bubble_colors),
        onBack = onBack,
        topBarActions = {
            Box {
                IconButton(onClick = { menuOpen = true }, modifier = Modifier.testTag("bubble_colors.menu")) {
                    Icon(painterResource(R.drawable.ic_more_vert), stringResource(R.string.more_options))
                }
                WhiteNoiseDropdownMenu(
                    expanded = menuOpen,
                    onDismissRequest = { menuOpen = false },
                    items =
                        listOf(
                            WhiteNoiseMenuItem(
                                label =
                                    stringResource(
                                        if (perChat) R.string.reset_to_global_colors else R.string.reset_to_default,
                                    ),
                                enabled = canReset,
                                onClick = {
                                    mine = null
                                    other = null
                                    mineValid = true
                                    otherValid = true
                                    reset = true
                                    resetRevision++
                                },
                            ),
                        ),
                )
            }
        },
        bottomBar = {
            SettingsBottomAction {
                WhiteNoiseButton(
                    onClick = {
                        onSave(BubbleColorPair(mine, other))
                        onBack()
                    },
                    enabled = changed && mineValid && otherValid,
                    modifier = Modifier.fillMaxWidth().testTag("bubble_colors.save"),
                ) { Text(stringResource(R.string.save)) }
            }
        },
    ) {
        SettingsList {
            stickyHeader {
                Surface(color = MaterialTheme.colorScheme.surfaceContainerLow) {
                    SettingsGroup {
                        row("preview") { context ->
                            SettingsGroupPanel(context) {
                                BubblePreview(
                                    mineArgb = mine ?: inherited.mine,
                                    otherArgb = other ?: inherited.other,
                                    modifier = Modifier.padding(16.dp),
                                )
                            }
                        }
                    }
                }
            }
            item { SettingsSection(stringResource(R.string.bubble_my_messages)) }
            item {
                SettingsGroup {
                    row("mine") { context ->
                        SettingsGroupPanel(context) {
                            key(resetRevision) {
                                FullSpectrumColorPicker(
                                    selectedArgb = mineSelected,
                                    fallbackArgb = defaults.mine,
                                    onColorSelected = { mine = it },
                                    onValidityChanged = { mineValid = it },
                                    modifier = Modifier.padding(16.dp).testTag("bubble_colors.mine.picker"),
                                )
                            }
                        }
                    }
                }
            }
            item { SettingsSection(stringResource(R.string.bubble_other_messages)) }
            item {
                SettingsGroup {
                    row("other") { context ->
                        SettingsGroupPanel(context) {
                            key(resetRevision) {
                                FullSpectrumColorPicker(
                                    selectedArgb = otherSelected,
                                    fallbackArgb = defaults.other,
                                    onColorSelected = { other = it },
                                    onValidityChanged = { otherValid = it },
                                    modifier = Modifier.padding(16.dp).testTag("bubble_colors.other.picker"),
                                )
                            }
                        }
                    }
                }
            }
            item {
                SettingsExplainer(
                    stringResource(
                        if (perChat) {
                            R.string.chat_bubble_colors_chat_detail
                        } else {
                            R.string.chat_bubble_colors_global_detail
                        },
                        stringResource(theme.labelRes),
                    ),
                )
            }
        }
    }
}

/** Received bubble at the start, then the sent bubble at the end, coloured exactly as a real chat would. */
@Suppress("FunctionNaming")
@Composable
private fun BubblePreview(
    mineArgb: Long?,
    otherArgb: Long?,
    modifier: Modifier = Modifier,
) {
    val tokens = previewBubbleTokens()
    val other = resolveBubblePresentationArgb(deleted = false, amoled = false, mine = false, otherArgb, tokens)
    val mine = resolveBubblePresentationArgb(deleted = false, amoled = false, mine = true, mineArgb, tokens)
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        PreviewBubble(
            text = stringResource(R.string.bubble_preview_other),
            container = colorFromArgb(other.backgroundArgb),
            content = colorFromArgb(other.contentArgb),
            modifier = Modifier.align(Alignment.Start).testTag("bubble_colors.other.preview"),
        )
        PreviewBubble(
            text = stringResource(R.string.bubble_preview_mine),
            container = colorFromArgb(mine.backgroundArgb),
            content = colorFromArgb(mine.contentArgb),
            modifier = Modifier.align(Alignment.End).testTag("bubble_colors.mine.preview"),
        )
    }
}

@Suppress("FunctionNaming")
@Composable
private fun PreviewBubble(
    text: String,
    container: Color,
    content: Color,
    modifier: Modifier,
) {
    Surface(
        modifier = modifier,
        color = container,
        contentColor = content,
        border = amoledOutlineBorder(),
        shape = MaterialTheme.shapes.large,
    ) {
        Text(text, Modifier.padding(horizontal = 16.dp, vertical = 10.dp))
    }
}

/** The theme roles a real message bubble resolves against, so the preview and the chat agree. */
@Composable
private fun previewBubbleTokens(): BubblePresentationTokens {
    val scheme = MaterialTheme.colorScheme
    return BubblePresentationTokens(
        errorBackgroundArgb = scheme.errorContainer.toOpaqueArgb(),
        errorContentArgb = scheme.onErrorContainer.toOpaqueArgb(),
        surfaceBackgroundArgb = scheme.surfaceVariant.toOpaqueArgb(),
        surfaceContentArgb = scheme.onSurfaceVariant.toOpaqueArgb(),
        mineBackgroundArgb = scheme.primaryContainer.toOpaqueArgb(),
        mineContentArgb = scheme.onPrimaryContainer.toOpaqueArgb(),
        mentionAccentArgb = scheme.primary.toOpaqueArgb(),
    )
}

/** Where each picker starts when no colour is saved: the theme's own bubble fills. */
@Composable
private fun defaultBubbleColors(): BubbleColorDefaults {
    val scheme = MaterialTheme.colorScheme
    return BubbleColorDefaults(
        mine = scheme.primaryContainer.toOpaqueArgb(),
        other = scheme.surfaceVariant.toOpaqueArgb(),
    )
}

/** One colour per bubble side; null means the side keeps its inherited or default fill. */
internal data class BubbleColorPair(
    val mine: Long?,
    val other: Long?,
)

/** What an editor starts from: the stored colours and, per chat, the account defaults beneath them. */
internal data class BubbleColorSource(
    val initial: BubbleColorPair,
    val inherited: BubbleColorPair,
)

/** The theme's own bubble fills, where a picker starts when nothing is saved. */
private data class BubbleColorDefaults(
    val mine: Long,
    val other: Long,
)
