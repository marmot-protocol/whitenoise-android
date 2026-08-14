package dev.ipf.whitenoise.android.ui.group

import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.unit.dp
import dev.ipf.whitenoise.android.R
import dev.ipf.whitenoise.android.core.ProfileSanitizer
import dev.ipf.whitenoise.android.media.GroupImageDraftProcessor
import dev.ipf.whitenoise.android.media.ImageUploadDraft
import dev.ipf.whitenoise.android.state.ConversationController
import dev.ipf.whitenoise.android.state.WhiteNoiseAppState
import dev.ipf.whitenoise.android.ui.common.GroupAvatar
import dev.ipf.whitenoise.android.ui.common.SectionCard
import dev.ipf.whitenoise.android.ui.common.StickyFormActionBar
import dev.ipf.whitenoise.android.ui.common.rememberEncryptedGroupAvatar
import dev.ipf.whitenoise.android.ui.common.rememberGroupTitleCopy
import dev.ipf.whitenoise.android.ui.profile.AvatarFullScreenViewer
import dev.ipf.whitenoise.android.ui.profile.rememberAvatarImageAvailable
import dev.ipf.whitenoise.android.ui.theme.Dimens
import dev.ipf.whitenoise.android.ui.theme.ScrimAlpha
import kotlinx.coroutines.CancellationException

/**
 * The URL a Blossom upload may be published under. Throws rather than fall
 * back, so a host that answers with anything but a safe HTTPS URL can never
 * become the group's public avatar.
 */
