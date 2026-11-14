package dev.ipf.whitenoise.android.ui.group

import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.StringRes
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
import androidx.compose.material3.SheetValue
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import dev.ipf.marmotkit.AppGroupRecordFfi
import dev.ipf.whitenoise.android.R
import dev.ipf.whitenoise.android.core.ProfileSanitizer
import dev.ipf.whitenoise.android.media.GroupImageDraftProcessor
import dev.ipf.whitenoise.android.media.IdentityImageCropShape
import dev.ipf.whitenoise.android.media.ImageUploadDraft
import dev.ipf.whitenoise.android.media.ProfilePictureDraftProcessor
import dev.ipf.whitenoise.android.media.renderIdentityImageDraft
import dev.ipf.whitenoise.android.state.ConversationController
import dev.ipf.whitenoise.android.state.MediaQuality
import dev.ipf.whitenoise.android.state.ScopedGroupImageMutation
import dev.ipf.whitenoise.android.state.StalenessGuard
import dev.ipf.whitenoise.android.state.WhiteNoiseAppState
import dev.ipf.whitenoise.android.state.presentFailure
import dev.ipf.whitenoise.android.ui.common.Avatar
import dev.ipf.whitenoise.android.ui.common.GroupNameEmojiField
import dev.ipf.whitenoise.android.ui.common.IMAGE_DOCUMENT_MIME_TYPES
import dev.ipf.whitenoise.android.ui.common.IdentityImageCropFlow
import dev.ipf.whitenoise.android.ui.common.PreparedGroupAvatarContent
import dev.ipf.whitenoise.android.ui.common.ProseTextField
import dev.ipf.whitenoise.android.ui.common.StickyFormActionBar
import dev.ipf.whitenoise.android.ui.common.WhiteNoiseButton
import dev.ipf.whitenoise.android.ui.common.WhiteNoiseDropdownMenu
import dev.ipf.whitenoise.android.ui.common.WhiteNoiseFilledTonalButton
import dev.ipf.whitenoise.android.ui.common.WhiteNoiseMenuItem
import dev.ipf.whitenoise.android.ui.common.conversationGroupAvatarAsset
import dev.ipf.whitenoise.android.ui.common.rememberAvatarPreparationRecovery
import dev.ipf.whitenoise.android.ui.common.rememberConversationGroupAvatar
import dev.ipf.whitenoise.android.ui.common.rememberGroupTitleCopy
import dev.ipf.whitenoise.android.ui.common.rememberSheetTerminalMotion
import dev.ipf.whitenoise.android.ui.common.whiteNoiseVerticalScroll
import dev.ipf.whitenoise.android.ui.conversation.composer.EmojiPickerSheet
import dev.ipf.whitenoise.android.ui.conversation.composer.insertEmojiAtSelection
import dev.ipf.whitenoise.android.ui.profile.AvatarFullScreenViewer
import dev.ipf.whitenoise.android.ui.profile.rememberAvatarImageAvailable
import dev.ipf.whitenoise.android.ui.rememberRecentEmojiRecentsOwner
import dev.ipf.whitenoise.android.ui.settings.SettingsScaffold
import dev.ipf.whitenoise.android.ui.theme.WhiteNoiseSpacing
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

/**
 * The URL a Blossom upload may be published under. Throws rather than fall
 * back, so a host that answers with anything but a safe HTTPS URL can never
 * become the group's public avatar.
 */
@Suppress("MaxLineLength")
internal fun safeAvatarUploadUrl(url: String): String = ProfileSanitizer.androidOwnedHttpsImageUrl(url) ?: error("unsafe upload URL")

/** The name field accepts emoji only for an admin while no save or mutation runs. */
internal fun groupNameEmojiEditable(
    canEdit: Boolean,
    saving: Boolean,
    mutationInFlight: Boolean,
): Boolean = canEdit && !saving && !mutationInFlight

