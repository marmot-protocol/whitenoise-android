package dev.ipf.whitenoise.android.ui.group

import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import dev.ipf.whitenoise.android.R
import dev.ipf.whitenoise.android.core.ProfileSanitizer
import dev.ipf.whitenoise.android.media.GroupImageDraftProcessor
import dev.ipf.whitenoise.android.media.ImageUploadDraft
import dev.ipf.whitenoise.android.state.ConversationController
import dev.ipf.whitenoise.android.state.WhiteNoiseAppState
import dev.ipf.whitenoise.android.state.presentFailure
import dev.ipf.whitenoise.android.ui.common.GroupAvatar
import dev.ipf.whitenoise.android.ui.common.GroupNameEmojiField
import dev.ipf.whitenoise.android.ui.common.IMAGE_DOCUMENT_MIME_TYPES
import dev.ipf.whitenoise.android.ui.common.StickyFormActionBar
import dev.ipf.whitenoise.android.ui.common.WhiteNoiseButton
import dev.ipf.whitenoise.android.ui.common.WhiteNoiseDropdownMenu
import dev.ipf.whitenoise.android.ui.common.WhiteNoiseFilledTonalButton
import dev.ipf.whitenoise.android.ui.common.WhiteNoiseMenuItem
import dev.ipf.whitenoise.android.ui.common.rememberEncryptedGroupAvatar
import dev.ipf.whitenoise.android.ui.common.rememberGroupTitleCopy
import dev.ipf.whitenoise.android.ui.common.whiteNoiseVerticalScroll
import dev.ipf.whitenoise.android.ui.conversation.composer.EmojiPickerSheet
import dev.ipf.whitenoise.android.ui.conversation.composer.insertEmojiAtSelection
import dev.ipf.whitenoise.android.ui.profile.AvatarFullScreenViewer
import dev.ipf.whitenoise.android.ui.profile.rememberAvatarImageAvailable
import dev.ipf.whitenoise.android.ui.rememberRecentEmojiRecentsOwner
import dev.ipf.whitenoise.android.ui.settings.SettingsScaffold
import dev.ipf.whitenoise.android.ui.theme.WhiteNoiseSpacing
import kotlinx.coroutines.CancellationException

/**
 * The URL a Blossom upload may be published under. Throws rather than fall
 * back, so a host that answers with anything but a safe HTTPS URL can never
 * become the group's public avatar.
 */
@Suppress("MaxLineLength")
internal fun safeAvatarUploadUrl(url: String): String = ProfileSanitizer.androidOwnedHttpsImageUrl(url) ?: error("unsafe upload URL")

