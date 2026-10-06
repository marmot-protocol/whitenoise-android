@file:Suppress("FunctionNaming") // Compose surface names follow the repository convention.

package dev.ipf.whitenoise.android.ui.profile

import androidx.compose.foundation.layout.Column
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import dev.ipf.whitenoise.android.R
import dev.ipf.whitenoise.android.ui.common.Avatar

/** Accessible text actions remain vertically reachable at large font scales in the editor's scroll container. */
@Composable
internal fun ContactPictureControls(
    state: ContactPictureEditorState,
    source: String?,
    title: String,
    onPick: () -> Unit,
    onReposition: () -> Unit,
    onClear: () -> Unit,
) {
    Column {
        Text(stringResource(R.string.contact_private_picture), style = MaterialTheme.typography.titleSmall)
        Avatar(title, source.orEmpty(), 64.dp, source.takeIf { state.preview == null }, state.preview)
        TextButton(onPick, enabled = !state.busy, modifier = Modifier.testTag("contact_picture.pick")) {
            val label = if (state.hasPicture) R.string.contact_picture_replace else R.string.contact_picture_pick
            Text(stringResource(label))
        }
        if (state.hasPicture) {
            TextButton(onReposition, enabled = !state.busy, modifier = Modifier.testTag("contact_picture.crop")) {
                Text(stringResource(R.string.contact_picture_reposition))
            }
            TextButton(onClear, enabled = !state.busy, modifier = Modifier.testTag("contact_picture.clear")) {
                Text(stringResource(R.string.contact_picture_clear))
            }
        }
        Text(stringResource(R.string.contact_picture_local_only), style = MaterialTheme.typography.bodySmall)
        if (state.failed) Text(stringResource(R.string.contact_picture_failed), color = MaterialTheme.colorScheme.error)
    }
}

/** A realistic private identity editor for design inspection without storage or picker access. */
@androidx.compose.ui.tooling.preview.Preview
@Composable
private fun ContactPictureEditorPreview() {
    dev.ipf.whitenoise.android.ui.theme.WhiteNoiseTheme {
        ContactPrivateDetailsDialog(
            "Maya Chen",
            "Maya",
            "Met at the Android meetup",
            {},
            { _, _ -> },
            pictureState = ContactPictureEditorState(),
        )
    }
}
