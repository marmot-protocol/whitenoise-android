@file:OptIn(ExperimentalMaterial3ExpressiveApi::class)

package dev.ipf.whitenoise.android.ui.settings

import androidx.annotation.DrawableRes
import androidx.compose.foundation.layout.size
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.SemanticsPropertyKey
import androidx.compose.ui.semantics.SemanticsPropertyReceiver
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import dev.ipf.whitenoise.android.ui.theme.ConnectedRowShape
import dev.ipf.whitenoise.android.ui.theme.amoledRowSeamColor
import dev.ipf.whitenoise.android.ui.theme.connectedRowBorder

internal object SettingsRowDefaults {
    /** Material's disabled content alpha for a blocked row's text and icons — its connected outline never dims. */
    const val DisabledAlpha = 0.38f

    /** The existing settings rows size their busy indicator like the switch it replaces. */
    val BusyIndicatorSize = 24.dp

    /** One leading slot for every row type, matching the trailing control and the Material icon box. */
    val LeadingIconSize = 24.dp

    /** The expressive list item's gap after its leading slot, which rows laid out by hand repeat to stay aligned. */
    val LeadingContentGap = 12.dp

    /** AMOLED has no tonal surfaces, so a selected choice uses a faint content-colour wash instead. */
    const val AmoledSelectionAlpha = 0.16f
}

/** Titles dim to Material's disabled alpha whenever the row rejects activation. */
@Suppress("FunctionNaming")
@Composable
internal fun SettingsRowTitle(
    title: String,
    editable: Boolean,
    destructive: Boolean = false,
) {
    val color = if (destructive) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface
    Text(title, color = color.dimmedUnless(editable))
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
            modifier = Modifier.size(SettingsRowDefaults.BusyIndicatorSize),
        )
    } else {
        control()
    }
}

/** Whether the row hosting a leading slot accepts activation; each settings row provides its own state. */
internal val LocalSettingsRowEditable = compositionLocalOf { true }

/** The drawable a settings row's leading glyph draws, so tests can compare glyphs within a group. */
internal val SettingsLeadingIconKey = SemanticsPropertyKey<Int>("SettingsLeadingIcon")

private var SemanticsPropertyReceiver.settingsLeadingIcon by SettingsLeadingIconKey

/**
 * The decorative glyph in a row's leading slot, at one size and the hosting row's disabled treatment.
 *
 * The title already names the action, so the icon carries no content description and adds nothing for
 * a screen reader to repeat. Direction-dependent drawables declare `autoMirrored` themselves. [tint]
 * defaults to the row's supporting colour; destructive rows pass the error role.
 */
@Suppress("FunctionNaming")
@Composable
internal fun SettingsLeadingIcon(
    @DrawableRes icon: Int,
    modifier: Modifier = Modifier,
    tint: Color = MaterialTheme.colorScheme.onSurfaceVariant,
    testTag: String? = null,
) {
    Icon(
        painter = painterResource(icon),
        contentDescription = null,
        modifier =
            modifier
                .size(SettingsRowDefaults.LeadingIconSize)
                .semantics { settingsLeadingIcon = icon }
                .then(if (testTag == null) Modifier else Modifier.testTag(testTag)),
        tint = tint.dimmedUnless(LocalSettingsRowEditable.current),
    )
}

/** Wraps a row's [leading] slot so its glyph dims with the row; `null` keeps the slot empty. */
internal fun settingsLeadingSlot(
    leading: (@Composable () -> Unit)?,
    editable: Boolean,
): (@Composable () -> Unit)? =
    leading?.let { content ->
        { CompositionLocalProvider(LocalSettingsRowEditable provides editable, content = content) }
    }

/** Blocked rows scale the token's own alpha by the disabled alpha, enabled rows keep the token untouched. */
internal fun Color.dimmedUnless(editable: Boolean): Color =
    if (editable) {
        this
    } else {
        copy(alpha = alpha * SettingsRowDefaults.DisabledAlpha)
    }

/**
 * Each AMOLED row owns its side edges in the group's outline colour and the preceding row owns the
 * single-pixel shared divider, drawn as the dimmed seam so the outer outline stays the only white edge.
 *
 * The outline is group chrome, not row state — a disabled or busy row greys its content but keeps the
 * shared perimeter and seam in the group's colours, so one unavailable action never cuts a grey
 * segment into the box.
 */
internal fun Modifier.settingsRowBorder(context: SettingsRowContext): Modifier {
    val shape = context.shapes.shape
    return if (shape is ConnectedRowShape) {
        val outline = context.borderColor
        connectedRowBorder(shape, outline, seamColor = amoledRowSeamColor(outline))
    } else {
        this
    }
}
