@file:Suppress("FunctionNaming") // Jetpack Compose functions use UpperCamelCase.

package dev.ipf.whitenoise.android.ui.conversation

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.ui.res.stringResource
import dev.ipf.whitenoise.android.R
import dev.ipf.whitenoise.android.media.editor.DraftBackedPhoto
import dev.ipf.whitenoise.android.media.editor.DraftPreparedPhoto
import dev.ipf.whitenoise.android.media.editor.PhotoDraftStageResult
import dev.ipf.whitenoise.android.media.editor.PhotoDraftStager
import dev.ipf.whitenoise.android.media.editor.PhotoEditorCommitResult
import dev.ipf.whitenoise.android.media.editor.PhotoEditorCommitter
import dev.ipf.whitenoise.android.media.editor.PhotoEditorRenderer
import dev.ipf.whitenoise.android.media.editor.editorDigest
import dev.ipf.whitenoise.android.state.AppText
import dev.ipf.whitenoise.android.state.ConversationController
import dev.ipf.whitenoise.android.state.MediaQuality
import dev.ipf.whitenoise.android.state.WhiteNoiseAppState
import dev.ipf.whitenoise.android.ui.conversation.media.MediaPreviewScreen
import dev.ipf.whitenoise.android.ui.conversation.media.PendingMediaSlot
import dev.ipf.whitenoise.android.ui.conversation.media.PreparedPhotoPreview
import dev.ipf.whitenoise.android.ui.conversation.media.PreparedPhotoQuality
import dev.ipf.whitenoise.android.ui.conversation.media.clearMediaTempFiles
import dev.ipf.whitenoise.android.ui.conversation.media.editor.PhotoEditorDialog
import dev.ipf.whitenoise.android.ui.conversation.media.editor.PhotoEditorStateHolder
import dev.ipf.whitenoise.android.ui.conversation.media.isLegacyRestore
import dev.ipf.whitenoise.android.ui.conversation.media.photoApprovalOutputQuality
import dev.ipf.whitenoise.android.ui.conversation.media.safeGetType
import dev.ipf.whitenoise.android.ui.conversation.media.selectablePhotoQuality
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

internal data class PhotoEditorMessages(
    val animationNotEditable: String,
    val sourceNotEditable: String,
    val sourceUnavailable: String,
    val saveFailed: String,
)

internal data class ActivePhotoEditor(
    val slot: PendingMediaSlot,
    val photo: DraftBackedPhoto,
    val previewBitmap: Bitmap,
    val stateHolder: PhotoEditorStateHolder,
)

/**
 * Owns the transient photo-draft workflow for one conversation.
 *
 * Keeping this state and its editor operations out of [ConversationScreen]
 * prevents the screen composable from growing into a single oversized DEX
 * method while preserving the existing conversation-scoped lifecycle.
 */
