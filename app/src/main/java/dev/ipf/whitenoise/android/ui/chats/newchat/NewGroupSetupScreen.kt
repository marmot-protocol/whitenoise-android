package dev.ipf.whitenoise.android.ui.chats.newchat

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Group
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import dev.ipf.whitenoise.android.R
import dev.ipf.whitenoise.android.core.IdentityFormatter
import dev.ipf.whitenoise.android.core.RecipientSearch
import dev.ipf.whitenoise.android.media.GroupImageDraftProcessor
import dev.ipf.whitenoise.android.media.ImageUploadDraft
import dev.ipf.whitenoise.android.state.ChatListItem
import dev.ipf.whitenoise.android.state.WhiteNoiseAppState
import dev.ipf.whitenoise.android.state.groupCreateFailureDetail
import dev.ipf.whitenoise.android.ui.common.Avatar
import dev.ipf.whitenoise.android.ui.common.rememberImageUploadPreview
import dev.ipf.whitenoise.android.ui.group.DisappearingMessagesPickerDialog
import dev.ipf.whitenoise.android.ui.group.ImageSearchSheet
import dev.ipf.whitenoise.android.ui.group.disappearingMessagesLabel
import dev.ipf.whitenoise.android.ui.theme.Dimens
import dev.ipf.whitenoise.android.ui.theme.ScrimAlpha
import kotlinx.coroutines.CancellationException

