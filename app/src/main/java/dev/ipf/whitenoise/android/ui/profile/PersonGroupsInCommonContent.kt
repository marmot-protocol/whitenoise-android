// The file names its primary composable; its row/action model stays colocated.
@file:Suppress("MatchingDeclarationName")

package dev.ipf.whitenoise.android.ui.profile

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Group
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import dev.ipf.whitenoise.android.R
import dev.ipf.whitenoise.android.ui.common.Avatar
import dev.ipf.whitenoise.android.ui.settings.SettingsGroup
import dev.ipf.whitenoise.android.ui.settings.SettingsGroupPanel
import dev.ipf.whitenoise.android.ui.settings.SettingsLink
import dev.ipf.whitenoise.android.ui.settings.SettingsScaffold
import dev.ipf.whitenoise.android.ui.theme.WhiteNoiseSpacing

/** Native shared-group projection; no count or avatar is inferred from a prototype fixture. */
internal data class PersonSharedGroupRow(
    val id: String,
    val title: String,
    val memberCount: Int,
)

/** Existing Groups in Common destination, preserving unresolved roster evidence and the existing add-group callback. */
@Suppress("FunctionNaming", "LongMethod", "LongParameterList")
@Composable
internal fun PersonGroupsInCommonContent(
    rows: List<PersonSharedGroupRow>,
    unresolved: Boolean,
    onBack: () -> Unit,
    onOpen: (String) -> Unit,
    onAdd: () -> Unit,
    onRetry: () -> Unit,
    avatar: @Composable (String) -> ImageBitmap? = { null },
) {
    SettingsScaffold(
        title = stringResource(R.string.person_groups_in_common),
        onBack = onBack,
        bottomBar = {
            PersonProfileBottomAction(
                stringResource(
                    if (rows.isEmpty()) R.string.person_add_to_group else R.string.profile_add_to_another_group,
                ),
                onAdd,
                enabled = true,
                groupAction = true,
                modifier = Modifier.testTag("groups_in_common.add"),
            )
        },
    ) {
        LazyColumn(
            Modifier.fillMaxSize().testTag("groups_in_common.list"),
            contentPadding = PaddingValues(vertical = WhiteNoiseSpacing.Section),
        ) {
            item("groups") {
                SettingsGroup {
                    if (rows.isEmpty() && !unresolved) {
                        row("empty") { context ->
                            SettingsGroupPanel(context) {
                                Text(
                                    stringResource(R.string.person_no_groups_in_common),
                                    Modifier.padding(16.dp),
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                    rows.forEach { group ->
                        row(group.id) { context ->
                            SettingsLink(
                                context,
                                group.title,
                                onClick = { onOpen(group.id) },
                                subtitle = stringResource(R.string.members_count, group.memberCount),
                                modifier = Modifier.testTag("groups_in_common.group.${group.id}"),
                                leading = { Avatar(group.title, group.id, 48.dp, picture = avatar(group.id)) },
                            )
                        }
                    }
                }
            }
            if (unresolved) {
                item("unresolved") {
                    Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(
                            stringResource(R.string.person_group_memberships_unavailable),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        TextButton(onRetry, Modifier.testTag("groups_in_common.retry")) {
                            Text(stringResource(R.string.retry))
                        }
                    }
                }
            }
        }
    }
}

/** Prototype's compact three-avatar preview plus truthful overflow count; no unknown roster counts are guessed. */
@Suppress("FunctionNaming")
@Composable
internal fun PersonSharedGroupAvatars(
    rows: List<PersonSharedGroupRow>,
    avatar: @Composable (String) -> ImageBitmap? = { null },
) {
    if (rows.isEmpty()) {
        Icon(Icons.Default.Group, null, Modifier.size(24.dp))
        return
    }
    val shown = rows.take(3)
    val remaining = (rows.size - shown.size).coerceAtLeast(0)
    val count = shown.size + if (remaining > 0) 1 else 0
    Box(Modifier.width(32.dp + 22.dp * (count - 1)).height(32.dp)) {
        shown.forEachIndexed { index, row ->
            Surface(
                Modifier.offset(x = 22.dp * index),
                shape = CircleShape,
                color = MaterialTheme.colorScheme.surfaceContainerLow,
            ) {
                Avatar(row.title, row.id, 32.dp, picture = avatar(row.id))
            }
        }
        if (remaining > 0) {
            Surface(
                Modifier.offset(x = 22.dp * shown.size).size(32.dp),
                shape = CircleShape,
                color = MaterialTheme.colorScheme.surfaceContainerHighest,
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Text("+$remaining", style = MaterialTheme.typography.labelMedium)
                }
            }
        }
    }
}
