@file:Suppress("FunctionNaming")

package dev.ipf.whitenoise.android.ui.conversation.composer

import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.ipf.whitenoise.android.R
import dev.ipf.whitenoise.android.state.ComposerShortcut
import dev.ipf.whitenoise.android.state.ComposerShortcutSide
import dev.ipf.whitenoise.android.state.ComposerToolbarLayout
import dev.ipf.whitenoise.android.state.ComposerToolbarSettings
import dev.ipf.whitenoise.android.state.ComposerVoicePlacement
import dev.ipf.whitenoise.android.ui.common.fadingVerticalScroll
import dev.ipf.whitenoise.android.ui.settings.settingsCardMorph

private val EditorContentInset = 12.dp

/** Shared customization surface. Completed edits save immediately; dismissing never undoes saved choices. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
@Suppress("LongMethod", "LongParameterList") // Owns one save/back session around the shared editor content.
internal fun ComposerToolbarEditor(
    state: ComposerToolbarEditState,
    saving: Boolean,
    onSave: (ComposerToolbarSettings, (Boolean) -> Unit) -> Unit,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier,
    overlayBackRegistrar: ComposerOverlayBackRegistrar? = null,
    saveFailed: Boolean = false,
) {
    val focusManager = LocalFocusManager.current
    val keyboardController = LocalSoftwareKeyboardController.current
    LaunchedEffect(state) {
        focusManager.clearFocus(force = true)
        keyboardController?.hide()
    }
    var failed by remember(state) { mutableStateOf(saveFailed) }
    var revision by remember(state) { mutableStateOf(0) }
    val latestSave by rememberUpdatedState(onSave)
    LaunchedEffect(saveFailed) { failed = saveFailed }
    var active by remember(state) { mutableStateOf(true) }
    val latestCancel by rememberUpdatedState(onCancel)
    val scroll = rememberScrollState()
    var viewport by remember { mutableStateOf(Rect.Zero) }
    DisposableEffect(state) {
        active = true
        val saveChange: (ComposerToolbarSettings) -> Unit = { settings ->
            val submitted = ++revision
            failed = false
            latestSave(settings) { success ->
                if (active && submitted == revision) failed = !success
            }
        }
        state.onChange = saveChange
        onDispose {
            active = false
            if (state.onChange === saveChange) state.onChange = null
            state.cancelDrag()
        }
    }
    BackHandler { latestCancel() }
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        ComposerPlatformPreImeBackHandler(true, { latestCancel() }, overlayBackRegistrar)
    }
    Surface(
        color = MaterialTheme.colorScheme.surface,
        modifier =
            modifier.testTag("composer.customize").pointerInput(Unit) {
                awaitPointerEventScope { while (true) awaitPointerEvent() }
            },
    ) {
        Column {
            if (failed) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        stringResource(R.string.composer_save_failed),
                        color = MaterialTheme.colorScheme.error,
                        modifier =
                            Modifier
                                .weight(1f)
                                .padding(horizontal = EditorContentInset)
                                .semantics { liveRegion = LiveRegionMode.Polite },
                    )
                    TextButton(onClick = { state.onChange?.invoke(state.draft) }, enabled = !saving) {
                        Text(stringResource(R.string.retry))
                    }
                }
            }
            Column(
                Modifier
                    .weight(1f, fill = false)
                    .onGloballyPositioned {
                        val next = it.boundsInRoot()
                        if (viewport != Rect.Zero && viewport != next) state.cancelDrag()
                        viewport = next
                    }.fadingVerticalScroll(scroll)
                    .padding(horizontal = EditorContentInset),
            ) {
                // Each section is a card the composer pill unfolds into and Back folds away.
                Box(Modifier.settingsCardMorph()) { ComposerToolbarPreview(state.draft, state, false) }
                Box(Modifier.settingsCardMorph()) { ComposerToolbarLayoutChoices(state, false) }
                Box(Modifier.settingsCardMorph()) { ComposerToolbarPalette(state, false) }
            }
        }
    }
}

/** One stable action set controls visibility; ordering happens directly in the composer preview. */
@Composable
private fun ComposerToolbarPalette(
    state: ComposerToolbarEditState,
    saving: Boolean,
) {
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        // App text size scales typography directly, independently of platform density.fontScale.
        val minimumLabelWidth =
            maxOf(
                ComposerMinimumTextWidth,
                with(LocalDensity.current) {
                    MaterialTheme.typography.bodyLarge.fontSize
                        .toDp()
                } * 6,
            )
        val inlineLabelRoom =
            maxWidth - ComposerShortcutIconSize - ComposerToolbarGutter * 2 -
                ComposerShortcutTarget * 4
        val stackSideChoices = inlineLabelRoom < minimumLabelWidth
        Column {
            ComposerShortcut.entries.forEach { shortcut ->
                key(shortcut) {
                    if (shortcut == ComposerShortcut.VoiceNote) {
                        ComposerVoicePlacementRow(state, saving)
                    } else {
                        ComposerShortcutPaletteRow(shortcut, state, saving, stackSideChoices)
                    }
                }
            }
        }
    }
    OutlinedButton(
        onClick = { state.change(ComposerToolbarSettings()) },
        enabled = !saving,
        modifier = Modifier.padding(vertical = ComposerToolbarGutter).testTag("composer.customize.reset"),
    ) {
        Icon(painterResource(R.drawable.ic_refresh), null, Modifier.size(ButtonDefaults.IconSize))
        Text(stringResource(R.string.composer_reset), Modifier.padding(start = ButtonDefaults.IconSpacing))
    }
}

