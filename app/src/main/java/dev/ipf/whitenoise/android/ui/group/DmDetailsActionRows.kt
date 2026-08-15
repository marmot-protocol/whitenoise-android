@file:Suppress("FunctionNaming") // Jetpack Compose functions use UpperCamelCase.

package dev.ipf.whitenoise.android.ui.group

import androidx.compose.foundation.layout.Column
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Group
import androidx.compose.material.icons.filled.PersonAdd
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import dev.ipf.whitenoise.android.ui.chats.newchat.SettingsActionRow

@Composable
internal fun DmDetailsContactActionRows(
    createGroupTitle: String,
    addToGroupTitle: String,
    addingContactToGroups: Boolean,
    onCreateGroup: () -> Unit,
    onAddToGroup: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier) {
        SettingsActionRow(
            icon = Icons.Default.Group,
            title = createGroupTitle,
            onClick = onCreateGroup,
        )
        SettingsActionRow(
            icon = Icons.Default.PersonAdd,
            title = addToGroupTitle,
            enabled = !addingContactToGroups,
            inProgress = addingContactToGroups,
            onClick = onAddToGroup,
        )
    }
}
