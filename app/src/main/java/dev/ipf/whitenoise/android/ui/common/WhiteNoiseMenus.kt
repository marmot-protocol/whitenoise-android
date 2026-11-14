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
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import dev.ipf.whitenoise.android.ui.design.AnimatedDropdownMenu
import dev.ipf.whitenoise.android.ui.settings.rememberMeasuredControlClick
import dev.ipf.whitenoise.android.ui.testing.exposePerformanceTestTags
import dev.ipf.whitenoise.android.ui.theme.amoledOutlineBorder

/**
 * App dropdown menu on the prototype's surface: low container, AMOLED outline, one row per item. Call beside its
 * trigger inside the anchor's Box. Material 1.5.0-alpha25's grouped Expressive menu replaces the body later.
 */
@Suppress("FunctionNaming")
@Composable
fun WhiteNoiseDropdownMenu(
    expanded: Boolean,
    onDismissRequest: () -> Unit,
    items: List<WhiteNoiseMenuItem>,
    modifier: Modifier = Modifier,
    header: (@Composable () -> Unit)? = null,
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
                        surface.close {
                            onDismissRequest()
                            item.onClick()
                        }
                    }
                }
            DropdownMenuItem(
                text = { Text(item.label) },
                onClick = activate,
                modifier = item.modifier.then(originModifier),
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
                enabled = item.enabled,
                colors = MenuDefaults.itemColors(textColor = contentColor, leadingIconColor = contentColor),
            )
        }
    }
}

/** Null selection denotes a command; non-null selection denotes a mutually exclusive choice. */
data class WhiteNoiseMenuItem(
    val label: String,
    val onClick: () -> Unit,
    @param:DrawableRes val icon: Int? = null,
    val selected: Boolean? = null,
    val enabled: Boolean = true,
    val destructive: Boolean = false,
    val modifier: Modifier = Modifier,
    val onOrigin: ((Rect?) -> Unit)? = null,
)

private val MenuIconSize = 24.dp