/** Keeps the action name readable while side choices adapt to the available label space. */
@Composable
private fun ComposerShortcutPaletteRow(
    shortcut: ComposerShortcut,
    state: ComposerToolbarEditState,
    saving: Boolean,
    stackSideChoices: Boolean,
) {
    val index = state.draft.shortcuts.indexOf(shortcut)
    val selected = index >= 0
    val actions = composerShortcutAccessibilityActions(shortcut, state, selected, saving)
    Column {
        Row(
            Modifier
                .fillMaxWidth()
                .heightIn(min = ComposerShortcutTarget)
                .testTag("composer.customize.label.${shortcut.id}")
                .toggleable(
                    value = selected,
                    enabled = !saving && shortcut != ComposerShortcut.More,
                    role = Role.Checkbox,
                    onValueChange = { if (it) state.add(shortcut) else state.remove(shortcut) },
                ).semantics { customActions = actions },
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (shortcut == ComposerShortcut.VoiceNote) {
                MiniWaveformGlyph(MaterialTheme.colorScheme.onSurfaceVariant)
            } else {
                Icon(painterResource(shortcut.iconRes), null, Modifier.size(ComposerShortcutIconSize))
            }
            Text(
                stringResource(shortcut.labelRes),
                Modifier
                    .weight(1f)
                    .padding(horizontal = ComposerToolbarGutter)
                    .testTag("composer.customize.name.${shortcut.id}"),
            )
            if (selected && !stackSideChoices) {
                ComposerShortcutSideChoices(shortcut, state, saving)
            }
            Checkbox(
                checked = selected,
                onCheckedChange = null,
                enabled = !saving && shortcut != ComposerShortcut.More,
                modifier = Modifier.clearAndSetSemantics {},
            )
        }
        if (selected && stackSideChoices) {
            Row(
                Modifier.fillMaxWidth().padding(start = ComposerShortcutIconSize),
                horizontalArrangement = Arrangement.End,
            ) { ComposerShortcutSideChoices(shortcut, state, saving) }
        }
    }
}

/** Voice notes switch on or off like other rows, then choose Send's slot or either toolbar side. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ComposerVoicePlacementRow(
    state: ComposerToolbarEditState,
    saving: Boolean,
) {
    val enabled = state.draft.voiceEnabled
    val current = state.draft.voicePlacement
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = ComposerShortcutTarget)
            .testTag("composer.customize.label.${ComposerShortcut.VoiceNote.id}")
            .toggleable(
                value = enabled,
                enabled = !saving,
                role = Role.Checkbox,
                onValueChange = { state.change(state.draft.copy(voiceEnabled = it)) },
            ),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        MiniWaveformGlyph(MaterialTheme.colorScheme.onSurfaceVariant)
        // Choices move beneath the label only when both cannot share one line.
        FlowRow(Modifier.weight(1f), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(
                stringResource(R.string.composer_voice_placement),
                Modifier
                    .align(Alignment.CenterVertically)
                    .padding(horizontal = ComposerToolbarGutter)
                    .testTag("composer.customize.name.${ComposerShortcut.VoiceNote.id}"),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (enabled) {
                Row(Modifier.selectableGroup()) {
                    ComposerVoicePlacement.entries.forEach { placement ->
                        ComposerVoicePlacementChoice(placement, current == placement, state, saving)
                    }
                }
            }
        }
        Checkbox(
            checked = enabled,
            onCheckedChange = null,
            enabled = !saving,
            modifier = Modifier.clearAndSetSemantics {},
        )
    }
}

@Composable
private fun ComposerVoicePlacementChoice(
    placement: ComposerVoicePlacement,
    chosen: Boolean,
    state: ComposerToolbarEditState,
    saving: Boolean,
) {
    TextButton(
        onClick = { state.change(state.draft.withVoicePlacement(placement)) },
        enabled = !saving,
        contentPadding = ComposerChoicePadding,
        modifier =
            Modifier
                .testTag("composer.customize.voice.${placement.name.lowercase()}")
                .semantics {
                    role = Role.RadioButton
                    selected = chosen
                },
    ) {
        Text(
            stringResource(placement.labelRes),
            maxLines = 1,
            softWrap = false,
            fontWeight = if (chosen) FontWeight.Bold else FontWeight.Normal,
            textDecoration = if (chosen) TextDecoration.Underline else TextDecoration.None,
        )
    }
}

private val ComposerChoicePadding = PaddingValues(horizontal = 8.dp)

private val ComposerVoicePlacement.labelRes: Int
    get() =
        when (this) {
            ComposerVoicePlacement.Send -> R.string.composer_voice_on_send
            ComposerVoicePlacement.Left -> R.string.composer_side_left
            ComposerVoicePlacement.Right -> R.string.composer_side_right
        }

/** Keeps side selection independent of the visibility checkbox, inline or beneath its label. */
@Composable
private fun ComposerShortcutSideChoices(
    shortcut: ComposerShortcut,
    state: ComposerToolbarEditState,
    saving: Boolean,
) {
    ComposerShortcutSide.entries.forEach { side ->
        val onThisSide = state.draft.side(shortcut) == side
        TextButton(
            onClick = { state.change(state.draft.withSide(shortcut, side)) },
            enabled = !saving,
            modifier =
                Modifier.semantics {
                    role = Role.RadioButton
                    this.selected = onThisSide
                },
        ) {
            val label =
                if (side == ComposerShortcutSide.Left) {
                    R.string.composer_side_left
                } else {
                    R.string.composer_side_right
                }
            Text(
                stringResource(label),
                fontWeight = if (onThisSide) FontWeight.Bold else FontWeight.Normal,
                textDecoration = if (onThisSide) TextDecoration.Underline else TextDecoration.None,
            )
        }
    }
}

