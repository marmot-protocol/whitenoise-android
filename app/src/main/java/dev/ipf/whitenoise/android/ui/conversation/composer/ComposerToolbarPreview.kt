@file:Suppress("FunctionNaming")

package dev.ipf.whitenoise.android.ui.conversation.composer

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import dev.ipf.whitenoise.android.state.ComposerShortcut
import dev.ipf.whitenoise.android.state.ComposerToolbarSettings
import dev.ipf.whitenoise.android.ui.common.accountActionColors

private val PreviewPrimaryEndInset = 4.dp

/** Uses production geometry with synthetic text and inert adapters; it never acquires media or clipboard data. */
@Composable
@Suppress("LongMethod") // One inert production-pill preview keeps its geometry and overlay together.
internal fun ComposerToolbarPreview(
    settings: ComposerToolbarSettings,
    editState: ComposerToolbarEditState? = null,
    saving: Boolean = false,
) {
    val previewHeightLimit =
        ComposerToolbarMaximumHeight + ComposerActionRowHeight +
            with(LocalDensity.current) {
                MaterialTheme.typography.bodyLarge.lineHeight
                    .toDp()
            }
    val displayedSettings = editState?.previewSettings ?: settings
    val attachments =
        setOf(
            ComposerShortcut.Camera,
            ComposerShortcut.Gallery,
            ComposerShortcut.Files,
            ComposerShortcut.Location,
            ComposerShortcut.Person,
            ComposerShortcut.Contact,
            ComposerShortcut.Poll,
        )
    val visibleShortcuts =
        displayedSettings.displayedShortcuts.filter {
            it != ComposerShortcut.More ||
                (attachments + composerMenuTextShortcuts - displayedSettings.shortcuts.toSet()).isNotEmpty()
        }
    // Send always keeps its place; the voice launcher shares it only with Send placement.
    val protectedWidth = ComposerShortcutTarget
    var editingProgress by remember { mutableStateOf<State<Float>?>(null) }
    val primaryAlignment = remember { ComposerActionRowAlignment(Alignment.End) { editingProgress?.value ?: 0f } }
    BoxWithConstraints(
        Modifier
            .fillMaxWidth()
            .padding(vertical = ComposerToolbarGutter)
            .heightIn(max = previewHeightLimit)
            .testTag("composer.toolbar.preview")
            .onGloballyPositioned { editState?.selectedArea = it.boundsInRoot() }
            .composerPreviewGestures(editState, saving),
    ) {
        ComposerPill(
            textFieldValue = TextFieldValue(),
            composerFocus = remember { FocusRequester() },
            emojiPickerOpen = false,
            onValueChange = {},
            onEmojiPickerToggle = {},
            onAttachmentsToggle = {},
            attachmentSheetOpen = false,
            onPickFromGallery = {},
            onPickDocument = {},
            onDictation = {},
            hasCameraCapture = true,
            hasLocationShare = true,
            hasUserShare = true,
            hasContactShare = true,
            actionColors = accountActionColors(null),
            sendingIdentity = { Icon(painterResource(ComposerShortcut.SendingIdentity.iconRes), null) },
            inputFocusEnabled = false,
            toolbar =
                ComposerToolbarBindings(
                    displayedSettings,
                    visibleShortcuts,
                    emptyMap(),
                    enabled = false,
                    scrollEnabled = editState?.dragging == null,
                    slotModifier = { shortcut ->
                        if (editState != null) composerPreviewDragModifier(shortcut, editState) else Modifier
                    },
                ),
            compactMeasurementWidth = maxWidth,
            expandedTrailingActionInset = protectedWidth,
            onEditingProgress = { editingProgress = it },
            modifier = Modifier.fillMaxWidth(),
            toolbarPreview = true,
        )
        if (editState != null) ComposerToolbarLiftedIcon(editState)
        Box(
            Modifier
                .align(primaryAlignment)
                .testTag("composer.toolbar.preview.primary")
                .width(ComposerShortcutTarget + PreviewPrimaryEndInset)
                .height(ComposerShortcutTarget)
                .padding(end = PreviewPrimaryEndInset),
            contentAlignment = Alignment.Center,
        ) {
            if (displayedSettings.voiceOnSend) {
                MiniWaveformGlyph(MaterialTheme.colorScheme.onSurfaceVariant)
            } else {
                Icon(
                    painterResource(dev.ipf.whitenoise.android.R.drawable.ic_arrow_upward),
                    null,
                    Modifier.size(ComposerShortcutIconSize),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}
