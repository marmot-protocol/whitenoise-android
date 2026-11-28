package dev.ipf.whitenoise.android.ui.common

import androidx.annotation.DrawableRes
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import dev.ipf.whitenoise.android.R
import dev.ipf.whitenoise.android.ui.EmojiLabel
import dev.ipf.whitenoise.android.ui.design.AnimatedDropdownMenu
import dev.ipf.whitenoise.android.ui.testing.exposePerformanceTestTags
import dev.ipf.whitenoise.android.ui.theme.amoledOutlineBorder

/**
 * App dropdown menu on the prototype's surface: low container, AMOLED outline, one row per item. Call beside its
 * trigger inside the anchor's Box, so a toolbar menu anchors to its button (docs/ui-patterns.md, toolbar menus).
 * An item with a non-null [WhiteNoiseMenuItem.selected] is a choice: it carries radio semantics and the chosen row
 * shows a trailing check, so a value menu such as playback speed always shows the current value. [header] sits above
 * the rows, and a caller that swaps the rows for an inline editor passes the editor there with no items.
 * [keyboardPreserving] keeps the composer's keyboard up while the menu is open, as the composer's own menus need.
 * Material 1.5.0-alpha25's grouped Expressive menu replaces the body later.
 */
@Suppress("FunctionNaming")
@Composable
fun WhiteNoiseDropdownMenu(
    expanded: Boolean,
    onDismissRequest: () -> Unit,
    items: List<WhiteNoiseMenuItem>,
    modifier: Modifier = Modifier,
    header: (@Composable () -> Unit)? = null,
    keyboardPreserving: Boolean = false,
) {
    val menuScrollState = rememberScrollState()
    AnimatedDropdownMenu(
        scrollState = menuScrollState,
        expanded = expanded,
        onDismissRequest = onDismissRequest,
        modifier = modifier.exposePerformanceTestTags().scrollEdgeFade(menuScrollState, stableRenderTarget = true),
        shape = MaterialTheme.shapes.medium,
        containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
        border = amoledOutlineBorder(),
        keyboardPreserving = keyboardPreserving,
    ) { surface ->
        header?.invoke()
        items.forEach { item ->
            val contentColor =
                if (item.destructive) {
                    MaterialTheme.colorScheme.error
                } else {
                    MaterialTheme.colorScheme.onSurface
                }
            val (originModifier, activate) =
                rememberMeasuredControlClick { origin ->
                    if (surface.acceptsInput) {
                        item.onOrigin?.invoke(origin)
                        if (item.closesMenu) {
                            surface.close {
                                onDismissRequest()
                                item.onClick()
                            }
                        } else {
                            item.onClick()
                        }
                    }
                }
            WhiteNoiseDropdownMenuRow(item, contentColor, activate, originModifier)
        }
    }
}

/** One menu row: the label, an optional leading icon, and a trailing check on the chosen value of a choice menu. */
@Suppress("FunctionNaming")
@Composable
private fun WhiteNoiseDropdownMenuRow(
    item: WhiteNoiseMenuItem,
    contentColor: Color,
    activate: () -> Unit,
    originModifier: Modifier,
) {
    DropdownMenuItem(
        text = { if (item.emojiLabel) EmojiLabel(item.label) else Text(item.label) },
        onClick = activate,
        modifier = item.modifier.then(originModifier).then(choiceSemantics(item.selected)),
        leadingIcon =
            item.icon?.let { icon ->
                {
                    Icon(
                        painterResource(icon),
                        contentDescription = null,
                        modifier = Modifier.size(MenuIconSize),
                    )
                }
            },
        trailingIcon =
            if (item.selected == true) {
                {
                    // Material's item colours leave the trailing icon untinted, so the check takes the row's colour.
                    Icon(
                        painterResource(R.drawable.ic_check),
                        contentDescription = null,
                        modifier = Modifier.size(MenuIconSize),
                        tint = contentColor,
                    )
                }
            } else {
                null
            },
        enabled = item.enabled,
        colors = MenuDefaults.itemColors(textColor = contentColor, leadingIconColor = contentColor),
    )
}

/** A choice row reads as one radio option of the menu, so TalkBack announces which value is current. */
private fun choiceSemantics(selected: Boolean?): Modifier =
    if (selected == null) {
        Modifier
    } else {
        Modifier.semantics {
            this.selected = selected
            role = Role.RadioButton
        }
    }

/**
 * Null selection denotes a command; non-null selection denotes a mutually exclusive choice. [destructive] draws the
 * row in the error colour. [emojiLabel] draws custom emoji artwork in a label that quotes a chat or person name.
 * [closesMenu] false keeps the menu open, for a row that swaps the menu's content, such as a custom value editor.
 */
data class WhiteNoiseMenuItem(
    val label: String,
    val onClick: () -> Unit,
    @param:DrawableRes val icon: Int? = null,
    val selected: Boolean? = null,
    val enabled: Boolean = true,
    val destructive: Boolean = false,
    val modifier: Modifier = Modifier,
    val onOrigin: ((Rect?) -> Unit)? = null,
    val emojiLabel: Boolean = false,
    val closesMenu: Boolean = true,
)

private val MenuIconSize = 24.dp