@Stable
@Suppress("TooManyFunctions") // Intent-style draft commands form one cohesive conversation-scoped owner.
internal class ConversationMediaDraftState(
    private val appState: WhiteNoiseAppState,
    private val controller: ConversationController,
    private val context: Context,
    private val scope: CoroutineScope,
    private val messages: PhotoEditorMessages,
) {
    private val renderer = PhotoEditorRenderer()
    private val stager =
        PhotoDraftStager(
            contentResolver = context.contentResolver,
            sources = appState.editorSourceStore,
            sessions = appState.editorSessionStore,
            renderer = renderer,
            drafts = appState.messageDraftRepository,
        )
    private val committer =
        PhotoEditorCommitter(
            sources = appState.editorSourceStore,
            renderer = renderer,
            drafts = appState.messageDraftRepository,
        )

    private var currentSlots: List<PendingMediaSlot> = emptyList()
    private var currentAccountRef: String? = null

    var backedPhotos by mutableStateOf<Map<String, DraftBackedPhoto>>(emptyMap())
        private set
    var preparedPhotos by mutableStateOf<Map<String, DraftPreparedPhoto>>(emptyMap())
        private set
    var preparingSlotIds by mutableStateOf<Set<String>>(emptySet())
        private set
    var nonEditableDescriptions by mutableStateOf<Map<String, String>>(emptyMap())
        private set
    var activeEditor by mutableStateOf<ActivePhotoEditor?>(null)
        private set
    private var requestedEditorSlotId: String? = null

    fun updateInputs(
        slots: List<PendingMediaSlot>,
        accountRef: String?,
    ) {
        currentSlots = slots
        currentAccountRef = accountRef
    }

    suspend fun prepareMissingPhotos() {
        val trackedSlotIds =
            backedPhotos.keys + preparedPhotos.keys + preparingSlotIds + nonEditableDescriptions.keys
        currentSlots.forEach { slot ->
            if (slot.id !in trackedSlotIds) preparePhoto(slot)
        }
    }

    fun openEditor(slot: PendingMediaSlot) {
        if (slot.id in nonEditableDescriptions) return
        requestedEditorSlotId = slot.id
        if (slot.id !in preparingSlotIds) scope.launch { preparePhoto(slot) }
    }

    fun selectQuality(
        slot: PendingMediaSlot,
        requestedQuality: MediaQuality,
    ) {
        val photo = backedPhotos[slot.id]
        if (photo == null || slot.id in preparingSlotIds) return
        val quality = requestedQuality.selectablePhotoQuality()
        val currentlyStandard = photo.quality == MediaQuality.Low || photo.quality == MediaQuality.Standard
        val accountRef = currentAccountRef
        if (currentlyStandard == (quality == MediaQuality.Standard) || accountRef == null) return

        preparingSlotIds += slot.id
        appState.launchMutation {
            try {
                val result =
                    committer.commit(
                        accountRef = accountRef,
                        groupIdHex = controller.group.groupIdHex,
                        currentAttachment = photo.attachment,
                        expectedDigest = photo.attachmentDigest,
                        sourceLeaseId = photo.sourceLeaseId,
                        recipe = photo.recipe,
                        quality = quality,
                    )
                if (result is PhotoEditorCommitResult.Success) {
                    val updated =
                        photo.copy(
                            attachment = result.attachment,
                            attachmentDigest = result.attachment.editorDigest(),
                            quality = quality,
                        )
                    if (currentSlots.any { it.id == slot.id }) {
                        backedPhotos += slot.id to updated
                    } else {
                        stager.remove(accountRef, controller.group.groupIdHex, updated)
                    }
                } else if (currentSlots.any { it.id == slot.id }) {
                    appState.presentText(AppText.Plain(messages.saveFailed), copyable = true)
                }
            } finally {
                preparingSlotIds -= slot.id
            }
        }
    }

    fun releasePreparedPhoto(slotId: String) {
        val photo = backedPhotos[slotId]
        val prepared = preparedPhotos[slotId]
        if (photo == null && prepared == null) return

        backedPhotos -= slotId
        preparedPhotos -= slotId
        nonEditableDescriptions -= slotId
        val accountRef = currentAccountRef ?: return
        appState.launchMutation {
            if (photo != null) {
                stager.remove(accountRef, controller.group.groupIdHex, photo)
            } else if (prepared != null) {
                stager.removePrepared(accountRef, controller.group.groupIdHex, prepared)
            }
        }
    }

    fun dismissEditor() {
        activeEditor?.previewBitmap?.recycle()
        activeEditor = null
    }

    fun saveEditor(
        editor: ActivePhotoEditor,
        recipe: dev.ipf.whitenoise.android.media.editor.PhotoEditRecipe,
        quality: MediaQuality,
    ) {
        scope.launch {
            if (recipe == editor.photo.recipe && quality == editor.photo.quality) {
                dismissEditor(editor)
                return@launch
            }
            val accountRef = currentAccountRef
            if (accountRef == null) {
                editor.stateHolder.finishSaving(messages.saveFailed)
                return@launch
            }
            when (
                val result =
                    committer.commit(
                        accountRef = accountRef,
                        groupIdHex = controller.group.groupIdHex,
                        currentAttachment = editor.photo.attachment,
                        expectedDigest = editor.photo.attachmentDigest,
                        sourceLeaseId = editor.photo.sourceLeaseId,
                        recipe = recipe,
                        quality = quality,
                    )
            ) {
                is PhotoEditorCommitResult.Success -> {
                    backedPhotos +=
                        editor.slot.id to
                        editor.photo.copy(
                            attachment = result.attachment,
                            attachmentDigest = result.attachment.editorDigest(),
                            recipe = recipe,
                            quality = quality,
                        )
                    dismissEditor(editor)
                }
                else -> editor.stateHolder.finishSaving(messages.saveFailed)
            }
        }
    }

    fun preparedPreviews(): Map<String, PreparedPhotoPreview> =
        backedPhotos.mapValues { (_, photo) ->
            PreparedPhotoPreview(
                revision = photo.attachmentDigest,
                bytes = photo.attachment.plaintext,
            )
        } +
            preparedPhotos.mapValues { (_, photo) ->
                PreparedPhotoPreview(
                    revision = photo.attachmentDigest,
                    bytes = photo.attachment.plaintext,
                )
            }

    fun preparedQualities(): Map<String, PreparedPhotoQuality> =
        backedPhotos.mapValues { (_, photo) ->
            fun dimensions(quality: MediaQuality): String? =
                renderer.outputPlan(photo.sourceInfo, photo.recipe, quality)?.geometry?.outputSize?.let {
                    "${it.width} × ${it.height}"
                }
            PreparedPhotoQuality(
                selectedQuality = photo.quality,
                standardDimensions =
                    dimensions(photoApprovalOutputQuality(photo.quality, MediaQuality.Standard)),
                hdDimensions = dimensions(photoApprovalOutputQuality(photo.quality, MediaQuality.High)),
            )
        }

    fun preparedAttachments() =
        backedPhotos.mapValues { (_, photo) -> photo.pendingAttachment() } +
            preparedPhotos.mapValues { (_, photo) -> photo.pendingAttachment() }

    fun dispose() {
        activeEditor?.previewBitmap?.recycle()
        val accountRef = currentAccountRef
        val stagedPhotos = backedPhotos.values.toList()
        val readyPhotos = preparedPhotos.values.toList()
        if (accountRef != null && (stagedPhotos.isNotEmpty() || readyPhotos.isNotEmpty())) {
            appState.launchMutation {
                stagedPhotos.forEach { stager.remove(accountRef, controller.group.groupIdHex, it) }
                readyPhotos.forEach { stager.removePrepared(accountRef, controller.group.groupIdHex, it) }
            }
        }
        clearMediaTempFiles(context)
        controller.clearRetainedUploads()
    }

    @Suppress("LongMethod", "ReturnCount") // One slot-scoped coroutine owns stale-result and editor-open races.
    private suspend fun preparePhoto(slot: PendingMediaSlot) {
        val slotId = slot.id
        if (slotId in preparingSlotIds || slotId in nonEditableDescriptions) return
        val accountRef = currentAccountRef ?: return
        preparingSlotIds += slotId
        try {
            val existing = backedPhotos[slotId]
            if (existing == null) {
                val mime = withContext(Dispatchers.IO) { safeGetType(context.contentResolver, slot.uri) }
                if (mime.startsWith("video/", ignoreCase = true)) {
                    clearRequestedEditor(slotId)
                    return
                }
            }
            val staged =
                existing?.let { PhotoDraftStageResult.Success(it) }
                    ?: stager.stage(
                        uri = slot.uri,
                        attachmentSlotId = slotId,
                        accountRef = accountRef,
                        groupIdHex = controller.group.groupIdHex,
                        quality = appState.mediaQuality,
                        legacyOccurrenceIndex = legacyOccurrenceIndex(slot),
                    )
            handleStageResult(slot, accountRef, staged)
        } finally {
            preparingSlotIds -= slotId
        }
    }

    private suspend fun handleStageResult(
        slot: PendingMediaSlot,
        accountRef: String,
        result: PhotoDraftStageResult,
    ) {
        when (result) {
            is PhotoDraftStageResult.Success -> handleStagedPhoto(slot, accountRef, result.photo)
            is PhotoDraftStageResult.NotEditable -> markNotEditable(slot.id, result)
            is PhotoDraftStageResult.PreparedOnly -> markPreparedOnly(slot.id, result.photo)
            PhotoDraftStageResult.DraftUnavailable,
            PhotoDraftStageResult.SourceUnavailable,
            -> showUnavailableIfRequested(slot.id)
        }
    }

    private suspend fun handleStagedPhoto(
        slot: PendingMediaSlot,
        accountRef: String,
        photo: DraftBackedPhoto,
    ) {
        if (slot !in currentSlots) {
            stager.remove(accountRef, controller.group.groupIdHex, photo)
            clearRequestedEditor(slot.id)
            return
        }
        preparedPhotos -= slot.id
        nonEditableDescriptions -= slot.id
        backedPhotos += slot.id to photo
        if (requestedEditorSlotId != slot.id) return

        requestedEditorSlotId = null
        val sourceBytes = withContext(Dispatchers.IO) { appState.editorSourceStore.bytes(photo.sourceLeaseId) }
        val preview = sourceBytes?.let { renderer.decodePreview(it) }
        if (!currentCoroutineContext().isActive) {
            preview?.recycle()
        } else if (preview == null) {
            appState.present(R.string.toast_couldnt_decode_image, copyable = true)
        } else {
            activeEditor =
                ActivePhotoEditor(
                    slot = slot,
                    photo = photo,
                    previewBitmap = preview,
                    stateHolder =
                        PhotoEditorStateHolder(
                            initialRecipe = photo.recipe,
                            initialQuality = photo.quality,
                            orientedSize = photo.sourceInfo.orientedSize,
                        ),
                )
        }
    }

    private fun markNotEditable(
        slotId: String,
        result: PhotoDraftStageResult.NotEditable,
    ) {
        val description =
            if (result.reason == dev.ipf.whitenoise.android.media.editor.PhotoEditorSourceFailure.Animated) {
                messages.animationNotEditable
            } else {
                messages.sourceNotEditable
            }
        nonEditableDescriptions += slotId to description
        if (requestedEditorSlotId == slotId) {
            requestedEditorSlotId = null
            appState.presentText(AppText.Plain(description), copyable = true)
        }
    }

    private fun markPreparedOnly(
        slotId: String,
        photo: DraftPreparedPhoto,
    ) {
        preparedPhotos += slotId to photo
        nonEditableDescriptions += slotId to messages.sourceUnavailable
        showUnavailableIfRequested(slotId)
    }

    private fun showUnavailableIfRequested(slotId: String) {
        if (requestedEditorSlotId != slotId) return
        requestedEditorSlotId = null
        appState.presentText(AppText.Plain(messages.sourceUnavailable), copyable = true)
    }

    private fun clearRequestedEditor(slotId: String) {
        if (requestedEditorSlotId == slotId) requestedEditorSlotId = null
    }

    private fun legacyOccurrenceIndex(slot: PendingMediaSlot): Int? =
        if (slot.isLegacyRestore()) {
            currentSlots
                .takeWhile { it.id != slot.id }
                .count { it.isLegacyRestore() && it.uri == slot.uri }
        } else {
            null
        }

    private fun dismissEditor(editor: ActivePhotoEditor) {
        if (activeEditor !== editor) return
        editor.previewBitmap.recycle()
        activeEditor = null
    }
}

