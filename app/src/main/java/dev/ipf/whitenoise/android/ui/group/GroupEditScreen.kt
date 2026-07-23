package dev.ipf.whitenoise.android.ui.group

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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.unit.dp
import dev.ipf.whitenoise.android.R
import dev.ipf.whitenoise.android.core.ProfileSanitizer
import dev.ipf.whitenoise.android.state.ConversationController
import dev.ipf.whitenoise.android.state.WhiteNoiseAppState
import dev.ipf.whitenoise.android.ui.common.Avatar
import dev.ipf.whitenoise.android.ui.common.SectionCard
import dev.ipf.whitenoise.android.ui.common.StickyFormActionBar
import dev.ipf.whitenoise.android.ui.common.rememberGroupTitleCopy
import dev.ipf.whitenoise.android.ui.profile.AvatarFullScreenViewer
import dev.ipf.whitenoise.android.ui.profile.rememberAvatarImageAvailable
import dev.ipf.whitenoise.android.ui.theme.Dimens
import dev.ipf.whitenoise.android.ui.theme.ScrimAlpha

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
    val canEdit = controller.isSelfMember && controller.isSelfAdmin
    val groupAvatarUrl = ProfileSanitizer.imageUrl(controller.group.avatarUrl)
    val groupAvatarImageAvailable = rememberAvatarImageAvailable(groupAvatarUrl)
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

    // System back returns to Group Details, not all the way out to the
    // conversation. This composes after the details screen's own BackHandler
    // (rendered just before the early return that shows this screen), so it
    // wins the back event while the editor is open.
    BackHandler { onBack() }

    val editImageLabel =
        stringResource(
            if (controller.group.avatarUrl.isNullOrBlank()) {
                R.string.group_image_search_set
            } else {
                R.string.group_image_search_edit
            },
        )

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.edit)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.back))
                    }
                },
            )
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
                            Avatar(
                                title = controller.title(groupTitleCopy),
                                seed = controller.group.groupIdHex,
                                size = 96.dp,
                                pictureUrl = groupAvatarUrl,
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

    if (avatarViewerOpen && groupAvatarUrl != null && groupAvatarImageAvailable) {
        AvatarFullScreenViewer(
            title = controller.title(groupTitleCopy),
            seed = controller.group.groupIdHex,
            pictureUrl = groupAvatarUrl,
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
            header = stringResource(R.string.group_image_search_title),
            title = controller.title(groupTitleCopy),
            seed = controller.group.groupIdHex,
            urlLabel = stringResource(R.string.group_avatar_url),
            applyInFlight = imageSaving || controller.mutationInFlight,
            onApply = { picked ->
                imageSaving = true
                controller.clearLastMutationError()
                appState.launchMutation {
                    try {
                        if (controller.updateGroupAvatarUrl(picked)) showImageSearch = false
                    } finally {
                        imageSaving = false
                    }
                }
            },
            onDismiss = { showImageSearch = false },
        )
    }
}
