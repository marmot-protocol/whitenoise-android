package dev.ipf.whitenoise.android.ui.chats.newchat

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import dev.ipf.whitenoise.android.R
import dev.ipf.whitenoise.android.ui.common.WhiteNoiseDropdownMenu
import dev.ipf.whitenoise.android.ui.common.WhiteNoiseMenuItem

/** Existing validated media entry points surfaced in the prototype photo menu. */
@Composable
@Suppress("FunctionNaming") // Compose naming follows the framework convention.
internal fun NewGroupPhotoMenu(
    expanded: Boolean,
    hasImage: Boolean,
    onDismiss: () -> Unit,
    onPhotos: () -> Unit,
    onFiles: () -> Unit,
    onWeb: () -> Unit,
    onEmoji: () -> Unit,
    onRemove: () -> Unit,
) {
    WhiteNoiseDropdownMenu(
        expanded,
        onDismiss,
        items =
            buildList {
                add(WhiteNoiseMenuItem(stringResource(R.string.group_photo_photos), onPhotos, R.drawable.ic_image))
                add(WhiteNoiseMenuItem(stringResource(R.string.group_photo_files), onFiles, R.drawable.ic_description))
                add(WhiteNoiseMenuItem(stringResource(R.string.group_photo_web), onWeb, R.drawable.ic_search))
                add(WhiteNoiseMenuItem(stringResource(R.string.group_photo_emoji), onEmoji, R.drawable.ic_add))
                if (hasImage) {
                    add(
                        WhiteNoiseMenuItem(
                            stringResource(R.string.group_remove_photo),
                            onRemove,
                            R.drawable.ic_delete,
                            destructive = true,
                        ),
                    )
                }
            },
    )
}