@Composable
internal fun rememberConversationMediaDraftState(
    appState: WhiteNoiseAppState,
    controller: ConversationController,
    chatId: String,
    mediaSlots: List<PendingMediaSlot>,
): ConversationMediaDraftState {
    val context = androidx.compose.ui.platform.LocalContext.current
    val scope = rememberCoroutineScope()
    val messages =
        PhotoEditorMessages(
            animationNotEditable = stringResource(R.string.photo_editor_not_editable_animation),
            sourceNotEditable = stringResource(R.string.photo_editor_not_editable_source),
            sourceUnavailable = stringResource(R.string.photo_editor_source_unavailable),
            saveFailed = stringResource(R.string.photo_editor_save_failed),
        )
    val state =
        remember(appState, controller, chatId, context, scope, messages) {
            ConversationMediaDraftState(appState, controller, context, scope, messages)
        }
    state.updateInputs(mediaSlots, controller.boundAccountRef)

    androidx.compose.runtime.LaunchedEffect(state, mediaSlots, controller.boundAccountRef) {
        state.prepareMissingPhotos()
    }
    DisposableEffect(state, chatId) {
        onDispose(state::dispose)
    }
    return state
}

@Composable
@Suppress("LongMethod") // Preview callbacks intentionally share one snapshot of the staged attachment list.
internal fun ConversationMediaDraftContent(
    state: ConversationMediaDraftState,
    chatId: String,
    mediaSlots: List<PendingMediaSlot>,
    documentUris: List<Uri>,
    onMediaSlotsChange: (List<PendingMediaSlot>) -> Unit,
    onDocumentUrisChange: (List<Uri>) -> Unit,
    mediaSender: ConversationMediaSender,
    chatTitle: String,
    composerText: String,
    onCaptionAccepted: (seededCaption: String) -> Unit,
    onAddPhotos: () -> Unit,
    onAddDocuments: () -> Unit,
    onAfterSend: () -> Unit,
) {
    val previewStateHolder = rememberSaveableStateHolder()
    if ((mediaSlots.isNotEmpty() || documentUris.isNotEmpty()) && state.activeEditor == null) {
        val seededCaption = composerText
        previewStateHolder.SaveableStateProvider(chatId) {
            MediaPreviewScreen(
                mediaSlots = mediaSlots,
                documentUris = documentUris,
                chatTitle = chatTitle,
                initialCaption = seededCaption,
                onDismiss = {
                    (state.backedPhotos.keys + state.preparedPhotos.keys).forEach(state::releasePreparedPhoto)
                    onMediaSlotsChange(emptyList())
                    onDocumentUrisChange(emptyList())
                },
                onSend = { caption, onResult ->
                    mediaSender.sendStagedAttachments(
                        mediaSlots,
                        documentUris,
                        caption,
                        preparedImageAttachments = state.preparedAttachments(),
                        onAccepted = {
                            (state.backedPhotos.keys + state.preparedPhotos.keys)
                                .forEach(state::releasePreparedPhoto)
                            onMediaSlotsChange(emptyList())
                            onDocumentUrisChange(emptyList())
                            onCaptionAccepted(seededCaption)
                            onResult(true)
                        },
                        onRejected = { onResult(false) },
                        onAfterSend = onAfterSend,
                    )
                },
                onRemoveAt = { index ->
                    mediaSlots.getOrNull(index)?.id?.let(state::releasePreparedPhoto)
                    onMediaSlotsChange(mediaSlots.toMutableList().apply { if (index in indices) removeAt(index) })
                },
                onRemoveDocumentAt = { index ->
                    onDocumentUrisChange(
                        documentUris.toMutableList().apply { if (index in indices) removeAt(index) },
                    )
                },
                onAddPhotos = onAddPhotos,
                onAddDocuments = onAddDocuments,
                onEditMediaAt = { index -> mediaSlots.getOrNull(index)?.let(state::openEditor) },
                onSelectMediaQuality = { slotId, quality ->
                    mediaSlots.firstOrNull { it.id == slotId }?.let { state.selectQuality(it, quality) }
                },
                preparedPhotoPreviews = state.preparedPreviews(),
                preparedPhotoQualities = state.preparedQualities(),
                preparingPhotoSlotIds = state.preparingSlotIds,
                nonEditableMediaSlotIds = state.nonEditableDescriptions.keys,
                nonEditableMediaDescriptions = state.nonEditableDescriptions,
            )
        }
    }

    state.activeEditor?.let { editor ->
        PhotoEditorDialog(
            previewBitmap = editor.previewBitmap,
            sourceInfo = editor.photo.sourceInfo,
            stateHolder = editor.stateHolder,
            onCancel = state::dismissEditor,
            onSave = { recipe, quality -> state.saveEditor(editor, recipe, quality) },
        )
    }
}
