package dev.ipf.whitenoise.android.ui.conversation.media

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import dev.ipf.whitenoise.android.R
import dev.ipf.whitenoise.android.ui.common.WhiteNoiseMenuItem

/** Stateless menu descriptions and reuse predicates; the viewer frame and page retain their state owners. */
@Composable
internal fun mediaViewerMenuItems(
    onSave: (() -> Unit)?,
    onSetGroupPicture: (() -> Unit)?,
    onGoToMessage: (() -> Unit)?,
    actionsEnabled: Boolean,
    groupPictureBusy: Boolean,
): List<WhiteNoiseMenuItem> =
    buildList {
        if (onSave != null) {
            add(
                WhiteNoiseMenuItem(
                    label = stringResource(R.string.media_save),
                    icon = R.drawable.ic_download,
                    onClick = onSave,
                    enabled = actionsEnabled,
                ),
            )
        }
        if (onSetGroupPicture != null) {
            add(
                WhiteNoiseMenuItem(
                    label = stringResource(R.string.media_set_group_picture),
                    icon = R.drawable.ic_image,
                    onClick = onSetGroupPicture,
                    enabled = actionsEnabled && !groupPictureBusy,
                ),
            )
        }
        if (onGoToMessage != null) {
            add(
                WhiteNoiseMenuItem(
                    label = stringResource(R.string.shared_content_go_to_message),
                    icon = R.drawable.ic_settings_chat_bubble_outline,
                    onClick = onGoToMessage,
                    enabled = actionsEnabled,
                ),
            )
        }
    }

internal fun hasViewerBottomActions(
    onShare: (() -> Unit)?,
    onForwardMessage: (() -> Unit)?,
): Boolean = onShare != null || onForwardMessage != null

internal fun reusableViewerPresentation(
    presentation: DecodedAttachmentPresentation?,
    promoteAnimation: Boolean,
    replaceTerminatedAnimation: Boolean,
    sourcePrepared: Boolean,
): Boolean =
    presentation != null &&
        sourcePrepared &&
        !viewerReplacementNeeded(promoteAnimation, replaceTerminatedAnimation)

private fun viewerReplacementNeeded(
    promoteAnimation: Boolean,
    replaceTerminatedAnimation: Boolean,
): Boolean = promoteAnimation || replaceTerminatedAnimation

internal fun hasViewerOverflowActions(
    onSave: (() -> Unit)?,
    onSetGroupPicture: (() -> Unit)?,
    onGoToMessage: (() -> Unit)?,
): Boolean = onSave != null || onSetGroupPicture != null || onGoToMessage != null