/**
 * Final step of the New Group flow: name the group, preview the invited
 * members, and create. Disappearing messages picked here are applied right
 * after the create commit, before anyone can post.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun NewGroupSetupScreen(
    appState: WhiteNoiseAppState,
    members: List<RecipientSearch.Candidate>,
    onBack: () -> Unit,
    onOpenConversation: (ChatListItem, Boolean) -> Unit,
) {
    var groupName by rememberSaveable { mutableStateOf("") }
    var retentionSecs by rememberSaveable { mutableLongStateOf(0L) }
    var showRetentionPicker by remember { mutableStateOf(false) }
    var showImagePicker by remember { mutableStateOf(false) }
    var imageDraft by remember { mutableStateOf<ImageUploadDraft?>(null) }
    var imagePreparing by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val context = LocalContext.current
    val imagePreview = rememberImageUploadPreview(imageDraft)

    fun createGroupErrorMessage(throwable: Throwable): String = groupCreateFailureDetail(throwable, appState::chatMemberTitle).resolve(context)

    val canCreate =
        canSubmitNewChatSheet(
            directMessage = false,
            busy = busy || imagePreparing,
            pendingRecipient = "",
            groupName = groupName,
        )

    fun create() {
        // canCreate is a composition-time snapshot; the direct `busy` state
        // read blocks a second tap that lands before recomposition.
        if (busy || !canCreate) return
        val account = appState.activeAccountRef ?: return
        val recipients =
            newChatMemberRefs(
                directMessage = false,
                normalizedPendingRecipients = emptyList(),
                initialMemberRefs = members.map { it.accountIdHex },
            )
        busy = true
        error = null
        appState.launchMutation {
            runCatching {
                appState.marmotIo {
                    createGroupWithInitialImage(
                        account,
                        groupName.trim(),
                        recipients,
                        null,
                        imageDraft?.initialGroupImage(),
                    )
                }
            }.onSuccess { groupIdHex ->
                if (retentionSecs > 0L) {
                    // Applied post-create because the create commit has no
                    // retention parameter; a failure leaves the group usable
                    // with the default (off) window, so say so instead of
                    // letting the user believe the timer is on.
                    runCatching {
                        appState.withGroupCommitLock(account, groupIdHex) {
                            appState.marmotIo { updateMessageRetention(account, groupIdHex, retentionSecs.toULong()) }
                        }
                    }.onFailure {
                        appState.present(R.string.toast_disappearing_not_applied, copyable = true)
                    }
                }
                appState.present(R.string.toast_chat_created)
                appState.awaitChatListItem(groupIdHex)?.let { item ->
                    onOpenConversation(item, false)
                }
            }.onFailure {
                error = createGroupErrorMessage(it)
            }
            busy = false
        }
    }

    fun prepareImage(load: suspend () -> ImageUploadDraft) {
        if (imagePreparing || busy) return
        imagePreparing = true
        appState.launchMutation {
            try {
                imageDraft = load()
                showImagePicker = false
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Throwable) {
                appState.present(R.string.toast_couldnt_prepare_image, copyable = true)
            } finally {
                imagePreparing = false
            }
        }
    }

    // Installed unconditionally: a disabled handler would let back fall
    // through to the Activity while the create is mid-flight.
    BackHandler {
        if (!busy) onBack()
    }

    Scaffold(
        modifier = Modifier.imePadding(),
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.name_this_group)) },
                navigationIcon = {
                    IconButton(onClick = onBack, enabled = !busy) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.back))
                    }
                },
            )
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = { create() },
                containerColor =
                    if (canCreate) {
                        MaterialTheme.colorScheme.primaryContainer
                    } else {
                        MaterialTheme.colorScheme.surfaceContainerHighest
                    },
                contentColor =
                    if (canCreate) {
                        MaterialTheme.colorScheme.onPrimaryContainer
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.38f)
                    },
            ) {
                if (busy) {
                    CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                } else {
                    Icon(Icons.Default.Check, contentDescription = null)
                }
                Spacer(Modifier.size(Dimens.spaceSm))
                Text(stringResource(R.string.create))
            }
        },
    ) { padding ->
        LazyColumn(
            modifier =
                Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .consumeWindowInsets(padding),
            contentPadding = PaddingValues(bottom = 96.dp),
        ) {
            item {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = Dimens.spaceLg, vertical = Dimens.spaceLg),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(Dimens.spaceLg),
                ) {
                    val trimmedName = groupName.trim()
                    val editImageLabel =
                        stringResource(
                            if (imageDraft == null) {
                                R.string.group_image_search_set
                            } else {
                                R.string.group_image_search_edit
                            },
                        )
                    Box(
                        contentAlignment = Alignment.Center,
                        modifier =
                            Modifier
                                .size(72.dp)
                                .clickable(
                                    enabled = !busy && !imagePreparing,
                                    onClickLabel = editImageLabel,
                                    role = Role.Button,
                                ) { showImagePicker = true },
                    ) {
                        Box(
                            contentAlignment = Alignment.Center,
                            modifier =
                                Modifier
                                    .size(72.dp)
                                    .clip(CircleShape),
                        ) {
                            if (trimmedName.isEmpty() && imagePreview == null) {
                                Box(
                                    modifier =
                                        Modifier
                                            .size(72.dp)
                                            .background(MaterialTheme.colorScheme.surfaceContainerHigh),
                                    contentAlignment = Alignment.Center,
                                ) {
                                    Icon(
                                        Icons.Default.Group,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            } else {
                                Avatar(
                                    title = trimmedName.ifBlank { stringResource(R.string.new_group) },
                                    seed = trimmedName,
                                    size = 72.dp,
                                    picture = imagePreview,
                                )
                            }
                        }
                        Box(
                            modifier =
                                Modifier
                                    .align(Alignment.BottomEnd)
                                    .offset(x = 4.dp, y = 4.dp)
                                    .size(30.dp)
                                    .clip(CircleShape)
                                    .background(Color.Black.copy(alpha = ScrimAlpha.HEAVY)),
                            contentAlignment = Alignment.Center,
                        ) {
                            Icon(
                                Icons.Default.PhotoCamera,
                                contentDescription = null,
                                tint = Color.White,
                                modifier = Modifier.size(18.dp),
                            )
                        }
                    }
                    TextField(
                        value = groupName,
                        onValueChange = { groupName = it },
                        label = { Text(stringResource(R.string.group_name)) },
                        singleLine = true,
                        enabled = !busy,
                        colors =
                            TextFieldDefaults.colors(
                                focusedContainerColor = Color.Transparent,
                                unfocusedContainerColor = Color.Transparent,
                                disabledContainerColor = Color.Transparent,
                                errorContainerColor = Color.Transparent,
                            ),
                        modifier = Modifier.weight(1f),
                    )
                }
            }
            error?.let { message ->
                item {
                    SelectionContainer {
                        Text(
                            message,
                            color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.fillMaxWidth().padding(horizontal = Dimens.spaceLg),
                        )
                    }
                }
            }
            item {
                SettingsActionRow(
                    icon = Icons.Default.Schedule,
                    title = stringResource(R.string.disappearing_messages),
                    value = disappearingMessagesLabel(retentionSecs),
                    enabled = !busy,
                    onClick = { showRetentionPicker = true },
                )
            }
            item { SectionHeader("${stringResource(R.string.members)} · ${members.size}") }
            if (members.isEmpty()) {
                // Members are optional — the group can be created empty and
                // people added afterward from group details.
                item {
                    Text(
                        stringResource(R.string.group_add_members_after_create),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier =
                            Modifier
                                .fillMaxWidth()
                                .padding(horizontal = Dimens.spaceLg, vertical = Dimens.spaceSm),
                    )
                }
            }
            items(members, key = { it.accountIdHex }) { member ->
                ContactRow(
                    title = appState.displayName(member.accountIdHex),
                    subtitle = IdentityFormatter.short(member.npub),
                    avatarSeed = member.accountIdHex,
                    avatarUrl = appState.avatarUrl(member.accountIdHex),
                )
            }
        }
    }

    if (showRetentionPicker) {
        DisappearingMessagesPickerDialog(
            currentSecs = retentionSecs,
            onDismiss = { showRetentionPicker = false },
            onPick = { secs ->
                showRetentionPicker = false
                retentionSecs = secs
            },
        )
    }

    if (showImagePicker) {
        ImageSearchSheet(
            initialUrl = imageDraft?.sourceUrl.orEmpty(),
            hasCurrentImage = imageDraft != null,
            header = stringResource(R.string.group_image_search_title),
            title = groupName.trim(),
            seed = groupName.trim(),
            urlLabel = stringResource(R.string.group_avatar_url),
            applyInFlight = imagePreparing,
            onApply = { picked ->
                if (picked == null) {
                    imageDraft = null
                    showImagePicker = false
                } else {
                    prepareImage { GroupImageDraftProcessor.fromRemoteUrl(picked) }
                }
            },
            onPickPhoto = { uri ->
                prepareImage {
                    GroupImageDraftProcessor.fromContentUri(context.contentResolver, uri)
                }
            },
            onDismiss = { if (!imagePreparing) showImagePicker = false },
        )
    }
}
