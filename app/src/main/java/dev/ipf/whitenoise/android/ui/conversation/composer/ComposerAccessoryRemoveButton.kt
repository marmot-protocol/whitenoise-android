@file:Suppress("FunctionNaming")

package dev.ipf.whitenoise.android.ui.conversation.composer

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.indication
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import dev.ipf.whitenoise.android.R

/** Keeps accessory dismissal easy to hit while drawing the compact corner control. */
@Composable
internal fun ComposerAccessoryRemoveButton(
    onClick: () -> Unit,
    description: String,
    modifier: Modifier = Modifier,
    highContrast: Boolean = false,
    enabled: Boolean = true,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val background =
        if (highContrast) {
            MaterialTheme.colorScheme.inverseSurface.copy(alpha = 0.88f)
        } else {
            MaterialTheme.colorScheme.surfaceContainerHighest
        }
    val content =
        if (highContrast) {
            MaterialTheme.colorScheme.inverseOnSurface
        } else {
            MaterialTheme.colorScheme.onSurfaceVariant
        }
    Box(
        modifier =
            modifier
                .size(48.dp)
                .clickable(
                    interactionSource = interactionSource,
                    indication = null,
                    role = Role.Button,
                    enabled = enabled,
                    onClick = onClick,
                ).semantics { contentDescription = description }
                .testTag("conversation.composer.remove.target"),
        contentAlignment = Alignment.TopEnd,
    ) {
        Box(
            modifier =
                Modifier
                    // The Edit target sits below the resize strip; center its painted X on the label.
                    .padding(top = 2.dp, end = 6.dp)
                    .size(20.dp)
                    .clip(CircleShape)
                    .background(background)
                    .indication(interactionSource, ripple(radius = 10.dp))
                    .testTag("conversation.composer.remove.visual"),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                painter = painterResource(R.drawable.ic_close),
                contentDescription = null,
                modifier = Modifier.size(12.dp),
                tint = content,
            )
        }
    }
}
