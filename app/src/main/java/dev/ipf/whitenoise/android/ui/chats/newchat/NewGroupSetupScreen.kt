package dev.ipf.whitenoise.android.ui.chats.newchat

import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.TextFieldValue
import dev.ipf.whitenoise.android.R
import dev.ipf.whitenoise.android.core.RecipientSearch
import dev.ipf.whitenoise.android.media.GroupImageDraftProcessor
import dev.ipf.whitenoise.android.media.ImageUploadDraft
import dev.ipf.whitenoise.android.state.ChatCreateOpenTiming
import dev.ipf.whitenoise.android.state.ChatListItem
import dev.ipf.whitenoise.android.state.WhiteNoiseAppState
import dev.ipf.whitenoise.android.state.chatListItemFromAuthoritativeGroupDetails
import dev.ipf.whitenoise.android.state.groupCreateFailureDetail
import dev.ipf.whitenoise.android.state.presentFailure
import dev.ipf.whitenoise.android.state.runCatchingCancellable
import dev.ipf.whitenoise.android.ui.common.rememberImageUploadPreview
import dev.ipf.whitenoise.android.ui.conversation.composer.EmojiPickerSheet
import dev.ipf.whitenoise.android.ui.conversation.composer.insertEmojiAtSelection
import dev.ipf.whitenoise.android.ui.group.DisappearingMessagesPickerDialog
import dev.ipf.whitenoise.android.ui.group.GroupEmojiImagePickerSheet
import dev.ipf.whitenoise.android.ui.group.ImageSearchSheet
import dev.ipf.whitenoise.android.ui.group.disappearingMessagesLabel
import dev.ipf.whitenoise.android.ui.rememberRecentEmojiRecentsOwner
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
    owner: GroupCreationSession,
): GroupRetentionApplyOutcome {
    if (retentionSecs <= 0L || isRetryLoad) return GroupRetentionApplyOutcome.Skipped
    // Applied post-create because the create commit has no retention parameter;
    // a failure leaves the group usable with the default (off) window.
    onStage(NewGroupCreateStage.ApplyingRetention)
    return runCatchingCancellable {
        appState.withGroupCommitLock(account, groupIdHex) {
            owner.ensureNativeCurrent()
            appState.marmotIo {
                owner.ensureNativeCurrent()
                updateMessageRetention(account, groupIdHex, retentionSecs.toULong())
            }
        }
    }.fold(
        onSuccess = { GroupRetentionApplyOutcome.Applied },
        onFailure = {
            if (owner.isCurrent()) appState.present(R.string.toast_disappearing_not_applied, copyable = true)
            GroupRetentionApplyOutcome.Failed
        },
    )
}

/** Create once or recover the accepted native group ID before applying captured policy and reading its projection. */
@Suppress("LongParameterList") // Native owner, immutable submission and stage/error delivery belong to this operation.
private suspend fun createOrRecoverNewGroup(
    appState: WhiteNoiseAppState,
    account: String,
    submission: NewGroupSubmission,
    onStage: (NewGroupCreateStage?) -> Unit,
    onCreateError: (Throwable) -> Unit,
    owner: GroupCreationSession,
): String? =
    runCatchingCancellable {
        onStage(NewGroupCreateStage.Creating)
        if (owner.isCurrent()) {
            appState.markChatCreateOpenStage(ChatCreateOpenTiming.STAGE_MDK_CREATE_START)
        }
        submission
            .createWith { name, members, about, image ->
                appState.marmotIo {
                    owner.ensureCurrent()
                    createGroupWithInitialImage(account, name, members, about, image)
                }
            }.also {
                if (owner.isCurrent()) {
                    appState.markChatCreateOpenStage(ChatCreateOpenTiming.STAGE_MDK_CREATE_RETURN)
                }
            }
    }.getOrElse {
        createdGroupIdAfterProjectionUnavailable(it)?.also {
            if (owner.isCurrent()) {
                appState.markChatCreateOpenStage(ChatCreateOpenTiming.STAGE_MDK_CREATE_RETURN)
            }
        } ?: run {
            if (owner.isCurrent()) {
                appState.abandonGroupCreateTiming(ChatCreateOpenTiming.STAGE_CREATE_FAILED)
            }
            onCreateError(it)
            null
        }
    }

