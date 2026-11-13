package dev.ipf.whitenoise.android.ui.conversation.composer

import androidx.annotation.DrawableRes
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.ipf.whitenoise.android.R
import dev.ipf.whitenoise.android.ui.theme.amoledOutlineBorder

// Material's disabled container and content emphasis, so an inactive Send reads grey rather than accented.
private const val DISABLED_DISC_CONTAINER_ALPHA = 0.12f
private const val DISABLED_DISC_CONTENT_ALPHA = 0.38f

/**
 * The prototype's send disc: a flat 32dp circle centred in the 40dp trailing slot, with the account's action
 * colours (the AMOLED outline theme paints the surface with an outline instead). A disabled disc is grey, and its
 * AMOLED outline dims to the same grey as the icon.
 */
@Composable
@Suppress("FunctionNaming")
internal fun ComposerActionDisc(
    onClick: () -> Unit,
    containerColor: Color,
    contentColor: Color,
    description: String,
    @DrawableRes icon: Int,
    enabled: Boolean = true,
    width: Dp = ComposerToolbarSlotWidth,
    modifier: Modifier = Modifier,
) {
    val outline = amoledOutlineBorder(enabled)
    ComposerToolbarTargets {
        IconButton(onClick = onClick, enabled = enabled, modifier = modifier.width(width).height(48.dp)) {
            Surface(
                modifier = Modifier.size(32.dp),
                shape = CircleShape,
                color =
                    when {
                        outline != null -> MaterialTheme.colorScheme.surface
                        enabled -> containerColor
                        else -> MaterialTheme.colorScheme.onSurface.copy(alpha = DISABLED_DISC_CONTAINER_ALPHA)
                    },
                contentColor =
                    when {
                        !enabled -> MaterialTheme.colorScheme.onSurface.copy(alpha = DISABLED_DISC_CONTENT_ALPHA)
                        outline != null -> MaterialTheme.colorScheme.onSurface
                        else -> contentColor
                    },
                border = outline,
            ) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Icon(
                        painter = painterResource(icon),
                        contentDescription = description,
                        modifier = Modifier.size(20.dp),
                    )
                }
            }
        }
    }
}

private val SendSlotRingSize = 24.dp
private val SendSlotRingStroke = 2.dp

/**
 * Send's place while a translate-mode draft has no ready translation. It keeps the slot's width so neighbouring
 * shortcuts stay put, shows a non-interactive progress ring while [pending], and is empty after a failure or
 * before a target is chosen.
 */
@Composable
@Suppress("FunctionNaming")
internal fun ComposerTranslationSendSlot(
    width: Dp,
    pending: Boolean,
    modifier: Modifier = Modifier,
) {
    Box(modifier.width(width).height(48.dp).testTag("composer.send.slot"), contentAlignment = Alignment.Center) {
        if (pending) {
            val description = stringResource(R.string.translation_loading)
            CircularProgressIndicator(
                modifier =
                    Modifier
                        .size(SendSlotRingSize)
                        .testTag("composer.send.progress")
                        .semantics { contentDescription = description },
                strokeWidth = SendSlotRingStroke,
            )
        }
    }
}