/** Edit Group: photo with its sources, name and description, Save in the bottom bar. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun GroupEditScreen(
    appState: WhiteNoiseAppState,
    controller: ConversationController,
    onBack: () -> Unit,
) {
    PreparedGroupAvatarContent(
        appState,
        listOfNotNull(conversationGroupAvatarAsset(appState, controller)),
        controller.boundAccountRef,
        surfaceIdentity = controller,
    ) {
        val groupTitleCopy = rememberGroupTitleCopy()
        val imageAccount = appState.activeAccountRef
        val imageRuntime = appState.runtimeGeneration
        val imageGroup = controller.group.groupIdHex
        val currentImageController by rememberUpdatedState(controller)
        val imageOwner = remember(appState, controller, imageGroup, imageAccount, imageRuntime) { StalenessGuard() }
        val imageOwnerEpoch = remember(imageOwner) { imageOwner.capture() }
        DisposableEffect(imageOwner) { onDispose { imageOwner.advance() } }

        /** This exact editor owns callbacks permanently; later owners cannot revive an old selection. */
        fun imageOwnerIsCurrent(): Boolean {
            val ownsController =
                currentImageController === controller &&
                    controller.group.groupIdHex == imageGroup &&
                    controller.boundAccountRef == imageAccount
            val ownsSession =
                imageAccount != null &&
                    appState.activeAccountRef == imageAccount &&
                    appState.runtimeGeneration == imageRuntime
            val available = !appState.signOutInProgress && !appState.wipeInProgress
            return imageOwner.isCurrent(imageOwnerEpoch) && ownsController && ownsSession && available
        }
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
        // The values the editor opened with: a field the user left alone is not sent, even when
        // another admin changes it while the editor is open (#3704).
        val openedName = rememberSaveable(controller.group.groupIdHex) { controller.group.name }
        val openedDescription = rememberSaveable(controller.group.groupIdHex) { controller.group.description }
        var showEmojiPicker by rememberSaveable(controller.group.groupIdHex) { mutableStateOf(false) }
        var showImageSearch by remember { mutableStateOf(false) }
        var showGroupEmojiImagePicker by remember { mutableStateOf(false) }
        var avatarViewerOpen by remember { mutableStateOf(false) }
        var saving by remember { mutableStateOf(false) }
        var imageSaving by remember(imageOwner) { mutableStateOf(false) }
        val sheetBusy by rememberUpdatedState(imageSaving || controller.mutationInFlight)
        val searchState =
            key(imageOwner, showImageSearch) {
                rememberModalBottomSheetState(
                    skipPartiallyExpanded = true,
                    confirmValueChange = { it != SheetValue.Hidden || !sheetBusy },
                )
            }
        val searchTerminal = rememberSheetTerminalMotion(searchState)
        val emojiImageState =
            key(imageOwner, showGroupEmojiImagePicker) {
                rememberModalBottomSheetState(
                    skipPartiallyExpanded = true,
                    confirmValueChange = { it != SheetValue.Hidden || !sheetBusy },
                )
            }
        val emojiImageTerminal = rememberSheetTerminalMotion(emojiImageState)

        val imageFailureScope =
            remember(imageOwner) {
                GroupImageFailureScope({ appState.toast }, { appState.clearToast(it) })
            }
        DisposableEffect(imageFailureScope) { onDispose { imageFailureScope.dispose() } }
        val cropRecovery =
            rememberAvatarPreparationRecovery(
                origin = "$imageRuntime:$imageAccount:${controller.group.groupIdHex}:group-avatar",
                isCurrent = {
                    imageAccount != null &&
                        appState.activeAccountRef == imageAccount &&
                        appState.runtimeGeneration == imageRuntime &&
                        !appState.signOutInProgress &&
                        !appState.wipeInProgress
                },
                onInterrupted = { appState.present(R.string.avatar_preparation_interrupted) },
            )
        val context = LocalContext.current
        val recentEmojiRecentsOwner = rememberRecentEmojiRecentsOwner(context)
        val canEdit = controller.isSelfMember && controller.isSelfAdmin && !controller.group.unrecoverable
        val nameEditable =
            groupNameEmojiEditable(
                canEdit = canEdit,
                saving = saving,
                mutationInFlight = controller.mutationInFlight,
            )
        val groupAvatar = rememberConversationGroupAvatar(appState, controller)
        val groupAvatarUrl = groupAvatar.pictureUrl
        val encryptedGroupAvatar = groupAvatar.image
        val legacyGroupAvatarAvailable =
            rememberAvatarImageAvailable(
                groupAvatarUrl.takeIf { encryptedGroupAvatar == null },
                groupAvatar.pictureDomain,
            )
        val groupAvatarImageAvailable = encryptedGroupAvatar != null || legacyGroupAvatarAvailable
        val hasGroupImage =
            ProfileSanitizer.protocolImageUrl(controller.group.avatarUrl) != null ||
                controller.group.imageHashHex != null
        // A photo being prepared or uploaded holds Save too, so a save cannot close the editor under it.
        val saveEnabled =
            !saving &&
                !imageSaving &&
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
                    if (controller.updateGroupProfile(name.text, description, openedName, openedDescription)) onBack()
                } finally {
                    saving = false
                }
            }
        }

        /** Apply or remove the encrypted group image and own any preparation failure shown here. */
        @Suppress("TooGenericExceptionCaught") // The callback can surface any non-cancellation preparation failure.
        fun updateImage(prepare: suspend () -> ImageUploadDraft?) {
            val searchExit = searchTerminal
            val emojiExit = emojiImageTerminal
            if (imageSaving || controller.mutationInFlight) return
            val failureAttempt = imageFailureScope.begin()
            imageSaving = true
            controller.clearLastMutationError()
            appState.launchMutation {
                try {
                    val draft = prepare()
                    val change = ScopedGroupImageMutation(draft) { imageFailureScope.isCurrent(failureAttempt) }
                    if (controller.updateGroupImage(change)) {
                        searchExit.closeWhenReady(ready = { !sheetBusy }) { showImageSearch = false }
                        emojiExit.closeWhenReady(ready = { !sheetBusy }) { showGroupEmojiImagePicker = false }
                    } else if (controller.lastMutationError != null) {
                        imageFailureScope.captureFailure(failureAttempt)
                    }
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (error: Exception) {
                    if (!imageFailureScope.isCurrent(failureAttempt)) return@launchMutation
                    appState.presentFailure(
                        R.string.toast_couldnt_prepare_image,
                        "GROUP_IMAGE_PREPARE",
                        error,
                        detail = groupImageFailureDetail(error),
                    )
                    imageFailureScope.captureFailure(failureAttempt)
                } finally {
                    imageSaving = false
                }
            }
        }

        /** Both public-avatar paths retain the same editor, account and permission ownership. */
        fun imageAttemptIsCurrent(
            attempt: Long,
            accountRef: String,
        ): Boolean =
            imageOwnerIsCurrent() &&
                imageFailureScope.isCurrent(attempt) &&
                appState.activeAccountRef == accountRef &&
                controller.boundAccountRef == accountRef &&
                controller.isSelfMember &&
                controller.isSelfAdmin &&
                !controller.group.unrecoverable

        /** Validate and publish a public HTTPS avatar URL for this group's current editor. */
        @Suppress("TooGenericExceptionCaught") // The FFI boundary can surface unchecked non-cancellation failures.
        fun setPublicAvatarUrl(url: String) {
            val searchExit = searchTerminal
            val accountRef = appState.activeAccountRef
            if (accountRef == null || imageSaving || controller.mutationInFlight) return
            val failureAttempt = imageFailureScope.begin()
            // Same HTTPS/credential/loopback policy the upload path enforces, but a
            // hand-typed URL earns a toast rather than safeAvatarUploadUrl's throw.
            val safeUrl = ProfileSanitizer.androidOwnedHttpsImageUrl(url)
            if (safeUrl == null) {
                appState.present(R.string.profile_picture_invalid, copyable = true)
                imageFailureScope.captureFailure(failureAttempt)
                return
            }
            imageSaving = true
            controller.clearLastMutationError()
            appState.launchMutation {
                val attemptIsCurrent = { imageAttemptIsCurrent(failureAttempt, accountRef) }
                try {
                    val change = ScopedGroupImageMutation(safeUrl, attemptIsCurrent)
                    if (controller.updateGroupAvatarUrl(change)) {
                        searchExit.closeWhenReady(ready = { !sheetBusy }) { showImageSearch = false }
                    } else if (attemptIsCurrent() && controller.lastMutationError != null) {
                        imageFailureScope.captureFailure(failureAttempt)
                    }
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (error: Exception) {
                    if (!attemptIsCurrent()) return@launchMutation
                    appState.presentFailure(
                        R.string.toast_couldnt_upload_group_image,
                        "GROUP_AVATAR_UPDATE",
                        error,
                    )
                    imageFailureScope.captureFailure(failureAttempt)
                } finally {
                    imageSaving = false
                }
            }
        }

        /**
         * Upload a device photo as a public avatar after preparation. Its plaintext bytes go to
         * Blossom because people outside the encrypted group cannot read the private group image.
         */
        @Suppress("TooGenericExceptionCaught") // Preparation, upload, and FFI calls have different failure types.
        fun uploadPublicAvatar(
            acceptedCrop: Boolean = false,
            load: suspend () -> ImageUploadDraft,
        ) {
            val accountRef = appState.activeAccountRef ?: return
            val editable = controller.isSelfMember && controller.isSelfAdmin && !controller.group.unrecoverable
            val searchExit = searchTerminal
            val busy = imageSaving || controller.mutationInFlight
            if (!imageOwnerIsCurrent() || !editable || busy) return
            if (acceptedCrop) cropRecovery.accepted()
            val failureAttempt = imageFailureScope.begin()
            imageSaving = true
            controller.clearLastMutationError()
            appState.launchMutation {
                val attemptIsCurrent = {
                    imageAttemptIsCurrent(failureAttempt, accountRef) &&
                        (!acceptedCrop || appState.runtimeGeneration == imageRuntime)
                }
                try {
                    val attempt =
                        GroupAvatarUploadAttempt(
                            isCurrent = attemptIsCurrent,
                            clockMillis = android.os.SystemClock::elapsedRealtime,
                        )
                    val updated =
                        attempt.run(
                            prepare = load,
                            upload = { draft ->
                                appState.marmotIo {
                                    uploadProfileImage(accountRef, draft.plaintext, draft.mediaType, null)
                                }
                            },
                            publish = { safeUrl ->
                                val change = ScopedGroupImageMutation(safeUrl, attemptIsCurrent)
                                controller.updateGroupAvatarUrl(change, commitIfCurrent = attemptIsCurrent)
                            },
                            // Any outcome — including a stale owner or an unreported `false` —
                            // clears the saved crop evidence, so only a real interruption
                            // shows the "interrupted" notice after recreation.
                            finished = { if (acceptedCrop) cropRecovery.settled() },
                        )
                    if (updated) {
                        searchExit.closeWhenReady(ready = { !sheetBusy }) { showImageSearch = false }
                    } else if (attemptIsCurrent() && controller.lastMutationError != null) {
                        imageFailureScope.captureFailure(failureAttempt)
                    }
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (error: Exception) {
                    if (!attemptIsCurrent()) return@launchMutation
                    val uploadFailure = error as? GroupAvatarUploadFailure
                    appState.presentFailure(
                        titleRes = groupAvatarFailureTitle(uploadFailure?.stage),
                        operationCode = uploadFailure?.stage?.operationCode ?: "GROUP_IMAGE_UPLOAD",
                        throwable = error,
                        detail = groupImageFailureDetail(error),
                    )
                    imageFailureScope.captureFailure(failureAttempt)
                } finally {
                    imageSaving = false
                }
            }
        }

        // System back returns to Group Details, not all the way out to the
        // conversation. This composes after the details screen's own BackHandler
        // (rendered just before the early return that shows this screen), so it
        // wins the back event while the editor is open.
        BackHandler {
            cropRecovery.settled()
            onBack()
        }

        var photoMenuOpen by remember(imageOwner) { mutableStateOf(false) }
        var pendingCropUri by rememberSaveable(imageOwner) { mutableStateOf<Uri?>(null) }
        // Every device photo is uploaded publicly, so each pick is confirmed before cropping or upload.
        var pendingPublicPhoto by remember(imageOwner) { mutableStateOf<PendingPublicGroupPhoto?>(null) }
        val imagePickerScope = rememberCoroutineScope()
        val imagePickGeneration = remember(imageOwner) { StalenessGuard() }

        /** Only the latest pick can open a review in the editor that requested it. */
        fun pickGroupImage(uri: Uri) {
            if (!imageOwnerIsCurrent()) return
            pendingPublicPhoto = null
            pendingCropUri = null
            val generation = imagePickGeneration.advance()
            imagePickerScope.launch {
                val gif = ProfilePictureDraftProcessor.isGifSource(context.contentResolver, uri)
                val editable = controller.isSelfMember && controller.isSelfAdmin && !controller.group.unrecoverable
                val current = imagePickGeneration.isCurrent(generation) && imageOwnerIsCurrent()
                val busy = imageSaving || controller.mutationInFlight
                if (!current || !editable || busy) return@launch
                pendingPublicPhoto = PendingPublicGroupPhoto(uri, gif)
            }
        }

        pendingPublicPhoto?.let { selected ->
            val generation = imagePickGeneration.capture()

            fun selectionIsCurrent(): Boolean =
                imageOwnerIsCurrent() &&
                    imagePickGeneration.isCurrent(generation) &&
                    pendingPublicPhoto == selected
            val gif = selected.gif
            dev.ipf.whitenoise.android.ui.common.ConfirmDialog(
                ownerKey = Triple(imageOwner, generation, selected),
                title = stringResource(if (gif) R.string.group_gif_upload_title else R.string.group_photo_upload_title),
                message =
                    stringResource(if (gif) R.string.group_gif_upload_notice else R.string.group_photo_upload_notice),
                confirmLabel =
                    stringResource(if (gif) R.string.group_gif_upload_confirm else R.string.group_photo_upload_confirm),
                confirmEnabled = canEdit && !imageSaving && !controller.mutationInFlight,
                onDismiss = {
                    if (selectionIsCurrent()) {
                        imagePickGeneration.advance()
                        pendingPublicPhoto = null
                    }
                },
                onConfirm = {
                    val editable = controller.isSelfMember && controller.isSelfAdmin && !controller.group.unrecoverable
                    val currentEditableSelection = selectionIsCurrent() && editable
                    if (currentEditableSelection && !imageSaving && !controller.mutationInFlight) {
                        imagePickGeneration.advance()
                        pendingPublicPhoto = null
                        if (selected.gif) {
                            uploadPublicAvatar {
                                GroupImageDraftProcessor.fromGroupContentUri(context.contentResolver, selected.uri)
                            }
                        } else {
                            pendingCropUri = selected.uri
                        }
                    }
                },
            )
        }

        // Retiring only the launcher registrations prevents an old system result from targeting a new editor.
        val photoPicker =
            key(imageOwner) {
                rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
                    if (uri != null) pickGroupImage(uri)
                }
            }
        val filePicker =
            key(imageOwner) {
                rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
                    if (uri != null) pickGroupImage(uri)
                }
            }
        IdentityImageCropFlow(
            uri = pendingCropUri,
            shape = IdentityImageCropShape.RoundedSquare,
            onDismiss = {
                imageFailureScope.clear()
                pendingCropUri = null
            },
            onUnreadable = { picked ->
                // The upload is prepared first and its actual media type decides the notice, so a GIF never goes up
                // whole after the still-photo confirmation. The image slot, and with it Save, stays claimed while
                // the pick is prepared. A later pick may still replace an unconfirmed GIF notice, as it would any
                // pending notice.
                val claimed = imageOwnerIsCurrent() && !imageSaving && !controller.mutationInFlight
                if (claimed) {
                    imageSaving = true
                    imagePickerScope.launch {
                        val prepared =
                            try {
                                preparedFallbackDraft(context.contentResolver, picked)
                            } finally {
                                imageSaving = false
                            }
                        when {
                            !imageOwnerIsCurrent() -> Unit
                            prepared.getOrNull()?.mediaType.equals(GIF_MEDIA_TYPE, ignoreCase = true) ->
                                pendingPublicPhoto = PendingPublicGroupPhoto(picked, gif = true)
                            else -> uploadPublicAvatar { prepared.getOrThrow() }
                        }
                    }
                }
            },
            onCropped = { bytes, crop ->
                // A replayed confirm after the crop has closed must not upload the same picture again.
                if (pendingCropUri != null) {
                    pendingCropUri = null
                    uploadPublicAvatar(acceptedCrop = true) {
                        renderIdentityImageDraft(bytes, crop, MediaQuality.Standard)
                    }
                }
            },
        )

        val saveLabel = stringResource(if (saving) R.string.saving_group else R.string.save_group)
        GroupEditScaffold(
            onBack = {
                cropRecovery.settled()
                onBack()
            },
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
                    Text(stringResource(R.string.group_photo_title), style = MaterialTheme.typography.titleMedium)
                    groupPhotoVisibilityDetail(controller.group)?.let { detail ->
                        Text(
                            stringResource(detail),
                            style = MaterialTheme.typography.bodyMedium,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.testTag("group_edit.photoVisibility"),
                        )
                    }
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
                        Avatar(
                            title = controller.title(groupTitleCopy),
                            seed = controller.group.groupIdHex,
                            size = GroupEditAvatarSize,
                            pictureUrl = groupAvatarUrl.takeIf { encryptedGroupAvatar == null },
                            picture = encryptedGroupAvatar,
                            groupAnimation = groupAvatar.groupAnimation,
                            pictureDomain = groupAvatar.pictureDomain,
                        )
                    }
                    if (imageSaving) LinearProgressIndicator(Modifier.fillMaxWidth())
                    Box {
                        WhiteNoiseFilledTonalButton(
                            onClick = {
                                imageFailureScope.clear()
                                photoMenuOpen = true
                            },
                            enabled = canEdit && !imageSaving,
                            modifier = Modifier.testTag("group_edit.photoAction"),
                        ) {
                            Text(stringResource(if (hasGroupImage) R.string.change_photo else R.string.add_photo))
                        }
                        GroupEditPhotoMenu(
                            expanded = photoMenuOpen,
                            hasImage = hasGroupImage,
                            onDismiss = {
                                imageFailureScope.clear()
                                photoMenuOpen = false
                            },
                            onChoosePhoto = {
                                imageFailureScope.clear()
                                photoPicker.launch(
                                    PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly),
                                )
                            },
                            onChooseFile = {
                                imageFailureScope.clear()
                                filePicker.launch(IMAGE_DOCUMENT_MIME_TYPES)
                            },
                            onFindWebImage = {
                                imageFailureScope.clear()
                                showImageSearch = true
                            },
                            onCreateEmoji = {
                                imageFailureScope.clear()
                                showGroupEmojiImagePicker = true
                            },
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
                    ProseTextField(
                        value = description,
                        filled = true,
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
                readLocalBytes = groupAvatar.readOriginalBytes,
                editActionLabel = if (canEdit) stringResource(R.string.group_image_search_edit) else null,
                onEditPicture =
                    if (canEdit) {
                        {
                            avatarViewerOpen = false
                            imageFailureScope.clear()
                            showImageSearch = true
                        }
                    } else {
                        null
                    },
            )
        }

        if (showImageSearch) {
            ImageSearchSheet(
                suppliedSheetState = searchState,
                suppliedTerminal = searchTerminal,
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
                onPickPhoto = { uri ->
                    if (imageSaving || controller.mutationInFlight) return@ImageSearchSheet
                    imageFailureScope.clear()
                    pickGroupImage(uri)
                },
                onPickEmoji = {
                    imageFailureScope.clear()
                    searchTerminal.close {
                        showImageSearch = false
                        showGroupEmojiImagePicker = true
                    }
                },
                onDismiss = {
                    if (!imageSaving && !controller.mutationInFlight) {
                        imageFailureScope.clear()
                        showImageSearch = false
                    }
                },
            )
        }

        if (showGroupEmojiImagePicker) {
            GroupEmojiImagePickerSheet(
                suppliedSheetState = emojiImageState,
                suppliedTerminal = emojiImageTerminal,
                applyInFlight = imageSaving || controller.mutationInFlight,
                recentEmojis = recentEmojiRecentsOwner.recents,
                onEmojiUsed = recentEmojiRecentsOwner::onEmojiUsed,
                onApply = { draft -> updateImage { draft } },
                onDismiss = {
                    if (!imageSaving && !controller.mutationInFlight) {
                        imageFailureScope.clear()
                        showGroupEmojiImagePicker = false
                    }
                },
            )
        }

        if (showEmojiPicker && nameEditable) {
            EmojiPickerSheet(
                closeOnPick = false,
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

/** A device photo awaiting the public-upload confirmation; a still image is cropped after it. */
private const val GIF_MEDIA_TYPE = "image/gif"

/** Prepares an uncroppable pick, keeping a preparation failure for the upload's own error handling. */
@Suppress("TooGenericExceptionCaught") // Decoding and provider reads fail with unrelated exception types.
private suspend fun preparedFallbackDraft(
    contentResolver: android.content.ContentResolver,
    uri: Uri,
): Result<ImageUploadDraft> =
    try {
        Result.success(GroupImageDraftProcessor.fromGroupContentUri(contentResolver, uri))
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (error: Exception) {
        Result.failure(error)
    }

private data class PendingPublicGroupPhoto(
    val uri: Uri,
    val gif: Boolean,
)

/**
 * Who can see the group's current photo: an encrypted image reaches members only, while a URL
 * photo sits on a public server. The avatar shows a public URL ahead of an encrypted image, so a
 * group carrying both is described as public. No photo needs no note.
 */
@StringRes
internal fun groupPhotoVisibilityDetail(group: AppGroupRecordFfi): Int? =
    when {
        ProfileSanitizer.protocolImageUrl(group.avatarUrl) != null -> R.string.group_photo_public_detail
        group.imageHashHex != null -> R.string.group_photo_encrypted_detail
        else -> null
    }
