@file:Suppress("FunctionNaming", "MatchingDeclarationName")

package dev.ipf.whitenoise.android.ui.conversation.composer

import android.view.View
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.DropdownMenuGroup
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MenuDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionOnScreen
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalTextToolbar
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.toRect
import androidx.compose.ui.unit.toSize
import androidx.compose.ui.window.PopupPositionProvider
import dev.ipf.whitenoise.android.R
import dev.ipf.whitenoise.android.state.ComposerShortcut
import dev.ipf.whitenoise.android.ui.common.LocalControlHandoffEndpointCurrent
import dev.ipf.whitenoise.android.ui.common.WhiteNoiseMenuItem
import dev.ipf.whitenoise.android.ui.common.boundedIntrinsicWidth
import dev.ipf.whitenoise.android.ui.common.fadingVerticalScroll
import dev.ipf.whitenoise.android.ui.design.KeyboardSafePopup
import dev.ipf.whitenoise.android.ui.navigation.DictationLauncherOrigin
import dev.ipf.whitenoise.android.ui.navigation.LocalDictationLauncherOrigin
import dev.ipf.whitenoise.android.ui.theme.amoledOutlineBorder

/** Above-Add placement mirrors the pinned prototype, with its below-anchor fallback in short windows. */
internal class ComposerAttachmentMenuPositionProvider(
    private val sourceBounds: IntRect,
    private val gapPx: Int,
    private val edgePx: Int,
) : PopupPositionProvider {
    /** A raised Add button bounds the viewport even when the popup measures the full window. */
    fun maxHeight(
        windowHeightPx: Int,
        minimumViewportPx: Int,
    ): Int {
        val above = (minOf(sourceBounds.top, windowHeightPx) - gapPx - edgePx).coerceAtLeast(0)
        val below = (windowHeightPx - sourceBounds.bottom - gapPx - edgePx).coerceAtLeast(0)
        return if (above >= minimumViewportPx) above else maxOf(above, below)
    }

    /** Anchors the menu above the attachment button, clamped to the window. */
    override fun calculatePosition(
        anchorBounds: IntRect,
        windowSize: IntSize,
        layoutDirection: LayoutDirection,
        popupContentSize: IntSize,
    ): IntOffset {
        val preferredX =
            if (layoutDirection == LayoutDirection.Ltr) {
                sourceBounds.left
            } else {
                sourceBounds.right - popupContentSize.width
            }
        val availableWidth = windowSize.width - edgePx * 2
        val x =
            if (popupContentSize.width >= availableWidth) {
                (windowSize.width - popupContentSize.width) / 2
            } else {
                preferredX.coerceIn(edgePx, windowSize.width - edgePx - popupContentSize.width)
            }
        val aboveY = sourceBounds.top - gapPx - popupContentSize.height
        val belowY = sourceBounds.bottom + gapPx
        val fitsBelow = belowY + popupContentSize.height <= windowSize.height - edgePx
        val preferredY = if (aboveY >= edgePx || !fitsBelow) aboveY else belowY
        val lastY = (windowSize.height - edgePx - popupContentSize.height).coerceAtLeast(edgePx)
        val y = preferredY.coerceIn(edgePx, lastY)
        return IntOffset(x, y)
    }
}

/** Text actions the Plus menu offers whenever they are not pinned to the toolbar. */
internal val composerMenuTextShortcuts =
    setOf(ComposerShortcut.Emoji, ComposerShortcut.Paste, ComposerShortcut.Dictation, ComposerShortcut.Translate)

