package dev.ipf.whitenoise.android.ui.account

import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.indication
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.PlainTooltip
import androidx.compose.material3.Text
import androidx.compose.material3.TooltipBox
import androidx.compose.material3.TooltipDefaults
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.material3.rememberTooltipState
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import dev.ipf.whitenoise.android.R
import dev.ipf.whitenoise.android.ui.theme.amoledOutlineBorder

/** Pinned prototype 20 dp arrows/30 dp state layer, with a separate native 48 dp accessible click target. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
@Suppress("FunctionNaming") // Compose naming follows the framework convention.
internal fun QuickProfileCycleButton(
    nextTitle: String,
    onClick: () -> Unit,
) {
    val description = stringResource(R.string.quick_account_switch_to, nextTitle)
    val interactions = remember { MutableInteractionSource() }
    val outline = amoledOutlineBorder()
    TooltipBox(
        positionProvider = TooltipDefaults.rememberTooltipPositionProvider(),
        tooltip = { PlainTooltip { Text(description) } },
        state = rememberTooltipState(),
    ) {
        Box(
            modifier =
                Modifier
                    .minimumInteractiveComponentSize()
                    .size(48.dp)
                    .testTag("chats.quickSwitch")
                    .clickable(
                        interactionSource = interactions,
                        indication = null,
                        role = Role.Button,
                        onClick = onClick,
                    ),
            contentAlignment = Alignment.Center,
        ) {
            Box(
                modifier =
                    Modifier
                        .offset(x = (-4).dp)
                        .size(30.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.surfaceContainerHighest, CircleShape)
                        .then(if (outline == null) Modifier else Modifier.border(outline, CircleShape))
                        .indication(interactions, ripple(bounded = false, radius = 15.dp)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    painter = painterResource(R.drawable.ic_swap_vert),
                    contentDescription = description,
                    modifier = Modifier.size(20.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/** Replaces the last cycle toast; a request or failed activation never calls this completion-only surface. */
internal class QuickProfileCycleNotice(
    private val context: Context,
) {
    private var toast: Toast? = null

    /** Emits configuration-aware confirmation only for the native callback’s verified destination. */
    fun show(actualTitle: String) {
        clear()
        toast =
            Toast
                .makeText(
                    context,
                    context.getString(R.string.quick_account_switched, actualTitle),
                    Toast.LENGTH_SHORT,
                ).also(Toast::show)
    }

    /** Cancel any prior toast so repeated cycles cannot queue obsolete identities. */
    fun clear() {
        toast?.cancel()
        toast = null
    }
}

/** Keeps configuration-aware native copy and cancels its short notice when the owning screen disappears. */
@Composable
internal fun rememberQuickProfileCycleNotice(): QuickProfileCycleNotice {
    val context = LocalContext.current
    val notice = remember(context) { QuickProfileCycleNotice(context) }
    DisposableEffect(notice) { onDispose(notice::clear) }
    return notice
}
