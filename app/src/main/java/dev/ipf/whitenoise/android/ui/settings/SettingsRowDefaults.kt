@file:OptIn(ExperimentalMaterial3ExpressiveApi::class)

package dev.ipf.whitenoise.android.ui.settings

import androidx.compose.foundation.layout.size
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.dp
import dev.ipf.whitenoise.android.ui.theme.ConnectedRowShape
import dev.ipf.whitenoise.android.ui.theme.connectedRowBorder

internal object SettingsRowDefaults {
    /** Material's disabled content alpha, shared by blocked row text and the AMOLED row edge. */
    const val DisabledAlpha = 0.38f

    /** The existing settings rows size their busy indicator like the switch it replaces. */
    val BusyIndicatorSize = 24.dp

    /** AMOLED has no tonal surfaces, so a selected choice uses a faint content-colour wash instead. */
    const val AmoledSelectionAlpha = 0.16f
}

/** Titles dim to Material's disabled alpha whenever the row rejects activation. */
@Suppress("FunctionNaming")
@Composable
internal fun SettingsRowTitle(
    title: String,
    editable: Boolean,
) {
    Text(title, color = MaterialTheme.colorScheme.onSurface.dimmedUnless(editable))
}

/** Supporting values wrap onto as many lines as they need instead of truncating. */
@Suppress("FunctionNaming")
@Composable
internal fun SettingsRowSupportingText(
    text: String,
    editable: Boolean,
) {
    Text(text, color = MaterialTheme.colorScheme.onSurfaceVariant.dimmedUnless(editable))
}

/** A busy row shows the existing Material indicator in place of its decorative trailing control. */
@Suppress("FunctionNaming")
@Composable
internal fun SettingsRowTrailing(
    busy: Boolean,
    control: @Composable () -> Unit,
) {
    if (busy) {
        LoadingIndicator(
            modifier = Modifier.size(SettingsRowDefaults.BusyIndicatorSize).clearAndSetSemantics {},
        )
    } else {
        control()
    }
}

/** Blocked rows scale the token's own alpha by the disabled alpha, enabled rows keep the token untouched. */
internal fun Color.dimmedUnless(editable: Boolean): Color =
    if (editable) {
        this
    } else {
        copy(alpha = alpha * SettingsRowDefaults.DisabledAlpha)
    }

/** Each AMOLED row owns its side edges and the preceding row owns the single-pixel shared divider. */
internal fun Modifier.settingsRowBorder(
    context: SettingsRowContext,
    editable: Boolean,
): Modifier {
    val shape = context.shapes.shape
    return if (shape is ConnectedRowShape) {
        connectedRowBorder(shape, context.borderColor.dimmedUnless(editable))
    } else {
        this
    }
}