/** Callbacks for unpinned text actions; Paste reports false when the system denies clipboard access. */
internal data class ComposerMenuTextShortcuts(
    val onEmoji: (() -> Unit)? = null,
    val onPaste: (() -> Boolean)? = null,
    val onDictation: (() -> Unit)? = null,
    val onTranslate: (() -> Unit)? = null,
    val translateActive: Boolean = false,
    val pasteAccepted: () -> Boolean = { true },
) {
    /** Drops actions already pinned to the toolbar or unavailable in this composer. */
    fun onlyHidden(hidden: Set<ComposerShortcut>): ComposerMenuTextShortcuts =
        ComposerMenuTextShortcuts(
            onEmoji = onEmoji?.takeIf { ComposerShortcut.Emoji in hidden },
            onPaste = onPaste?.takeIf { ComposerShortcut.Paste in hidden },
            onDictation = onDictation?.takeIf { ComposerShortcut.Dictation in hidden },
            onTranslate = onTranslate?.takeIf { ComposerShortcut.Translate in hidden },
            translateActive = translateActive,
            pasteAccepted = pasteAccepted,
        )
}

/** Native acquisition callbacks in the prototype command surface; no picker or permission owner is replaced. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
@Suppress("LongParameterList", "LongMethod")
internal fun ComposerAttachmentMenu(
    anchorBounds: IntRect,
    expanded: Boolean,
    onDismiss: () -> Unit,
    onCamera: (() -> Unit)?,
    onGallery: (() -> Unit)?,
    onFiles: (() -> Unit)?,
    onLocation: (() -> Unit)?,
    onUser: (() -> Unit)?,
    onContact: (() -> Unit)?,
    onPoll: (() -> Unit)? = null,
    onSticker: (() -> Unit)? = null,
    textShortcuts: ComposerMenuTextShortcuts = ComposerMenuTextShortcuts(),
    onCustomize: (() -> Unit)? = null,
) {
    val items =
        listOfNotNull(
            onCamera?.let { WhiteNoiseMenuItem(stringResource(R.string.attachment_camera), it, R.drawable.ic_camera) },
            onGallery?.let {
                WhiteNoiseMenuItem(stringResource(R.string.attachment_photos_videos), it, R.drawable.ic_image)
            },
            onFiles?.let { WhiteNoiseMenuItem(stringResource(R.string.download_files), it, R.drawable.ic_description) },
            onLocation?.let {
                WhiteNoiseMenuItem(stringResource(R.string.attach_location), it, R.drawable.ic_location_on)
            },
            onUser?.let { WhiteNoiseMenuItem(stringResource(R.string.attach_contact), it, R.drawable.ic_person) },
            onContact?.let {
                WhiteNoiseMenuItem(stringResource(R.string.attachment_device_contact), it, R.drawable.ic_person)
            },
            onPoll?.let { WhiteNoiseMenuItem(stringResource(R.string.poll_create), it, R.drawable.ic_description) },
            onSticker?.let { WhiteNoiseMenuItem(stringResource(R.string.stickers_title), it, R.drawable.ic_sticker) },
        ) + composerMenuTextItems(textShortcuts, anchorBounds)
    val density = LocalDensity.current
    val position =
        remember(anchorBounds, density) {
            ComposerAttachmentMenuPositionProvider(
                sourceBounds = anchorBounds,
                gapPx = with(density) { 10.dp.roundToPx() },
                edgePx = with(density) { 8.dp.roundToPx() },
            )
        }
    KeyboardSafePopup(expanded, onDismiss, position) {
        BoxWithConstraints {
            val menuMaxHeight =
                with(density) {
                    position.maxHeight(maxHeight.roundToPx(), 48.dp.roundToPx()).toDp()
                }
            // A popup hands its content the window's width, and the Expressive menu rows fill
            // whatever they are given, so without this the menu ran edge to edge. The prototype's
            // menu popup sizes to its widest label; wrapping to the intrinsic width matches it.
            DropdownMenuGroup(
                shapes = MenuDefaults.groupShapes(),
                border = amoledOutlineBorder(),
                shadowElevation = MenuDefaults.ShadowElevation,
                modifier =
                    Modifier
                        .boundedIntrinsicWidth(IntrinsicSize.Max)
                        .heightIn(max = menuMaxHeight)
                        .testTag("conversation.attachment.menu"),
            ) {
                // Holding any row opens composer customization, matching a long press on the toolbar.
                Column(
                    Modifier
                        .composerCustomizationEntry(
                            enabled = onCustomize != null,
                            onCustomize =
                                onCustomize?.let { customize ->
                                    {
                                        onDismiss()
                                        customize()
                                    }
                                },
                        ).fadingVerticalScroll(rememberScrollState()),
                ) {
                    items.forEachIndexed { index, item ->
                        DropdownMenuItem(
                            text = { Text(item.label) },
                            leadingIcon = {
                                item.icon?.let { Icon(painterResource(it), null, Modifier.size(24.dp)) }
                            },
                            shape = MenuDefaults.itemShape(index, items.size).shape,
                            colors = MenuDefaults.selectableItemColors(),
                            onClick = {
                                onDismiss()
                                item.onClick()
                            },
                            modifier = item.modifier,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun composerMenuTextItems(
    textShortcuts: ComposerMenuTextShortcuts,
    anchorBounds: IntRect,
): List<WhiteNoiseMenuItem> {
    val textToolbar = LocalTextToolbar.current
    val dictationOrigin = rememberMenuDictationOrigin()
    return listOfNotNull(
        textShortcuts.onEmoji?.let { composerMenuShortcutItem(ComposerShortcut.Emoji, onClick = it) },
        textShortcuts.onPaste?.let { paste ->
            composerMenuShortcutItem(ComposerShortcut.Paste) {
                // Denied clipboard reads fall back to the trusted system Paste menu at the Plus button.
                if (!paste()) {
                    textToolbar.showMenu(
                        rect = anchorBounds.toRect(),
                        onPasteRequested = { if (textShortcuts.pasteAccepted()) paste() },
                    )
                }
            }
        },
        textShortcuts.onDictation?.let { dictate ->
            composerMenuShortcutItem(ComposerShortcut.Dictation, dictationOrigin.modifier) {
                dictationOrigin.latch()
                dictate()
            }
        },
        textShortcuts.onTranslate?.let {
            composerMenuShortcutItem(
                ComposerShortcut.Translate,
                Modifier.semantics { selected = textShortcuts.translateActive },
                onClick = it,
            )
        },
    )
}

/**
 * The menu row lives in a popup window and is gone before dictation starts, so its bounds are
 * converted into the host window and latched for the dictation bubble's entrance.
 */