/** Freeze the normalized recipients, text and prepared image at the original submission boundary. */
private fun captureNewGroupSubmission(
    draft: NewGroupDraft,
    members: List<RecipientSearch.Candidate>,
    image: ImageUploadDraft?,
): NewGroupSubmission {
    val recipients =
        newChatMemberRefs(
            directMessage = false,
            normalizedPendingRecipients = emptyList(),
            initialMemberRefs = members.map { it.accountIdHex },
        )
    return NewGroupSubmission(
        name =
            draft.name.text
                .toString()
                .trim(),
        description =
            draft.description.text
                .toString()
                .trim()
                .takeIf { it.isNotEmpty() },
        members = recipients,
        image = image,
    )
}

private suspend fun runNewGroupCreateMutation(
    appState: WhiteNoiseAppState,
    account: String,
    groupName: String,
    description: String?,
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
    owner: GroupCreationSession,
) {
    try {
        var retentionOutcome = GroupRetentionApplyOutcome.Skipped
        runGroupCreationStages(
            owner = owner,
            createOrRetry = {
                retryLoadGroupIdHex ?: createOrRecoverNewGroup(
                    appState,
                    account,
                    NewGroupSubmission(groupName, description, recipients, imageDraft),
                    onStage,
                    onCreateError,
                    owner,
                )
            },
            applyCapturedPolicy = { groupIdHex ->
                onRetryGroupId(groupIdHex)
                onStage(null)
                retentionOutcome =
                    applyNewGroupRetentionIfNeeded(
                        appState = appState,
                        account = account,
                        groupIdHex = groupIdHex,
                        retentionSecs = retentionSecs,
                        isRetryLoad = isRetryLoad,
                        onStage = onStage,
                        owner = owner,
                    )
            },
            openCurrentChat = { groupIdHex ->
                onStage(null)
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
                    owner = owner,
                )
            },
        )
    } catch (cancelled: CancellationException) {
        if (owner.isCurrent()) appState.abandonGroupCreateTiming(ChatCreateOpenTiming.STAGE_CANCELLED)
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
    owner: GroupCreationSession,
) {
    val successToastResId = groupCreateSuccessToastResId(showCreatedToast, retentionOutcome)
    runCatchingCancellable {
        val item =
            owner.currentValue {
                loadCreatedGroupForOwner(appState, accountRef, groupIdHex, owner)
            }
        onRetryGroupIdCleared()
        successToastResId?.let { appState.presentConversationTransient(accountRef, groupIdHex, it) }
        onCreateCompletedOpen(item, createRequestToken)
    }.onFailure {
        if (owner.isCurrent()) appState.abandonGroupCreateTiming(ChatCreateOpenTiming.STAGE_AUTHORITATIVE_READ_FAILED)
        onAuthoritativeReadFailed(it)
    }
}

/** Same authoritative projection mapper, with a captured account and an IO-boundary owner check. */
private suspend fun loadCreatedGroupForOwner(
    appState: WhiteNoiseAppState,
    accountRef: String,
    groupIdHex: String,
    owner: GroupCreationSession,
): ChatListItem {
    owner.ensureCurrent()
    val accountHex = appState.accounts.firstOrNull { it.label == accountRef }?.accountIdHex
    appState.markChatCreateOpenStage(ChatCreateOpenTiming.STAGE_AUTHORITATIVE_READ_START)
    val details =
        appState.marmotIo {
            owner.ensureCurrent()
            groupDetails(accountRef, groupIdHex)
        }
    owner.ensureCurrent()
    appState.markChatCreateOpenStage(ChatCreateOpenTiming.STAGE_AUTHORITATIVE_READ_RETURN)
    return chatListItemFromAuthoritativeGroupDetails(details, accountHex)
}

