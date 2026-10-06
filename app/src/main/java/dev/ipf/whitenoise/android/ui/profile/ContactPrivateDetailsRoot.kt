@file:Suppress("FunctionNaming") // Compose surface names follow the repository convention.

package dev.ipf.whitenoise.android.ui.profile

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.window.SecureFlagPolicy
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.ipf.whitenoise.android.media.IdentityImageCropShape
import dev.ipf.whitenoise.android.state.WhiteNoiseAppState
import dev.ipf.whitenoise.android.ui.common.IdentityImageCropDialog

/** Android picker/lifecycle adapter around the existing private-details editor and pure picture controls. */
@Composable
internal fun ContactPrivateDetailsRoot(
    appState: WhiteNoiseAppState,
    account: String,
    contact: String,
    profileName: String,
    nickname: String,
    notes: String,
    ownerIsCurrent: () -> Boolean,
    onDismiss: () -> Unit,
    securePolicy: SecureFlagPolicy,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val controller =
        remember(appState, account, contact) {
            ContactPictureEditorController(
                appState.contactPictureStore,
                appState.contactPictureStore.reference(account, contact),
                scope,
                ownerIsCurrent,
            )
        }
    DisposableEffect(controller) { onDispose { controller.dispose() } }
    val state by controller.state.collectAsStateWithLifecycle()
    val picker =
        rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
            if (uri != null) controller.pick(context.contentResolver, uri)
        }
    ContactPrivateDetailsDialog(
        profileName,
        nickname,
        notes,
        onDismiss = { if (!state.busy) controller.dismiss(onDismiss) },
        onSave = { editedNickname, editedNotes ->
            controller.save(
                commit = { picture, current ->
                    appState.saveContactPrivateDetails(account, contact, editedNickname, editedNotes, picture, current)
                },
                onSaved = { controller.dismiss(onDismiss) },
            )
        },
        securePolicy = securePolicy,
        pictureState = state,
        pictureSeed = contact,
        pictureSource =
            if (state.hasPicture) {
                appState.contactAvatarSource(contact, account)
            } else {
                appState.avatarUrl(contact)
            },
        onPickPicture = { picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) },
        onRepositionPicture = controller::reposition,
        onClearPicture = controller::clear,
    )
    state.crop?.let { source ->
        IdentityImageCropDialog(
            source.preview.asImageBitmap(),
            source.orientedSize,
            IdentityImageCropShape.Circle,
            onDismiss = controller::dismissCrop,
            onConfirm = controller::confirm,
        )
    }
}
