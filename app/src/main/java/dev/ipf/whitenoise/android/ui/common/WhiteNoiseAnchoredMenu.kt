@file:Suppress("FunctionNaming") // Composable names follow the framework convention.

package dev.ipf.whitenoise.android.ui.common

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.DropdownMenuGroup
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.DropdownMenuPopup
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuAnchorPosition
import androidx.compose.material3.MenuDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.PopupProperties
import dev.ipf.whitenoise.android.R
import dev.ipf.whitenoise.android.ui.theme.amoledOutlineBorder

/** Expressive anchored commands; optional focus leaves a held native row pointer with its original owner. */
@OptIn(ExperimentalMaterial3Api::class)
@Suppress("LongParameterList")
@Composable
internal fun WhiteNoiseAnchoredMenu(
    expanded: Boolean,
    onDismissRequest: () -> Unit,
    items: List<WhiteNoiseMenuItem>,
    modifier: Modifier = Modifier,
    anchorSpacing: Dp = 0.dp,
    focusable: Boolean = true,
    canRunAction: () -> Boolean = { true },
) {
    BackHandler(enabled = expanded && !focusable, onBack = onDismissRequest)
    DropdownMenuPopup(
        expanded = expanded,
        onDismissRequest = onDismissRequest,
        popupPositionProvider = MenuDefaults.rememberDropdownMenuPopupPositionProvider(MenuAnchorPosition.Below),
        properties = PopupProperties(focusable = focusable),
        modifier = Modifier.padding(vertical = anchorSpacing),
    ) {
        DropdownMenuGroup(
            shapes = MenuDefaults.groupShapes(),
            border = amoledOutlineBorder(),
            modifier = modifier,
            shadowElevation = MenuDefaults.ShadowElevation,
        ) {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                items.forEachIndexed { index, item ->
                    AnchoredMenuItem(item, index, items.size) {
                        val admitted = canRunAction()
                        onDismissRequest()
                        if (admitted) item.onClick()
                    }
                }
            }
        }
    }
}

/** Material owns command/selection semantics, item shapes and disabled states; destructive commands use error color. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AnchoredMenuItem(
    item: WhiteNoiseMenuItem,
    index: Int,
    count: Int,
    onClick: () -> Unit,
) {
    val shapes = MenuDefaults.itemShape(index, count)
    val colors =
        if (item.destructive) {
            MenuDefaults.selectableItemColors(
                textColor = MaterialTheme.colorScheme.error,
                leadingIconColor = MaterialTheme.colorScheme.error,
            )
        } else {
            MenuDefaults.selectableItemColors()
        }
    val icon: (@Composable () -> Unit)? =
        item.icon?.let { resource ->
            { Icon(painterResource(resource), null, Modifier.size(24.dp)) }
        }
    if (item.selected == null) {
        DropdownMenuItem(
            onClick = onClick,
            text = { Text(item.label) },
            shape = shapes.shape,
            modifier = item.modifier,
            leadingIcon = icon,
            enabled = item.enabled,
            colors = colors,
        )
    } else {
        DropdownMenuItem(
            selected = item.selected,
            onClick = onClick,
            text = { Text(item.label) },
            shapes = shapes,
            modifier = item.modifier,
            leadingIcon = icon,
            selectedLeadingIcon = { Icon(painterResource(R.drawable.ic_check), null, Modifier.size(24.dp)) },
            enabled = item.enabled,
            colors = colors,
        )
    }
}