@Suppress("MaxLineLength")
internal fun safeAvatarUploadUrl(url: String): String = ProfileSanitizer.androidOwnedHttpsImageUrl(url) ?: error("unsafe upload URL")

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
    var name by remember(controller.group.groupIdHex) { mutableStateOf(controller.group.name) }
    var description by remember(controller.group.groupIdHex) { mutableStateOf(controller.group.description) }
    var showImageSearch by remember { mutableStateOf(false) }
    var avatarViewerOpen by remember { mutableStateOf(false) }
    var saving by remember { mutableStateOf(false) }
    var imageSaving by remember { mutableStateOf(false) }
    val context = LocalContext.current
    val canEdit = controller.isSelfMember && controller.isSelfAdmin && !controller.group.unrecoverable
    val groupAvatarUrl = ProfileSanitizer.protocolImageUrl(controller.group.avatarUrl)
    val encryptedGroupAvatar = rememberEncryptedGroupAvatar(appState, controller.group)
    val legacyGroupAvatarAvailable = rememberAvatarImageAvailable(groupAvatarUrl)
    val groupAvatarImageAvailable = encryptedGroupAvatar != null || legacyGroupAvatarAvailable
    val hasGroupImage = groupAvatarUrl != null || controller.group.imageHashHex != null
    val saveEnabled =
        !saving &&
            !controller.mutationInFlight &&
            (name != controller.group.name || description != controller.group.description)

    fun saveGroupProfile() {
        if (!saveEnabled) return
        saving = true
        controller.clearLastMutationError()
        appState.launchMutation {
            try {
                if (controller.updateGroupProfile(name, description)) onBack()
            } finally {
                saving = false
            }
        }
    }

    fun updateImage(prepare: suspend () -> ImageUploadDraft?) {
        if (imageSaving || controller.mutationInFlight) return
        imageSaving = true
        controller.clearLastMutationError()
        appState.launchMutation {
            try {
                val draft = prepare()
                if (controller.updateGroupImage(draft)) showImageSearch = false
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                appState.present(R.string.toast_couldnt_prepare_image, copyable = true)
            } finally {
                imageSaving = false
            }
        }
    }

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
            } catch (_: Exception) {
                appState.present(R.string.toast_couldnt_upload_group_image, copyable = true)
            } finally {
                imageSaving = false
            }
        }
    }

    // A device photo becomes a public avatar by uploading the plaintext bytes
    // to Blossom first: the encrypted group image is unreadable to anyone
    // outside the group, so invite previews and QR codes can't render it.
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
            } catch (_: Exception) {
                appState.present(
                    if (prepared) {
                        R.string.toast_couldnt_upload_group_image
                    } else {
                        R.string.toast_couldnt_prepare_image
                    },
                    copyable = true,
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

    val editImageLabel =
        stringResource(
            if (!hasGroupImage) {
                R.string.group_image_search_set
            } else {
                R.string.group_image_search_edit
            },
        )

    Scaffold(
        topBar = {
            GroupEditTopBar(onBack = onBack)
        },
        bottomBar = {
            if (canEdit) {
                StickyFormActionBar {
                    Button(
                        onClick = { saveGroupProfile() },
                        enabled = saveEnabled,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        if (saving) {
                            CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                        } else {
                            Icon(Icons.Default.Check, contentDescription = null)
                        }
                        Spacer(Modifier.width(8.dp))
                        Text(stringResource(if (saving) R.string.saving_group else R.string.save_group))
                    }
                }
            }
        },
    ) { padding ->
        LazyColumn(
            modifier =
                Modifier
                    .fillMaxSize()
                    .padding(padding),
            contentPadding = PaddingValues(Dimens.spaceLg),
            verticalArrangement = Arrangement.spacedBy(Dimens.spaceXl),
        ) {
            item {
                Column(
                    Modifier.fillMaxWidth().padding(top = Dimens.spaceSm),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(Dimens.spaceSm),
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Box(
                            contentAlignment = Alignment.Center,
                            modifier =
                                Modifier
                                    .clip(CircleShape)
                                    .clickable(
                                        enabled = groupAvatarImageAvailable || canEdit,
                                        onClickLabel =
                                            stringResource(
                                                if (groupAvatarImageAvailable) R.string.profile_view_picture else R.string.group_image_search_set,
                                            ),
                                        role = Role.Button,
                                    ) {
                                        if (groupAvatarImageAvailable) {
                                            avatarViewerOpen = true
                                        } else if (canEdit) {
                                            showImageSearch = true
                                        }
                                    },
                        ) {
                            GroupAvatar(
                                appState = appState,
                                group = controller.group,
                                title = controller.title(groupTitleCopy),
                                seed = controller.group.groupIdHex,
                                size = 96.dp,
                            )
                        }
                        if (canEdit) {
                            Box(
                                modifier =
                                    Modifier
                                        .align(Alignment.BottomEnd)
                                        .offset(x = 6.dp, y = 6.dp)
                                        .size(36.dp)
                                        .clip(CircleShape)
                                        .background(Color.Black.copy(alpha = ScrimAlpha.HEAVY))
                                        .clickable(
                                            onClickLabel = editImageLabel,
                                            role = Role.Button,
                                        ) { showImageSearch = true },
                                contentAlignment = Alignment.Center,
                            ) {
                                Icon(
                                    Icons.Default.PhotoCamera,
                                    contentDescription = null,
                                    tint = Color.White,
                                    modifier = Modifier.size(20.dp),
                                )
                            }
                        }
                    }
                }
            }
            item {
                SectionCard(title = stringResource(R.string.edit)) {
                    val profileFieldColors =
                        TextFieldDefaults.colors(
                            focusedContainerColor = Color.Transparent,
                            unfocusedContainerColor = Color.Transparent,
                            disabledContainerColor = Color.Transparent,
                            errorContainerColor = Color.Transparent,
                        )
                    TextField(
                        colors = profileFieldColors,
                        value = name,
                        onValueChange = { name = it },
                        label = { Text(stringResource(R.string.group_name)) },
                        singleLine = true,
                        enabled = canEdit,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    TextField(
                        colors = profileFieldColors,
                        value = description,
                        onValueChange = { description = it },
                        label = { Text(stringResource(R.string.description)) },
                        minLines = 3,
                        enabled = canEdit,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
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
            onDismiss = { showImageSearch = false },
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun GroupEditTopBar(onBack: () -> Unit) {
    TopAppBar(
        title = { Text(stringResource(R.string.edit_group_info_title)) },
        navigationIcon = {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.back))
            }
        },
    )
}
