// The file names its primary composable; its row/action model stays colocated.
@file:Suppress("MatchingDeclarationName")

package dev.ipf.whitenoise.android.ui.chats.newchat

import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.ipf.whitenoise.android.R
import dev.ipf.whitenoise.android.core.RecipientSearch
import dev.ipf.whitenoise.android.ui.common.Avatar
import dev.ipf.whitenoise.android.ui.common.WhiteNoiseListItemDefaults
import dev.ipf.whitenoise.android.ui.theme.amoledSurfaceBorder

/** Current native person reference plus sanitized display data; this is not a discovery cache. */
internal data class GroupCreationPerson(
    val candidate: RecipientSearch.Candidate,
    val subtitle: String?,
    val avatarUrl: String?,
)

/** Native checked rows preserve long-press profile preview; setup rows are full-contrast and read-only. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
@Suppress("FunctionNaming") // Compose naming follows the framework convention.
internal fun GroupCreationPersonRow(
    person: GroupCreationPerson,
    index: Int,
    count: Int,
    selected: Boolean?,
    enabled: Boolean = true,
    onClick: (() -> Unit)? = null,
    onLongClick: (() -> Unit)? = null,
) {
    val candidate = person.candidate
    val shapes = WhiteNoiseListItemDefaults.segmentedShapes(index, count)
    val followed = if (candidate.isFollowing) stringResource(R.string.user_search_you_follow) else null
    val modifier =
        Modifier
            .fillMaxWidth()
            .amoledSurfaceBorder(shapes.shape)
            .testTag("new_group.person.${candidate.accountIdHex}")
            .recipientRelationshipSemantics(followed, selected)
    val leading: @Composable () -> Unit = {
        Avatar(candidate.displayName, candidate.accountIdHex, 48.dp, pictureUrl = person.avatarUrl)
    }
    val supporting: (@Composable () -> Unit)? =
        person.subtitle?.let { subtitle ->
            { Text(subtitle, maxLines = 1, overflow = TextOverflow.Ellipsis) }
        }
    val headline: @Composable () -> Unit = {
        Text(candidate.displayName, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
        if (onClick == null) {
            Surface(shape = shapes.shape, color = MaterialTheme.colorScheme.surfaceContainerLowest) {
                ListItem(
                    headlineContent = headline,
                    supportingContent = supporting,
                    leadingContent = leading,
                    colors = ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.surfaceContainerLowest),
                    modifier = modifier,
                )
            }
        } else {
            ListItem(
                onClick = onClick,
                onLongClick = onLongClick,
                enabled = enabled,
                shapes = shapes,
                colors = ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.surfaceContainerLowest),
                modifier = modifier.semantics { role = Role.Checkbox },
                leadingContent = leading,
                supportingContent = supporting,
                trailingContent = {
                    Checkbox(selected == true, null, enabled = enabled, modifier = Modifier.clearAndSetSemantics {})
                },
                content = headline,
            )
        }
        if (index < count - 1) {
            HorizontalDivider(
                Modifier.padding(horizontal = 16.dp),
                color = MaterialTheme.colorScheme.surfaceContainerLow,
            )
        }
    }
}

/** Prototype 80dp selected-person chip; the complete chip is an accessible remove action. */
@Composable
@Suppress("FunctionNaming") // Compose naming follows the framework convention.
internal fun GroupSelectedPerson(
    person: GroupCreationPerson,
    onRemove: () -> Unit,
) {
    val candidate = person.candidate
    val interaction = remember(candidate.accountIdHex) { MutableInteractionSource() }
    val remove = stringResource(R.string.remove_member_named, candidate.displayName)
    Column(
        Modifier
            .width(80.dp)
            .clickable(interaction, null, role = Role.Button, onClick = onRemove)
            .semantics { contentDescription = remove }
            .testTag("new_group.selected.${candidate.accountIdHex}"),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Box(Modifier.size(72.dp)) {
            Box(Modifier.align(Alignment.BottomStart)) {
                Avatar(candidate.displayName, candidate.accountIdHex, 64.dp, pictureUrl = person.avatarUrl)
            }
            Surface(
                Modifier.size(24.dp).align(Alignment.TopEnd),
                shape = CircleShape,
                color = MaterialTheme.colorScheme.primary,
                contentColor = MaterialTheme.colorScheme.onPrimary,
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(painterResource(R.drawable.ic_close), null, Modifier.size(16.dp))
                }
            }
        }
        Text(
            candidate.displayName,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            style = MaterialTheme.typography.labelMedium,
            textAlign = TextAlign.Center,
        )
    }
}
