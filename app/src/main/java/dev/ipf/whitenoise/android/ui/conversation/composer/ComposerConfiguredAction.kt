@file:Suppress("FunctionNaming")

package dev.ipf.whitenoise.android.ui.conversation.composer

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.IntRect
import dev.ipf.whitenoise.android.R
import dev.ipf.whitenoise.android.state.ComposerShortcut
import dev.ipf.whitenoise.android.ui.common.SystemPasteIconButton
import dev.ipf.whitenoise.android.ui.common.TextEntryEmojiAction
import dev.ipf.whitenoise.android.ui.navigation.dictationLauncherOrigin
import kotlin.math.roundToInt

private const val DISABLED_VOICE_OPACITY = 0.38f

/** Reuses the existing action owners, including the trusted system Paste fallback and native attachment menu. */
@Composable
@Suppress("LongParameterList", "LongMethod")
internal fun ComposerConfiguredAction(
    shortcut: ComposerShortcut,
    modifier: Modifier,
    bindings: ComposerToolbarBindings,
    enabled: Boolean,
    preview: Boolean,
    pasteKey: Any?,
    onPaste: () -> Boolean,
    onMore: () -> Unit,
    onAnchor: (IntRect) -> Unit,
    attachmentMenu: (@Composable (IntRect) -> Unit)?,
    anchor: IntRect,
    emojiPickerOpen: Boolean,
    onEmoji: () -> Unit,
    onDictation: (() -> Unit)?,
    sendingIdentity: (@Composable (Modifier) -> Unit)?,
) {
    if (preview) {
        if (shortcut == ComposerShortcut.VoiceNote) {
            MiniWaveformGlyph(MaterialTheme.colorScheme.onSurfaceVariant)
            return
        }
        Icon(painterResource(shortcut.iconRes), null, Modifier.size(ComposerShortcutIconSize))
        return
    }
    val voiceLabel = stringResource(R.string.voice_message_record)
    when (shortcut) {
        ComposerShortcut.SendingIdentity -> sendingIdentity?.invoke(modifier)
        ComposerShortcut.More ->
            Box(
                Modifier.onGloballyPositioned { coordinates ->
                    val position = coordinates.positionInWindow()
                    onAnchor(
                        IntRect(
                            position.x.roundToInt(),
                            position.y.roundToInt(),
                            position.x.roundToInt() + coordinates.size.width,
                            position.y.roundToInt() + coordinates.size.height,
                        ),
                    )
                },
            ) {
                IconButton(
                    onClick = { if (bindings.canActivate()) onMore() },
                    enabled = enabled,
                    modifier = modifier,
                ) {
                    Icon(
                        painterResource(shortcut.iconRes),
                        stringResource(shortcut.labelRes),
                        Modifier.size(ComposerShortcutIconSize),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                attachmentMenu?.invoke(anchor)
            }
        ComposerShortcut.Emoji ->
            TextEntryEmojiAction(
                pickerOpen = emojiPickerOpen,
                enabled = enabled,
                onClick = { if (bindings.canActivate()) onEmoji() },
                togglesKeyboard = true,
                modifier = modifier,
                iconSize = ComposerShortcutIconSize,
                emojiIcon = painterResource(R.drawable.ic_emoji_smileys),
            )
        ComposerShortcut.Paste ->
            key(pasteKey, enabled) {
                SystemPasteIconButton(
                    onPaste = onPaste,
                    enabled = enabled,
                    modifier = modifier.testTag("composer.paste"),
                ) {
                    Icon(
                        painterResource(shortcut.iconRes),
                        stringResource(shortcut.labelRes),
                        Modifier.size(ComposerShortcutIconSize),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        // The real held-pointer node has one stable call site in ComposerBar, positioned over this slot.
        ComposerShortcut.VoiceNote -> {
            if (!bindings.voiceLauncherVisible) {
                IconButton(onClick = {}, enabled = false, modifier = modifier) {
                    Box(Modifier.semantics { contentDescription = voiceLabel }) {
                        MiniWaveformGlyph(
                            MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = DISABLED_VOICE_OPACITY),
                        )
                    }
                }
            }
        }
        else -> ComposerOptionalShortcutAction(shortcut, modifier, bindings, enabled, onDictation)
    }
}

/** Renders dictation and attachment shortcuts while rechecking activation at click time. */
@Composable
private fun ComposerOptionalShortcutAction(
    shortcut: ComposerShortcut,
    modifier: Modifier,
    bindings: ComposerToolbarBindings,
    enabled: Boolean,
    onDictation: (() -> Unit)?,
) {
    val shortcutEnabled = enabled && (shortcut != ComposerShortcut.Dictation || onDictation != null)
    IconButton(
        onClick = {
            if (bindings.canActivate()) {
                if (shortcut == ComposerShortcut.Dictation) {
                    onDictation?.invoke()
                } else {
                    bindings.attachmentActions[shortcut]?.invoke()
                }
            }
        },
        enabled = shortcutEnabled,
        modifier =
            modifier.then(
                if (shortcut == ComposerShortcut.Dictation) {
                    Modifier.dictationLauncherOrigin()
                } else {
                    Modifier
                },
            ),
    ) {
        Icon(
            painterResource(shortcut.iconRes),
            stringResource(shortcut.labelRes),
            Modifier.size(ComposerShortcutIconSize),
            tint =
                if (shortcut == ComposerShortcut.Dictation && !shortcutEnabled) {
                    MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = DISABLED_VOICE_OPACITY)
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
        )
    }
}