private class MenuDictationOrigin(
    private val hostView: View,
    private val origin: DictationLauncherOrigin?,
    private val enabled: Boolean,
) {
    private var screenBounds: Rect? = null
    val modifier: Modifier =
        Modifier.onGloballyPositioned { screenBounds = Rect(it.positionOnScreen(), it.size.toSize()) }

    fun latch() {
        val bounds = screenBounds ?: return
        if (!enabled || origin == null) return
        val onScreen = IntArray(2).also(hostView::getLocationOnScreen)
        val inWindow = IntArray(2).also(hostView::getLocationInWindow)
        val windowOrigin = Offset((onScreen[0] - inWindow[0]).toFloat(), (onScreen[1] - inWindow[1]).toFloat())
        origin.launchFrom(bounds.translate(-windowOrigin))
    }
}

@Composable
private fun rememberMenuDictationOrigin(): MenuDictationOrigin {
    val hostView = LocalView.current
    val origin = LocalDictationLauncherOrigin.current
    val enabled = LocalControlHandoffEndpointCurrent.current
    return remember(hostView, origin, enabled) { MenuDictationOrigin(hostView, origin, enabled) }
}

@Composable
private fun composerMenuShortcutItem(
    shortcut: ComposerShortcut,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) = WhiteNoiseMenuItem(
    stringResource(shortcut.labelRes),
    onClick,
    shortcut.iconRes,
    modifier = modifier.testTag("conversation.attachment.menu.${shortcut.id}"),
)
