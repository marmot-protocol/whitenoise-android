package dev.ipf.whitenoise.android.ui.chats.newchat

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.ipf.whitenoise.android.R
import dev.ipf.whitenoise.android.core.AvatarImageLoader
import dev.ipf.whitenoise.android.core.IdentityFormatter
import dev.ipf.whitenoise.android.core.RecipientSearch
import dev.ipf.whitenoise.android.state.WhiteNoiseAppState
import dev.ipf.whitenoise.android.ui.common.Avatar

/** One compact member row; the admin switch trails it, or drops below when text is large or space is narrow. */
@Composable
@Suppress("FunctionNaming")
internal fun GroupMemberReviewRow(
    appState: WhiteNoiseAppState,
    member: RecipientSearch.Candidate,
    busy: Boolean,
    initialAdminIds: Set<String>,
    onInitialAdminChange: ((RecipientSearch.Candidate, Boolean) -> Unit)?,
) {
    val person = rememberGroupReviewPerson(appState, member)
    val label =
        person.publicName
            ?: IdentityFormatter.short(person.identifier, prefix = 10, suffix = 8).ifBlank {
                stringResource(R.string.unknown)
            }
    val admin = initialAdminIds.any { it.equals(member.accountIdHex, ignoreCase = true) }
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val stacked =
            LocalDensity.current.fontScale > GROUP_REVIEW_LARGE_FONT_SCALE ||
                MaterialTheme.typography.bodyMedium.fontSize >= 20.sp ||
                maxWidth < GroupReviewPairedMinWidth
        val identity: @Composable (Modifier) -> Unit = { modifier ->
            Row(
                modifier.testTag("group.members.review.member"),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Avatar(
                    person.publicName ?: person.identifier,
                    member.accountIdHex,
                    36.dp,
                    pictureUrl = person.picture,
                    pictureDomain = AvatarImageLoader.publicProfiles(),
                )
                Column(Modifier.weight(1f)) { GroupReviewPersonText(person, stringResource(R.string.member)) }
            }
        }
        val change = onInitialAdminChange
        when {
            change == null -> identity(Modifier.fillMaxWidth())
            stacked ->
                Column {
                    identity(Modifier.fillMaxWidth())
                    SelectedMemberRoleControl(label, admin, !busy) { change(member, it) }
                }
            else ->
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    identity(Modifier.weight(1f))
                    GroupReviewAdminSwitch(label, admin, !busy) { change(member, it) }
                }
        }
    }
}

/** Trailing admin switch with a full-height toggle target and the same spoken label as the stacked row. */
@Composable
@Suppress("FunctionNaming")
private fun GroupReviewAdminSwitch(
    name: String,
    admin: Boolean,
    enabled: Boolean,
    onChange: (Boolean) -> Unit,
) {
    val adminLabel = stringResource(R.string.admin)
    Row(
        Modifier
            .heightIn(min = 48.dp)
            .toggleable(value = admin, enabled = enabled, role = Role.Switch, onValueChange = onChange)
            .padding(start = 8.dp)
            .semantics { contentDescription = "$adminLabel, $name" },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(adminLabel, style = MaterialTheme.typography.labelMedium)
        Switch(checked = admin, onCheckedChange = null, enabled = enabled)
    }
}
