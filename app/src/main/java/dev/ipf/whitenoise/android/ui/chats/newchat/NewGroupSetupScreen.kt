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
import androidx.compose.material.icons.filled.EmojiEmotions
import androidx.compose.material.icons.filled.Group
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import dev.ipf.whitenoise.android.R
import dev.ipf.whitenoise.android.core.RecipientSearch
import dev.ipf.whitenoise.android.media.GroupImageDraftProcessor
import dev.ipf.whitenoise.android.media.ImageUploadDraft
import dev.ipf.whitenoise.android.state.ChatCreateOpenTiming
import dev.ipf.whitenoise.android.state.ChatListItem
import dev.ipf.whitenoise.android.state.WhiteNoiseAppState
import dev.ipf.whitenoise.android.state.groupCreateFailureDetail
import dev.ipf.whitenoise.android.state.runCatchingCancellable
import dev.ipf.whitenoise.android.ui.common.Avatar
import dev.ipf.whitenoise.android.ui.common.rememberImageUploadPreview
import dev.ipf.whitenoise.android.ui.conversation.composer.EmojiPickerSheet
import dev.ipf.whitenoise.android.ui.conversation.composer.insertEmojiAtSelection
import dev.ipf.whitenoise.android.ui.group.DisappearingMessagesPickerDialog
import dev.ipf.whitenoise.android.ui.group.ImageSearchSheet
import dev.ipf.whitenoise.android.ui.group.disappearingMessagesLabel
import dev.ipf.whitenoise.android.ui.rememberRecentEmojiRecentsOwner
import dev.ipf.whitenoise.android.ui.testing.PerformanceTestTags
import dev.ipf.whitenoise.android.ui.testing.exposePerformanceTestTags
import dev.ipf.whitenoise.android.ui.testing.performanceTestTag
import dev.ipf.whitenoise.android.ui.theme.Dimens
import dev.ipf.whitenoise.android.ui.theme.ScrimAlpha
import kotlinx.coroutines.CancellationException

private fun WhiteNoiseAppState.abandonGroupCreateTiming(stage: String) {
    abandonChatCreateOpenTiming(stage)
}

internal fun submittedNewGroupName(value: TextFieldValue): String = value.text.trim()

internal fun newGroupDetailsEditable(
    retryGroupIdHex: String?,
    busy: Boolean,
    imagePreparing: Boolean,
): Boolean = retryGroupIdHex == null && !busy && !imagePreparing

private suspend fun applyNewGroupRetentionIfNeeded(
    appState: WhiteNoiseAppState,
    account: String,
    groupIdHex: String,
    retentionSecs: Long,
    isRetryLoad: Boolean,
    onStage: (NewGroupCreateStage?) -> Unit,
): GroupRetentionApplyOutcome {
    if (retentionSecs <= 0L || isRetryLoad) return GroupRetentionApplyOutcome.Skipped
    // Applied post-create because the create commit has no retention parameter;
    // a failure leaves the group usable with the default (off) window.
    onStage(NewGroupCreateStage.ApplyingRetention)
    return runCatchingCancellable {
        appState.withGroupCommitLock(account, groupIdHex) {
            appState.marmotIo { updateMessageRetention(account, groupIdHex, retentionSecs.toULong()) }
        }
    }.fold(
        onSuccess = { GroupRetentionApplyOutcome.Applied },
        onFailure = {
            appState.present(R.string.toast_disappearing_not_applied, copyable = true)
            GroupRetentionApplyOutcome.Failed
        },
    )
}

private suspend fun runNewGroupCreateMutation(
    appState: WhiteNoiseAppState,
    account: String,
    groupName: String,
    recipients: List<String>,
    imageDraft: ImageUploadDraft?,
    retentionSecs: Long,
    retryLoadGroupIdHex: String?,
    isRetryLoad: Boolean,
    createRequestToken: Long,
    onStage: (NewGroupCreateStage?) -> Unit,
    onRetryGroupId: (String) -> Unit,
    onCreateError: (Throwable) -> Unit,
    onCreateCompletedOpen: (ChatListItem, Long) -> Unit,
    onRetryGroupIdCleared: () -> Unit,
    onAuthoritativeReadFailed: (Throwable) -> Unit,
) {
    try {
        val groupIdHex =
            retryLoadGroupIdHex
                ?: runCatchingCancellable {
                    onStage(NewGroupCreateStage.Creating)
                    appState.markChatCreateOpenStage(ChatCreateOpenTiming.STAGE_MDK_CREATE_START)
                    appState
                        .marmotIo {
                            createGroupWithInitialImage(
                                account,
                                groupName,
                                recipients,
                                null,
                                imageDraft?.initialGroupImage(),
                            )
                        }.also { appState.markChatCreateOpenStage(ChatCreateOpenTiming.STAGE_MDK_CREATE_RETURN) }
                }.getOrElse {
                    appState.abandonGroupCreateTiming(ChatCreateOpenTiming.STAGE_CREATE_FAILED)
                    onCreateError(it)
                    return
                }
        onRetryGroupId(groupIdHex)
        val retentionOutcome =
            applyNewGroupRetentionIfNeeded(
                appState = appState,
                account = account,
                groupIdHex = groupIdHex,
                retentionSecs = retentionSecs,
                isRetryLoad = isRetryLoad,
                onStage = onStage,
            )
        openCreatedGroupAfterCanonicalCreate(
            appState = appState,
            accountRef = account,
            groupIdHex = groupIdHex,
            showCreatedToast = !isRetryLoad,
            retentionOutcome = retentionOutcome,
            createRequestToken = createRequestToken,
            onCreateCompletedOpen = onCreateCompletedOpen,
            onRetryGroupIdCleared = onRetryGroupIdCleared,
            onAuthoritativeReadFailed = onAuthoritativeReadFailed,
        )
    } catch (cancelled: CancellationException) {
        appState.abandonGroupCreateTiming(ChatCreateOpenTiming.STAGE_CANCELLED)
        throw cancelled
    }
}

