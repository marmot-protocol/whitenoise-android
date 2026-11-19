package dev.ipf.whitenoise.android.ui.group

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import dev.ipf.whitenoise.android.R
import dev.ipf.whitenoise.android.ui.common.WhiteNoiseDropdownMenu
import dev.ipf.whitenoise.android.ui.common.WhiteNoiseMenuItem

/** The prototype's photo sources behind the Add / Change photo button; Remove joins once the group has an image. */
@Composable
@Suppress("FunctionNaming", "LongParameterList")
internal fun GroupEditPhotoMenu(
    expanded: Boolean,
    hasImage: Boolean,
    onDismiss: () -> Unit,
    onChoosePhoto: () -> Unit,
    onChooseFile: () -> Unit,
    onFindWebImage: () -> Unit,
    onCreateEmoji: () -> Unit,
    onRemove: () -> Unit,
) {
    WhiteNoiseDropdownMenu(
        expanded = expanded,
        onDismissRequest = onDismiss,
        items =
            buildList {
                add(WhiteNoiseMenuItem(stringResource(R.string.choose_photos), onChoosePhoto, R.drawable.ic_image))
                add(WhiteNoiseMenuItem(stringResource(R.string.choose_files), onChooseFile, R.drawable.ic_description))
                add(WhiteNoiseMenuItem(stringResource(R.string.find_web_image), onFindWebImage, R.drawable.ic_search))
                add(
                    WhiteNoiseMenuItem(
                        stringResource(R.string.group_emoji_create),
                        onCreateEmoji,
                        R.drawable.ic_emoji_smileys,
                    ),
                )
                if (hasImage) {
                    add(
                        WhiteNoiseMenuItem(
                            stringResource(R.string.remove_photo),
                            onRemove,
                            icon = R.drawable.ic_delete,
                            destructive = true,
                        ),
                    )
                }
            },
    )
}
