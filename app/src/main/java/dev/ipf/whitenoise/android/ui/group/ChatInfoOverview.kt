package dev.ipf.whitenoise.android.ui.group

import androidx.annotation.DrawableRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import dev.ipf.whitenoise.android.R
import dev.ipf.whitenoise.android.ui.common.WhiteNoiseListItemDefaults
import dev.ipf.whitenoise.android.ui.settings.SettingsRowContext
import dev.ipf.whitenoise.android.ui.settings.rememberSettingsNativeRow
import dev.ipf.whitenoise.android.ui.theme.ConnectedRowShape
import dev.ipf.whitenoise.android.ui.theme.amoledOutlineBorder
import dev.ipf.whitenoise.android.ui.theme.connectedRowBorder

/** Equal-width overview action with one accessible tonal target and a wrapping visual caption. */
@Suppress("FunctionNaming", "LongParameterList")
@Composable
internal fun QuickInfoAction(
    label: String,
    @DrawableRes icon: Int,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    state: String? = null,
    enabled: Boolean = true,
    buttonModifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        FilledTonalIconButton(
            onClick = onClick,
            enabled = enabled,
            modifier =
                buttonModifier.fillMaxWidth().height(56.dp).semantics {
                    state?.let { stateDescription = it }
                },
        ) {
            Icon(painterResource(icon), contentDescription = label, modifier = Modifier.size(24.dp))
        }
        Text(
            text = label,
            modifier = Modifier.clearAndSetSemantics {},
            textAlign = TextAlign.Center,
            style = MaterialTheme.typography.labelLarge,
        )
    }
}

/** Segmented member presentation around the existing identity, copy and role-mutation controls. */
@Suppress("FunctionNaming")
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ChatInfoMemberCard(
    index: Int,
    count: Int,
    modifier: Modifier = Modifier,
    suppressChrome: () -> Boolean = { false },
    nativeGroup: Any? = null,
    nativeKey: String = index.toString(),
    content: @Composable () -> Unit,
) {
    val shapes = WhiteNoiseListItemDefaults.segmentedShapes(index, count)
    val shape = shapes.shape
    val border = amoledOutlineBorder()
    val nativeRow =
        if (nativeGroup != null) {
            rememberSettingsNativeRow(
                nativeGroup,
                nativeKey,
                SettingsRowContext(
                    shapes,
                    MaterialTheme.colorScheme.surfaceContainerLowest,
                    border?.let { Color.White } ?: Color.Unspecified,
                ),
            )
        } else {
            Modifier
        }
    Surface(
        modifier =
            modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
                .padding(
                    bottom = if (index == count - 1) 0.dp else WhiteNoiseListItemDefaults.segmentedGap,
                ).then(nativeRow)
                .then(
                    if (shape is ConnectedRowShape && border != null) {
                        Modifier.connectedRowBorder(
                            shape,
                            Color.White,
                            suppressChrome = suppressChrome,
                            suppressSeam = { false },
                        )
                    } else {
                        Modifier
                    },
                ),
        shape = shape,
        color = MaterialTheme.colorScheme.surfaceContainerLowest,
        border = if (shape is ConnectedRowShape) null else border,
    ) {
        Box(Modifier.heightIn(min = MemberRowMinimumHeight)) { content() }
    }
}

/**
 * Trailing glyph for a chat-info row that opens another destination. Terminal actions such as
 * Archive and Leave deliberately omit it, so the chevron alone signals "this navigates forward".
 */
@Suppress("FunctionNaming")
@Composable
internal fun ChatInfoRowChevron() {
    Icon(
        painter = painterResource(R.drawable.ic_chevron_right),
        contentDescription = null,
        tint = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

/**
 * Supporting line under a member's name. A roster mutation in flight replaces the role with the
 * updating notice, so the row reports progress in text rather than swapping its trailing glyph.
 */
@Composable
internal fun memberRoleLabel(
    isAdmin: Boolean,
    updating: Boolean,
): String =
    stringResource(
        when {
            updating -> R.string.group_member_updating
            isAdmin -> R.string.admin
            else -> R.string.member
        },
    )

/** Material's two-line list item height, which the prototype's member rows use. */
private val MemberRowMinimumHeight = 72.dp