private suspend fun openCreatedGroupAfterCanonicalCreate(
    appState: WhiteNoiseAppState,
    accountRef: String,
    groupIdHex: String,
    showCreatedToast: Boolean,
    retentionOutcome: GroupRetentionApplyOutcome,
    createRequestToken: Long,
    onCreateCompletedOpen: (ChatListItem, Long) -> Unit,
    onRetryGroupIdCleared: () -> Unit,
    onAuthoritativeReadFailed: (Throwable) -> Unit,
) {
    val successToastResId = groupCreateSuccessToastResId(showCreatedToast, retentionOutcome)
    runCatchingCancellable {
        val item = appState.loadCreatedChatListItem(groupIdHex)
        onRetryGroupIdCleared()
        onCreateCompletedOpen(item, createRequestToken)
        successToastResId?.let {
            appState.presentConversationTransient(accountRef, groupIdHex, it)
        }
    }.onFailure {
        appState.abandonGroupCreateTiming(ChatCreateOpenTiming.STAGE_AUTHORITATIVE_READ_FAILED)
        onAuthoritativeReadFailed(it)
    }
}

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
    onCreateCompletedOpen: (ChatListItem, Long) -> Unit,
    onCreateSubmitted: () -> Long = { 0L },
    initialRetryGroupIdHex: String? = null,
) {
    var groupName by rememberSaveable(stateSaver = TextFieldValue.Saver) { mutableStateOf(TextFieldValue()) }
    var retentionSecs by rememberSaveable { mutableLongStateOf(0L) }
    var showRetentionPicker by remember { mutableStateOf(false) }
    var showImagePicker by remember { mutableStateOf(false) }
    var showEmojiPicker by rememberSaveable { mutableStateOf(false) }
    var imageDraft by remember { mutableStateOf<ImageUploadDraft?>(null) }
    var imagePreparing by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var createStage by remember { mutableStateOf<NewGroupCreateStage?>(null) }
    var retryGroupIdHex by rememberSaveable { mutableStateOf(initialRetryGroupIdHex) }
    var createRequestToken by rememberSaveable { mutableLongStateOf(0L) }
    var error by remember { mutableStateOf<String?>(null) }
    val context = LocalContext.current
    val recentEmojiRecentsOwner = rememberRecentEmojiRecentsOwner(context)
    val imagePreview = rememberImageUploadPreview(imageDraft)

    fun createGroupErrorMessage(throwable: Throwable): String = groupCreateFailureDetail(throwable, appState::chatMemberTitle).resolve(context)

    val canCreate =
        canSubmitNewChatSheet(
            directMessage = false,
            busy = busy || imagePreparing,
            pendingRecipient = "",
            groupName = groupName.text,
        )
    val setupUi = newGroupSetupUiState(retryGroupIdHex, canCreate, busy)

    fun detailsEditableNow(): Boolean =
        newGroupDetailsEditable(
            retryGroupIdHex = retryGroupIdHex,
            busy = busy,
            imagePreparing = imagePreparing,
        )
    val detailsEditable = detailsEditableNow()
    val setupMessage = setupUi.statusResId?.let { stringResource(it) } ?: error

    LaunchedEffect(detailsEditable) {
        if (!detailsEditable) showEmojiPicker = false
    }

    fun create(retryLoadGroupIdHex: String? = null) {
        // canCreate is a composition-time snapshot; the direct `busy` state
        // read blocks a second tap that lands before recomposition.
        if (!canStartNewGroupCreateAttempt(busy, canCreate, retryLoadGroupIdHex)) return
        val account = appState.activeAccountRef ?: return
        val isRetryLoad = retryLoadGroupIdHex != null
        val recipients =
            newChatMemberRefs(
                directMessage = false,
                normalizedPendingRecipients = emptyList(),
                initialMemberRefs = members.map { it.accountIdHex },
            )
        busy = true
        createStage = null
        error = null
        if (!isRetryLoad) {
            createRequestToken = onCreateSubmitted()
        }
        appState.beginChatCreateOpenTiming()
        appState.launchMutation {
            try {
                runNewGroupCreateMutation(
                    appState = appState,
                    account = account,
                    groupName = submittedNewGroupName(groupName),
                    recipients = recipients,
                    imageDraft = imageDraft,
                    retentionSecs = retentionSecs,
                    retryLoadGroupIdHex = retryLoadGroupIdHex,
                    isRetryLoad = isRetryLoad,
                    createRequestToken = createRequestToken,
                    onStage = { createStage = it },
                    onRetryGroupId = { retryGroupIdHex = it },
                    onCreateError = { error = createGroupErrorMessage(it) },
                    onCreateCompletedOpen = onCreateCompletedOpen,
                    onRetryGroupIdCleared = { retryGroupIdHex = null },
                    onAuthoritativeReadFailed = { error = createGroupErrorMessage(it) },
                )
            } finally {
                busy = false
                createStage = null
            }
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
            } catch (_: Exception) {
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
        modifier = Modifier.imePadding().exposePerformanceTestTags(),
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
            Surface(
                onClick = { create(retryLoadGroupIdHex = retryGroupIdHex) },
                modifier = Modifier.performanceTestTag(PerformanceTestTags.CREATE_GROUP),
                enabled = setupUi.submitEnabled,
                shape = FloatingActionButtonDefaults.extendedFabShape,
                color =
                    if (setupUi.submitEnabled) {
                        MaterialTheme.colorScheme.primaryContainer
                    } else {
                        MaterialTheme.colorScheme.surfaceContainerHighest
                    },
                contentColor =
                    if (setupUi.submitEnabled) {
                        MaterialTheme.colorScheme.onPrimaryContainer
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.38f)
                    },
                shadowElevation = 6.dp,
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    if (busy) {
                        CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                    } else {
                        Icon(
                            Icons.Default.Check,
                            contentDescription = null,
                        )
                    }
                    Spacer(Modifier.size(Dimens.spaceSm))
                    Text(stringResource(setupUi.fabLabelResId))
                }
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
                    val trimmedName = submittedNewGroupName(groupName)
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
                                    enabled = setupUi.detailsEditable && !busy && !imagePreparing,
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
                        enabled = detailsEditable,
                        trailingIcon = {
                            IconButton(
                                onClick = {
                                    if (detailsEditableNow()) {
                                        showEmojiPicker = true
                                    }
                                },
                                enabled = detailsEditable,
                                modifier = Modifier.size(48.dp),
                            ) {
                                Icon(
                                    Icons.Default.EmojiEmotions,
                                    contentDescription = stringResource(R.string.open_emoji_picker),
                                )
                            }
                        },
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
            setupMessage?.let { message ->
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
            createStage?.let { stage ->
                item {
                    Text(
                        when (stage) {
                            NewGroupCreateStage.Creating -> stringResource(R.string.group_create_stage_creating)
                            NewGroupCreateStage.ApplyingRetention ->
                                stringResource(R.string.group_create_stage_applying_retention)
                        },
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.fillMaxWidth().padding(horizontal = Dimens.spaceLg),
                    )
                }
            }
            item {
                SettingsActionRow(
                    icon = Icons.Default.Schedule,
                    title = stringResource(R.string.disappearing_messages),
                    value = disappearingMessagesLabel(retentionSecs),
                    enabled = setupUi.detailsEditable && !busy,
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
                    title = selectedMemberDisplayName(member, appState),
                    subtitle = appState.shortNpub(member.accountIdHex).takeIf { it.isNotBlank() },
                    avatarSeed = member.accountIdHex,
                    avatarUrl = selectedMemberAvatarUrl(member, appState.avatarUrl(member.accountIdHex)),
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
            title = submittedNewGroupName(groupName),
            seed = submittedNewGroupName(groupName),
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

    if (showEmojiPicker && detailsEditable) {
        EmojiPickerSheet(
            onDismissRequest = { showEmojiPicker = false },
            onEmojiPicked = { emoji ->
                if (detailsEditableNow()) {
                    groupName = insertEmojiAtSelection(groupName, emoji)
                }
            },
            recentEmojis = recentEmojiRecentsOwner.recents,
            onEmojiUsed = { emoji ->
                if (detailsEditableNow()) recentEmojiRecentsOwner.onEmojiUsed(emoji)
            },
        )
    }
}