internal fun groupNameEmojiEditable(
    canEdit: Boolean,
    saving: Boolean,
    mutationInFlight: Boolean,
): Boolean = canEdit && !saving && !mutationInFlight

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun GroupEditScreen(
    appState: WhiteNoiseAppState,
    controller: ConversationController,
    onBack: () -> Unit,
) {
    val groupTitleCopy = rememberGroupTitleCopy()
    // Key only on the group id, not on name/description: the group-state
    // subscription can converge a backend update (another admin's edit, a
    // kind-1210 row) while this screen is open, and re-keying on those values
    // would re-init the fields and discard the user's in-progress edit. State
    // resets only when navigating to a different group. (CodeRabbit, #512.)
    var name by
        rememberSaveable(controller.group.groupIdHex, stateSaver = TextFieldValue.Saver) {
            mutableStateOf(TextFieldValue(controller.group.name))
        }
    var description by remember(controller.group.groupIdHex) { mutableStateOf(controller.group.description) }
    var showEmojiPicker by rememberSaveable(controller.group.groupIdHex) { mutableStateOf(false) }
    var showImageSearch by remember { mutableStateOf(false) }
    var showGroupEmojiImagePicker by remember { mutableStateOf(false) }
    var avatarViewerOpen by remember { mutableStateOf(false) }
    var saving by remember { mutableStateOf(false) }
    var imageSaving by remember { mutableStateOf(false) }
    val context = LocalContext.current
    val recentEmojiRecentsOwner = rememberRecentEmojiRecentsOwner(context)
    val canEdit = controller.isSelfMember && controller.isSelfAdmin && !controller.group.unrecoverable
    val nameEditable =
        groupNameEmojiEditable(
            canEdit = canEdit,
            saving = saving,
            mutationInFlight = controller.mutationInFlight,
        )
    val groupAvatarUrl = ProfileSanitizer.protocolImageUrl(controller.group.avatarUrl)
    val encryptedGroupAvatar = rememberEncryptedGroupAvatar(appState, controller.group)
    val legacyGroupAvatarAvailable = rememberAvatarImageAvailable(groupAvatarUrl)
    val groupAvatarImageAvailable = encryptedGroupAvatar != null || legacyGroupAvatarAvailable
    val hasGroupImage = groupAvatarUrl != null || controller.group.imageHashHex != null
    val saveEnabled =
        !saving &&
            !controller.mutationInFlight &&
            (name.text != controller.group.name || description != controller.group.description)

    LaunchedEffect(nameEditable) {
        if (!nameEditable) showEmojiPicker = false
    }

    fun saveGroupProfile() {
        if (!saveEnabled) return
        saving = true
        controller.clearLastMutationError()
        appState.launchMutation {
            try {
                if (controller.updateGroupProfile(name.text, description)) onBack()
            } finally {
                saving = false
            }
        }
    }

    @Suppress("TooGenericExceptionCaught") // The callback can surface any non-cancellation preparation failure.
    fun updateImage(prepare: suspend () -> ImageUploadDraft?) {
        if (imageSaving || controller.mutationInFlight) return
        imageSaving = true
        controller.clearLastMutationError()
        appState.launchMutation {
            try {
                val draft = prepare()
                if (controller.updateGroupImage(draft)) {
                    showImageSearch = false
                    showGroupEmojiImagePicker = false
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                appState.presentFailure(
                    R.string.toast_couldnt_prepare_image,
                    "GROUP_IMAGE_PREPARE",
                    error,
                )
            } finally {
                imageSaving = false
            }
        }
    }

    @Suppress("TooGenericExceptionCaught") // The FFI boundary can surface unchecked non-cancellation failures.
    fun setPublicAvatarUrl(url: String) {
        if (imageSaving || controller.mutationInFlight) return
        // Same HTTPS/credential/loopback policy the upload path enforces, but a
        // hand-typed URL earns a toast rather than safeAvatarUploadUrl's throw.
        val safeUrl = ProfileSanitizer.androidOwnedHttpsImageUrl(url)
        if (safeUrl == null) {
            appState.present(R.string.profile_picture_invalid, copyable = true)
            return
        }
        imageSaving = true
        controller.clearLastMutationError()
        appState.launchMutation {
            try {
                if (controller.updateGroupAvatarUrl(safeUrl)) showImageSearch = false
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                appState.presentFailure(
                    R.string.toast_couldnt_upload_group_image,
                    "GROUP_AVATAR_UPDATE",
                    error,
                )
            } finally {
                imageSaving = false
            }
        }
    }

    // A device photo becomes a public avatar by uploading the plaintext bytes
    // to Blossom first: the encrypted group image is unreadable to anyone
    // outside the group, so invite previews and QR codes can't render it.
    @Suppress("TooGenericExceptionCaught") // Preparation, upload, and FFI calls have different failure types.
    fun uploadPublicAvatar(uri: Uri) {
        val accountRef = appState.activeAccountRef ?: return
        if (imageSaving || controller.mutationInFlight) return
        imageSaving = true
        controller.clearLastMutationError()
        appState.launchMutation {
            var prepared = false
            try {
                val draft = GroupImageDraftProcessor.fromContentUri(context.contentResolver, uri)
                prepared = true
                val uploaded =
                    appState.marmotIo {
                        uploadProfileImage(accountRef, draft.plaintext, draft.mediaType, null)
                    }
                if (controller.updateGroupAvatarUrl(safeAvatarUploadUrl(uploaded))) {
                    showImageSearch = false
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                appState.presentFailure(
                    titleRes =
                        if (prepared) {
                            R.string.toast_couldnt_upload_group_image
                        } else {
                            R.string.toast_couldnt_prepare_image
                        },
                    operationCode = if (prepared) "GROUP_IMAGE_UPLOAD" else "GROUP_IMAGE_PREPARE",
                    throwable = error,
                )
            } finally {
                imageSaving = false
            }
        }
    }

    // System back returns to Group Details, not all the way out to the
    // conversation. This composes after the details screen's own BackHandler
    // (rendered just before the early return that shows this screen), so it
    // wins the back event while the editor is open.
    BackHandler { onBack() }

    var photoMenuOpen by remember { mutableStateOf(false) }
    val photoPicker =
        rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
            if (uri != null) uploadPublicAvatar(uri)
        }
    val filePicker =
        rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            if (uri != null) uploadPublicAvatar(uri)
        }

    val saveLabel = stringResource(if (saving) R.string.saving_group else R.string.save_group)
    GroupEditScaffold(
        onBack = onBack,
        bottomBar = {
            if (canEdit) {
                StickyFormActionBar {
                    WhiteNoiseButton(
                        onClick = { saveGroupProfile() },
                        enabled = saveEnabled,
                        modifier = Modifier.fillMaxWidth(),
                        loading = saving,
                        loadingLabel = saveLabel,
                    ) {
                        Text(saveLabel)
                    }
                }
            }
        },
    ) {
        Column(
            modifier =
                Modifier
                    .fillMaxSize()
                    .whiteNoiseVerticalScroll(rememberScrollState())
                    .padding(vertical = WhiteNoiseSpacing.Section),
            verticalArrangement = Arrangement.spacedBy(WhiteNoiseSpacing.Section),
        ) {
            Column(
                modifier = Modifier.fillMaxWidth().padding(horizontal = WhiteNoiseSpacing.CompactScreenMargin),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(WhiteNoiseSpacing.Related),
            ) {
                Text(stringResource(R.string.group_private_image), style = MaterialTheme.typography.titleMedium)
                Text(stringResource(R.string.group_private_image_detail), style = MaterialTheme.typography.bodyMedium)
                Box(
                    modifier =
                        Modifier
                            .clip(CircleShape)
                            .clickable(
                                enabled = groupAvatarImageAvailable,
                                onClickLabel = stringResource(R.string.profile_view_picture),
                                role = Role.Button,
                            ) { avatarViewerOpen = true },
                ) {
                    GroupAvatar(
                        appState = appState,
                        group = controller.group,
                        title = controller.title(groupTitleCopy),
                        seed = controller.group.groupIdHex,
                        size = GroupEditAvatarSize,
                    )
                }
                if (imageSaving) LinearProgressIndicator(Modifier.fillMaxWidth())
                Box {
                    WhiteNoiseFilledTonalButton(
                        onClick = { photoMenuOpen = true },
                        enabled = canEdit && !imageSaving,
                        modifier = Modifier.testTag("group_edit.photoAction"),
                    ) {
                        Text(stringResource(if (hasGroupImage) R.string.change_photo else R.string.add_photo))
                    }
                    GroupEditPhotoMenu(
                        expanded = photoMenuOpen,
                        hasImage = hasGroupImage,
                        onDismiss = { photoMenuOpen = false },
                        onChoosePhoto = {
                            photoPicker.launch(
                                PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly),
                            )
                        },
                        onChooseFile = { filePicker.launch(IMAGE_DOCUMENT_MIME_TYPES) },
                        onFindWebImage = { showImageSearch = true },
                        onCreateEmoji = { showGroupEmojiImagePicker = true },
                        onRemove = { updateImage { null } },
                    )
                }
            }
            Column(
                modifier = Modifier.fillMaxWidth().padding(horizontal = WhiteNoiseSpacing.CompactScreenMargin),
                verticalArrangement = Arrangement.spacedBy(WhiteNoiseSpacing.FormField),
            ) {
                GroupNameEmojiField(
                    value = name,
                    onValueChange = { name = it },
                    label = stringResource(R.string.group_name),
                    emojiPickerOpen = showEmojiPicker,
                    onEmojiPickerClick = {
                        if (nameEditable) showEmojiPicker = true
                    },
                    enabled = nameEditable,
                    modifier = Modifier.fillMaxWidth(),
                )
                TextField(
                    value = description,
                    onValueChange = { description = it },
                    label = { Text(stringResource(R.string.group_description)) },
                    minLines = 3,
                    maxLines = 6,
                    enabled = canEdit,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    }

    if (avatarViewerOpen && groupAvatarImageAvailable) {
        AvatarFullScreenViewer(
            title = controller.title(groupTitleCopy),
            seed = controller.group.groupIdHex,
            pictureUrl = groupAvatarUrl,
            picture = encryptedGroupAvatar,
            onDismiss = { avatarViewerOpen = false },
            editActionLabel = if (canEdit) stringResource(R.string.group_image_search_edit) else null,
            onEditPicture =
                if (canEdit) {
                    {
                        avatarViewerOpen = false
                        showImageSearch = true
                    }
                } else {
                    null
                },
        )
    }

    if (showImageSearch) {
        ImageSearchSheet(
            initialUrl = controller.group.avatarUrl.orEmpty(),
            hasCurrentImage = hasGroupImage,
            header = stringResource(R.string.group_image_search_title),
            title = controller.title(groupTitleCopy),
            seed = controller.group.groupIdHex,
            urlLabel = stringResource(R.string.group_avatar_url),
            applyInFlight = imageSaving || controller.mutationInFlight,
            onApply = { picked ->
                // Removal clears both the public URL and any encrypted image.
                if (picked == null) updateImage { null } else setPublicAvatarUrl(picked)
            },
            onPickPhoto = { uri -> uploadPublicAvatar(uri) },
            onPickEmoji = {
                showImageSearch = false
                showGroupEmojiImagePicker = true
            },
            onDismiss = { showImageSearch = false },
        )
    }

    if (showGroupEmojiImagePicker) {
        GroupEmojiImagePickerSheet(
            applyInFlight = imageSaving || controller.mutationInFlight,
            recentEmojis = recentEmojiRecentsOwner.recents,
            onEmojiUsed = recentEmojiRecentsOwner::onEmojiUsed,
            onApply = { draft -> updateImage { draft } },
            onDismiss = { if (!imageSaving && !controller.mutationInFlight) showGroupEmojiImagePicker = false },
        )
    }

    if (showEmojiPicker && nameEditable) {
        EmojiPickerSheet(
            onDismissRequest = { showEmojiPicker = false },
            onEmojiPicked = { emoji ->
                if (nameEditable) name = insertEmojiAtSelection(name, emoji)
            },
            recentEmojis = recentEmojiRecentsOwner.recents,
            onEmojiUsed = { emoji ->
                if (nameEditable) recentEmojiRecentsOwner.onEmojiUsed(emoji)
            },
        )
    }
}

private val GroupEditAvatarSize = 120.dp

/** The prototype's photo sources behind the Add / Change photo button; Remove joins once the group has an image. */
@Composable
@Suppress("FunctionNaming", "LongParameterList")
private fun GroupEditPhotoMenu(
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
                add(WhiteNoiseMenuItem(stringResource(R.string.group_emoji_create), onCreateEmoji, R.drawable.ic_add))
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

/** Settings frame shared by the native editor and its descriptive-title screenshot coverage. */
@Composable
@Suppress("FunctionNaming")
internal fun GroupEditScaffold(
    onBack: () -> Unit,
    bottomBar: @Composable () -> Unit = {},
    content: @Composable () -> Unit = {},
) {
    SettingsScaffold(
        title = stringResource(R.string.edit_group_info_title),
        onBack = onBack,
        bottomBar = bottomBar,
        content = content,
    )
}