/**
 * Final step of the New Group flow: name the group, preview the invited
 * members, and create. Disappearing messages picked here are applied after
 * the create commit and before this screen opens the resulting chat.
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
    draft: NewGroupDraft? = null,
) {
    if (appState.signOutInProgress || appState.wipeInProgress) return
    key(appState.activeAccountRef, appState.runtimeGeneration) {
        NewGroupSetupAccountScreen(
            appState,
            members,
            onBack,
            onCreateCompletedOpen,
            onCreateSubmitted,
            draft ?: rememberNewGroupDraft(initialRetryGroupIdHex),
        )
    }
}

/** Current account's setup operations; prepared images remain in the caller's unsubmitted draft. */
@Composable
@Suppress("FunctionNaming", "LongMethod", "CyclomaticComplexMethod") // Compose naming follows the framework convention.
private fun NewGroupSetupAccountScreen(
    appState: WhiteNoiseAppState,
    members: List<RecipientSearch.Candidate>,
    onBack: () -> Unit,
    onCreateCompletedOpen: (ChatListItem, Long) -> Unit,
    onCreateSubmitted: () -> Long,
    draft: NewGroupDraft,
) {
    val accountRef = appState.activeAccountRef
    val runtime = remember { appState.runtimeGeneration }
    val owner =
        remember {
            GroupCreationSession(
                nativeOwner = {
                    appState.runtimeGeneration == runtime &&
                        !appState.signOutInProgress &&
                        !appState.wipeInProgress &&
                        appState.accounts.any { it.label == accountRef && !it.signedOut }
                },
            ) {
                accountRef != null &&
                    appState.activeAccountRef == accountRef &&
                    appState.runtimeGeneration == runtime &&
                    !appState.signOutInProgress &&
                    !appState.wipeInProgress
            }
        }
    DisposableEffect(owner) { onDispose { owner.dispose() } }
    val groupName = TextFieldValue(draft.name.text.toString(), draft.name.selection)
    var imageGeneration by remember { mutableIntStateOf(0) }
    var imageError by remember { mutableStateOf(false) }
    var showPhotoMenu by remember { mutableStateOf(false) }
    var retentionSecs by draft::retentionSecs
    var showRetentionPicker by remember { mutableStateOf(false) }
    var showImagePicker by remember { mutableStateOf(false) }
    var showGroupEmojiImagePicker by remember { mutableStateOf(false) }
    var showEmojiPicker by rememberSaveable { mutableStateOf(false) }
    var imageDraft by draft::imageDraft
    var imagePreparing by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var createStage by remember { mutableStateOf<NewGroupCreateStage?>(null) }
    var retryGroupIdHex by draft::retryGroupIdHex
    var createRequestToken by draft::createRequestToken
    var error by remember { mutableStateOf<String?>(null) }
    val context = LocalContext.current
    val recentEmojiRecentsOwner = rememberRecentEmojiRecentsOwner(context)
    val imagePreview = rememberImageUploadPreview(imageDraft)

    fun createGroupErrorMessage(throwable: Throwable): String = groupCreateFailureDetail(throwable, appState::chatMemberTitle).resolve(context)

    val canCreate =
        owner.isCurrent() &&
            !draft.imageNeedsReselection &&
            canSubmitNewChatSheet(
                directMessage = false,
                busy = busy || imagePreparing,
                pendingRecipient = "",
                groupName = groupName.text,
            )
    val setupUi = newGroupSetupUiState(retryGroupIdHex, canCreate, busy)

    fun detailsEditableNow(): Boolean =
        owner.isCurrent() &&
            newGroupDetailsEditable(
                retryGroupIdHex = retryGroupIdHex,
                busy = busy,
                imagePreparing = imagePreparing,
            )
    val detailsEditable = detailsEditableNow()

    LaunchedEffect(detailsEditable) {
        if (!detailsEditable) showEmojiPicker = false
    }

    @Suppress("ReturnCount") // Early exits preserve route ownership and reject invalid or superseded actions.
    fun create(retryLoadGroupIdHex: String? = null) {
        // canCreate is a composition-time snapshot; the direct `busy` state
        // read blocks a second tap that lands before recomposition.
        val canCreateNow = !imagePreparing && !draft.imageNeedsReselection && draft.name.text.isNotBlank()
        if (!owner.isCurrent() || !canStartNewGroupCreateAttempt(busy, canCreateNow, retryLoadGroupIdHex)) return
        val account = appState.activeAccountRef ?: return
        val isRetryLoad = retryLoadGroupIdHex != null
        val submission = captureNewGroupSubmission(draft, members, imageDraft)
        val submittedRetention = retentionSecs
        busy = true
        createStage = null
        error = null
        if (!isRetryLoad) {
            createRequestToken = onCreateSubmitted()
        }
        if (!owner.isCurrent()) return
        appState.beginChatCreateOpenTiming()
        appState.launchMutation {
            try {
                runNewGroupCreateMutation(
                    appState = appState,
                    account = account,
                    groupName = submission.name,
                    description = submission.description,
                    recipients = submission.members,
                    imageDraft = submission.image,
                    retentionSecs = submittedRetention,
                    retryLoadGroupIdHex = retryLoadGroupIdHex,
                    isRetryLoad = isRetryLoad,
                    createRequestToken = createRequestToken,
                    onStage = { if (owner.isCurrent()) createStage = it },
                    onRetryGroupId = { if (owner.isCurrent()) retryGroupIdHex = it },
                    onCreateError = { if (owner.isCurrent()) error = createGroupErrorMessage(it) },
                    onCreateCompletedOpen = { item, token ->
                        if (owner.isCurrent()) {
                            owner.dispose()
                            onCreateCompletedOpen(item, token)
                        }
                    },
                    onRetryGroupIdCleared = { if (owner.isCurrent()) retryGroupIdHex = null },
                    onAuthoritativeReadFailed = { if (owner.isCurrent()) error = createGroupErrorMessage(it) },
                    owner = owner,
                )
            } finally {
                busy = false
                createStage = null
            }
        }
    }

    @Suppress("TooGenericExceptionCaught") // The callback can surface any non-cancellation preparation failure.
    fun prepareImage(load: suspend () -> ImageUploadDraft) {
        if (!detailsEditableNow()) return
        val generation = ++imageGeneration
        imageError = false
        imagePreparing = true
        appState.launchMutation {
            try {
                val prepared = owner.currentValue(load)
                if (generation != imageGeneration) return@launchMutation
                imageDraft = prepared
                draft.imageNeedsReselection = false
                showImagePicker = false
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                if (!owner.isCurrent() || generation != imageGeneration) return@launchMutation
                imageError = true
                appState.presentFailure(
                    R.string.toast_couldnt_prepare_image,
                    "NEW_GROUP_IMAGE_PREPARE",
                    error,
                )
            } finally {
                if (generation == imageGeneration) imagePreparing = false
            }
        }
    }

    // Installed unconditionally: a disabled handler would let back fall
    // through to the Activity while the create is mid-flight.
    BackHandler {
        if (!busy && owner.isCurrent()) {
            owner.dispose()
            onBack()
        }
    }

    val photoPicker =
        rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
            if (uri != null && detailsEditableNow()) {
                prepareImage { GroupImageDraftProcessor.fromContentUri(context.contentResolver, uri) }
            }
        }
    val filePicker =
        rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            if (uri != null && detailsEditableNow()) {
                prepareImage { GroupImageDraftProcessor.fromContentUri(context.contentResolver, uri) }
            }
        }
    NewGroupSetupContent(
        draft = draft,
        state =
            NewGroupSetupPresentation(
                members =
                    members.map { member ->
                        GroupCreationPerson(
                            member.copy(displayName = selectedMemberDisplayName(member, appState)),
                            appState.shortNpub(member.accountIdHex).takeIf { it.isNotBlank() },
                            selectedMemberAvatarUrl(member, appState.avatarUrl(member.accountIdHex)),
                        )
                    },
                imagePreview = imagePreview,
                imagePreparing = imagePreparing,
                imageError = imageError,
                detailsEditable = detailsEditable,
                submitEnabled = setupUi.submitEnabled && owner.isCurrent(),
                busy = busy,
                stage = createStage,
                error = error,
                retentionLabel = disappearingMessagesLabel(retentionSecs),
                emojiOpen = showEmojiPicker,
            ),
        actions =
            NewGroupSetupActions(
                back = {
                    if (!busy && owner.isCurrent()) {
                        owner.dispose()
                        onBack()
                    }
                },
                create = { create(retryLoadGroupIdHex = retryGroupIdHex) },
                photo = { if (detailsEditableNow()) showPhotoMenu = true },
                retention = { if (detailsEditableNow()) showRetentionPicker = true },
                emoji = { if (detailsEditableNow()) showEmojiPicker = true },
            ),
        photoMenu = {
            NewGroupPhotoMenu(
                expanded = showPhotoMenu && detailsEditable,
                hasImage = imageDraft != null || draft.imageNeedsReselection,
                onDismiss = { showPhotoMenu = false },
                onPhotos = {
                    if (detailsEditableNow()) {
                        photoPicker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                    }
                },
                onFiles = { if (detailsEditableNow()) filePicker.launch(arrayOf("image/*")) },
                onWeb = { if (detailsEditableNow()) showImagePicker = true },
                onEmoji = { if (detailsEditableNow()) showGroupEmojiImagePicker = true },
                onRemove = {
                    if (detailsEditableNow()) {
                        imageGeneration++
                        imageDraft = null
                        draft.imageNeedsReselection = false
                        imageError = false
                    }
                },
            )
        },
    )

    if (showRetentionPicker) {
        DisappearingMessagesPickerDialog(
            currentSecs = retentionSecs,
            onDismiss = { showRetentionPicker = false },
            onPick = { secs ->
                showRetentionPicker = false
                if (detailsEditableNow()) retentionSecs = secs
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
                if (!detailsEditableNow()) return@ImageSearchSheet
                if (picked == null) {
                    imageDraft = null
                    draft.imageNeedsReselection = false
                    imageError = false
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
            onPickEmoji = {
                if (!detailsEditableNow()) return@ImageSearchSheet
                showImagePicker = false
                showGroupEmojiImagePicker = true
            },
            onDismiss = { if (!imagePreparing) showImagePicker = false },
        )
    }

    if (showGroupEmojiImagePicker) {
        GroupEmojiImagePickerSheet(
            applyInFlight = imagePreparing,
            recentEmojis = recentEmojiRecentsOwner.recents,
            onEmojiUsed = { if (detailsEditableNow()) recentEmojiRecentsOwner.onEmojiUsed(it) },
            onApply = { prepared ->
                if (!detailsEditableNow()) return@GroupEmojiImagePickerSheet
                imageDraft = prepared
                draft.imageNeedsReselection = false
                imageError = false
                showGroupEmojiImagePicker = false
            },
            onDismiss = { if (!imagePreparing) showGroupEmojiImagePicker = false },
        )
    }

    if (showEmojiPicker && detailsEditable) {
        EmojiPickerSheet(
            onDismissRequest = { showEmojiPicker = false },
            onEmojiPicked = { emoji ->
                if (detailsEditableNow()) {
                    val current = TextFieldValue(draft.name.text.toString(), draft.name.selection)
                    val edited = insertEmojiAtSelection(current, emoji)
                    draft.name.edit {
                        replace(0, length, edited.text)
                        selection = edited.selection
                    }
                }
            },
            recentEmojis = recentEmojiRecentsOwner.recents,
            onEmojiUsed = { emoji ->
                if (detailsEditableNow()) recentEmojiRecentsOwner.onEmojiUsed(emoji)
            },
        )
    }
}