/** Shows the draft layout choice with a visible radio indicator. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ComposerToolbarLayoutChoices(
    state: ComposerToolbarEditState,
    saving: Boolean,
) {
    Text(stringResource(R.string.composer_layout), style = MaterialTheme.typography.titleSmall)
    FlowRow(Modifier.selectableGroup()) {
        ComposerToolbarLayout.entries.forEach { layout ->
            TextButton(
                onClick = { state.change(state.draft.copy(layout = layout)) },
                enabled = !saving,
                modifier =
                    Modifier.semantics {
                        role = Role.RadioButton
                        selected = state.draft.layout == layout
                    },
            ) {
                RadioButton(
                    selected = state.draft.layout == layout,
                    onClick = null,
                    enabled = !saving,
                    modifier = Modifier.clearAndSetSemantics {},
                )
                Text(
                    stringResource(
                        if (layout ==
                            ComposerToolbarLayout.Automatic
                        ) {
                            R.string.composer_layout_automatic
                        } else {
                            R.string.composer_layout_expanded
                        },
                    ),
                )
            }
        }
    }
}

/** Exposes draft editing operations without requiring a pointer drag. */
@Composable
private fun composerShortcutAccessibilityActions(
    shortcut: ComposerShortcut,
    state: ComposerToolbarEditState,
    selected: Boolean,
    saving: Boolean,
): List<CustomAccessibilityAction> {
    val earlier = stringResource(R.string.composer_move_earlier)
    val later = stringResource(R.string.composer_move_later)
    val add = stringResource(R.string.composer_add)
    val remove = stringResource(R.string.composer_remove)
    val left = stringResource(R.string.composer_side_left)
    val right = stringResource(R.string.composer_side_right)
    if (saving) return emptyList()
    return buildList {
        if (selected) {
            val siblings = state.draft.onSide(state.draft.side(shortcut))
            val sideIndex = siblings.indexOf(shortcut)
            add(
                CustomAccessibilityAction(left) {
                    state.change(state.draft.withSide(shortcut, ComposerShortcutSide.Left))
                    true
                },
            )
            add(
                CustomAccessibilityAction(right) {
                    state.change(state.draft.withSide(shortcut, ComposerShortcutSide.Right))
                    true
                },
            )
            if (sideIndex > 0) {
                add(
                    CustomAccessibilityAction(earlier) {
                        state.move(shortcut, -1)
                        true
                    },
                )
            }
            if (sideIndex < siblings.lastIndex) {
                add(
                    CustomAccessibilityAction(later) {
                        state.move(shortcut, 1)
                        true
                    },
                )
            }
            if (shortcut != ComposerShortcut.More) {
                add(
                    CustomAccessibilityAction(remove) {
                        state.remove(shortcut)
                        true
                    },
                )
            }
        } else {
            add(
                CustomAccessibilityAction(add) {
                    state.add(shortcut)
                    true
                },
            )
        }
    }
}
