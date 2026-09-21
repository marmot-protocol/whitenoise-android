@file:Suppress("FunctionNaming") // Jetpack Compose functions use UpperCamelCase.

package dev.ipf.whitenoise.android.ui.conversation

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.core.content.FileProvider
import dev.ipf.marmotkit.MessageDraftAttachmentFfi
import dev.ipf.whitenoise.android.R
import dev.ipf.whitenoise.android.media.MediaPipeline
import dev.ipf.whitenoise.android.media.editor.DraftBackedPhoto
import dev.ipf.whitenoise.android.media.editor.DraftPreparedPhoto
import dev.ipf.whitenoise.android.media.editor.MessageDraftMutationResult
import dev.ipf.whitenoise.android.media.editor.PhotoDraftStageResult
import dev.ipf.whitenoise.android.media.editor.PhotoDraftStager
import dev.ipf.whitenoise.android.media.editor.PhotoEditRecipe
import dev.ipf.whitenoise.android.media.editor.PhotoEditorCommitResult
import dev.ipf.whitenoise.android.media.editor.PhotoEditorCommitter
import dev.ipf.whitenoise.android.media.editor.PhotoEditorRenderer
import dev.ipf.whitenoise.android.media.editor.PhotoEditorSourceFailure
import dev.ipf.whitenoise.android.media.editor.editorDigest
import dev.ipf.whitenoise.android.media.editor.stagedPhotoAttachmentId
import dev.ipf.whitenoise.android.state.AppText
import dev.ipf.whitenoise.android.state.ConversationController
import dev.ipf.whitenoise.android.state.MediaQuality
import dev.ipf.whitenoise.android.state.PendingAttachment
import dev.ipf.whitenoise.android.state.WhiteNoiseAppState
import dev.ipf.whitenoise.android.ui.conversation.composer.ComposerAcceptanceToken
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
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.nio.charset.StandardCharsets
import java.util.UUID

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

internal data class RestoredConversationAttachments(
    val mediaSlots: List<PendingMediaSlot>,
    val documentUris: List<Uri>,
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
    private val attachmentReader = ConversationAttachmentReader(appState, context)

    private var currentSlots: List<PendingMediaSlot> = emptyList()
    private var currentDocumentUris: List<Uri> = emptyList()
    private var currentAccountRef: String? = null
    private var restoreAttempted = false
    private val preparationMutex = Mutex()
    private val documentOwnerFence = DraftDocumentOwnerFence()
    private var documentRemovalFence = DraftDocumentRemovalFence()

    var backedPhotos by mutableStateOf<Map<String, DraftBackedPhoto>>(emptyMap())
        private set
    var preparedPhotos by mutableStateOf<Map<String, DraftPreparedPhoto>>(emptyMap())
        private set
    var preparedDocuments by mutableStateOf<Map<Uri, DraftPreparedPhoto>>(emptyMap())
        private set
    var preparingSlotIds by mutableStateOf<Set<String>>(emptySet())
        private set
    var nonEditableDescriptions by mutableStateOf<Map<String, String>>(emptyMap())
        private set
    var activeEditor by mutableStateOf<ActivePhotoEditor?>(null)
        private set
    private var requestedEditorSlotId: String? = null

    /** Refreshes the latest shelf projection without making it the byte owner. */
    fun updateInputs(
        slots: List<PendingMediaSlot>,
        documentUris: List<Uri>,
        accountRef: String?,
    ) {
        val ownerChanged = documentOwnerFence.update(accountRef)
        if (ownerChanged) documentRemovalFence = DraftDocumentRemovalFence()
        documentRemovalFence.updateInputs(
            previousUris = if (ownerChanged) emptyList() else currentDocumentUris.map(Uri::toString),
            currentUris = documentUris.map(Uri::toString),
        )
        currentSlots = slots
        currentDocumentUris = documentUris
        currentAccountRef = accountRef
    }

    /** Stages picks on the process lifetime so navigation cannot cancel native-draft persistence. */
    fun prepareMissingAttachments() {
        appState.launchMutation {
            preparationMutex.withLock { prepareMissingAttachmentsNow() }
        }
    }

    /** Serializes draft insertion so native attachment order matches the composer shelf. */
    private suspend fun prepareMissingAttachmentsNow() {
        val owner = documentOwnerFence.current() ?: return
        val removalFence = documentRemovalFence
        val trackedSlotIds =
            backedPhotos.keys + preparedPhotos.keys + preparingSlotIds + nonEditableDescriptions.keys
        currentSlots.forEach { slot ->
            if (slot.id !in trackedSlotIds) preparePhoto(slot)
        }
        currentDocumentUris.forEach { uri ->
            val documentId =
                stagedDocumentAttachmentId(
                    owner.accountRef,
                    controller.group.groupIdHex,
                    uri.toString(),
                )
            if (
                uri !in preparedDocuments &&
                documentId !in preparingSlotIds &&
                removalFence.canPublish(uri.toString(), currentDocumentUris.map(Uri::toString))
            ) {
                prepareDocument(uri, documentId, owner, removalFence)
            }
        }
    }

    val isPreparing: Boolean
        get() = preparingSlotIds.isNotEmpty()

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
        recipe: PhotoEditRecipe,
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

    /** Returns native-owned document bytes in the URI order used by the shelf. */
    fun preparedDocumentAttachments(): Map<Uri, PendingAttachment> {
        val documents = preparedDocuments
        return documents.mapValues { (_, document) -> document.pendingAttachment() }
    }

    /** Removes a document only after an explicit shelf action, never because the screen was disposed. */
    fun releasePreparedDocument(uri: Uri) {
        val uriString = uri.toString()
        val removalFence = documentRemovalFence
        val ownedRemoval = documentOwnerFence.recordRemoval(uriString, removalFence) ?: return
        val document = preparedDocuments[uri]
        preparedDocuments -= uri
        val owner = ownedRemoval.owner
        val accountRef = owner.accountRef
        val groupId = controller.group.groupIdHex
        val attachmentId =
            document?.attachment?.id
                ?: stagedDocumentAttachmentId(accountRef, groupId, uriString)
        val sharedRemoval =
            appState.draftAttachmentRemovalTombstones.begin(
                accountRef,
                groupId,
                attachmentId,
            )
        appState.launchMutation {
            preparationMutex.withLock {
                val removed =
                    document ?: appState.messageDraftRepository
                        .draft(accountRef, groupId)
                        .getOrNull()
                        ?.mediaAttachments
                        ?.firstOrNull { it.id == attachmentId }
                        ?.let { DraftPreparedPhoto(it, it.editorDigest()) }
                if (removed != null) stager.removePrepared(accountRef, groupId, removed)
                appState.draftAttachmentRemovalTombstones.complete(sharedRemoval)
                removalFence.completeRemoval(ownedRemoval.removal)
                val currentUris = currentDocumentUris.map(Uri::toString)
                if (
                    uri !in preparedDocuments &&
                    canPublishDocument(uriString, currentUris, owner, removalFence)
                ) {
                    prepareDocument(uri, attachmentId, owner, removalFence)
                }
            }
        }
    }

    /** Drops screen projections after optimistic acceptance while MDK owns bytes until durable send cleanup. */
    fun forgetAcceptedAttachments(
        mediaSlotIds: Set<String>,
        documentUris: Set<Uri>,
    ) {
        backedPhotos = backedPhotos.filterKeys { it !in mediaSlotIds }
        preparedPhotos = preparedPhotos.filterKeys { it !in mediaSlotIds }
        nonEditableDescriptions = nonEditableDescriptions.filterKeys { it !in mediaSlotIds }
        preparedDocuments = preparedDocuments.filterKeys { it !in documentUris }
    }

    /** Rehydrates the composer shelf from authoritative native bytes after navigation or process recreation. */
    @Suppress("LongMethod", "ReturnCount") // One locked pass reconnects, materializes, and publishes one snapshot.
    suspend fun restorePersistedAttachments(): RestoredConversationAttachments? =
        preparationMutex.withLock {
            if (restoreAttempted) return@withLock null
            val accountRef = currentAccountRef ?: return@withLock null
            restoreAttempted = true
            val attachments =
                appState.messageDraftRepository
                    .draft(accountRef, controller.group.groupIdHex)
                    .getOrNull()
                    ?.mediaAttachments
                    .orEmpty()
            if (attachments.isEmpty()) return@withLock null

            val reconciliation =
                reconcilePersistedDraftAttachments(
                    accountRef = accountRef,
                    groupIdHex = controller.group.groupIdHex,
                    mediaSlotIds = currentSlots.map(PendingMediaSlot::id),
                    documentUriStrings = currentDocumentUris.map(Uri::toString),
                    attachments = attachments,
                    removedAttachmentIds =
                        removedDraftAttachmentIds(accountRef),
                )
            val restoredPhotos =
                reconciliation.mediaBySlotId.mapValues { (_, attachment) ->
                    DraftPreparedPhoto(attachment, attachment.editorDigest())
                }
            val restoredDocuments =
                reconciliation.documentsByUriString
                    .mapKeys { (uri, _) -> Uri.parse(uri) }
                    .mapValues { (_, attachment) ->
                        DraftPreparedPhoto(attachment, attachment.editorDigest())
                    }
            restoredPhotos.forEach { (slotId, prepared) ->
                if (prepared.attachment.mediaType.startsWith("image/", ignoreCase = true)) {
                    nonEditableDescriptions += slotId to messages.sourceUnavailable
                }
            }
            preparedPhotos += restoredPhotos
            preparedDocuments += restoredDocuments

            val materialized =
                withContext(Dispatchers.IO) {
                    reconciliation.unmatched.mapNotNull { attachment ->
                        materializeDraftAttachment(context, attachment)?.let { attachment to it }
                    }
                }
            val media = currentSlots.toMutableList()
            val documents = currentDocumentUris.toMutableList()
            materialized.forEach { (attachment, uri) ->
                val removedAttachmentIds =
                    removedDraftAttachmentIds(accountRef)
                if (attachment.id in removedAttachmentIds) {
                    return@forEach
                }
                val prepared = DraftPreparedPhoto(attachment, attachment.editorDigest())
                if (attachment.isComposerVisual() && !attachment.isComposerDocument()) {
                    media += PendingMediaSlot(attachment.id, uri)
                    preparedPhotos += attachment.id to prepared
                    if (attachment.mediaType.startsWith("image/", ignoreCase = true)) {
                        nonEditableDescriptions += attachment.id to messages.sourceUnavailable
                    }
                } else {
                    if (uri !in documents) documents += uri
                    preparedDocuments += uri to prepared
                }
            }
            RestoredConversationAttachments(media, documents)
        }

    /** Combines this composer's fences with process-owned cleanup still running for the account. */
    private fun removedDraftAttachmentIds(accountRef: String): Set<String> =
        documentRemovalFence.removedAttachmentIds(accountRef, controller.group.groupIdHex) +
            appState.draftAttachmentRemovalTombstones.attachmentIds(accountRef, controller.group.groupIdHex)

    /** Releases presentation resources without deleting the native attachment draft. */
    fun dispose() {
        activeEditor?.previewBitmap?.recycle()
        if (currentSlots.isEmpty() && currentDocumentUris.isEmpty()) {
            clearMediaTempFiles(context)
        } else {
            runCatching { File(context.cacheDir, "composer_paste/native_drafts").deleteRecursively() }
        }
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
                    stageVisualAttachment(slot, accountRef)
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
            is PhotoDraftStageResult.NotEditable -> {
                markNotEditable(slot.id, result)
                stageVisualAttachment(slot, accountRef)
            }
            is PhotoDraftStageResult.PreparedOnly -> markPreparedOnly(slot.id, result.photo)
            PhotoDraftStageResult.DraftUnavailable,
            PhotoDraftStageResult.SourceUnavailable,
            -> {
                stageVisualAttachment(slot, accountRef)
                showUnavailableIfRequested(slot.id)
            }
        }
    }

    /** Persists video and non-editable image bytes using the send-time media pipeline. */
    private suspend fun stageVisualAttachment(
        slot: PendingMediaSlot,
        accountRef: String,
    ) {
        val pending = attachmentReader.readVisualDraft(slot.uri) ?: return
        val attachmentId =
            stagedPhotoAttachmentId(
                accountRef,
                controller.group.groupIdHex,
                slot.id,
            )
        val prepared = stageGenericAttachment(accountRef, attachmentId, pending) ?: return
        if (currentSlots.any { it.id == slot.id }) {
            preparedPhotos += slot.id to prepared
        } else {
            stager.removePrepared(accountRef, controller.group.groupIdHex, prepared)
        }
    }

    /** Persists one document before its picker grant can be revoked. */
    @Suppress("ReturnCount") // Missing owner, unreadable picker data, and draft failure are distinct no-op exits.
    private suspend fun prepareDocument(
        uri: Uri,
        attachmentId: String,
        owner: DraftDocumentOwner,
        removalFence: DraftDocumentRemovalFence,
    ) {
        if (!documentOwnerFence.isCurrent(owner) || removalFence !== documentRemovalFence) return
        val accountRef = owner.accountRef
        preparingSlotIds += attachmentId
        try {
            val pending = attachmentReader.readDocumentDraft(uri) ?: return
            val prepared = stageGenericAttachment(accountRef, attachmentId, pending) ?: return
            val currentUris = currentDocumentUris.map(Uri::toString)
            if (canPublishDocument(uri.toString(), currentUris, owner, removalFence)) {
                preparedDocuments += uri to prepared
            } else {
                stager.removePrepared(accountRef, controller.group.groupIdHex, prepared)
            }
        } finally {
            preparingSlotIds -= attachmentId
        }
    }

    /** Rejects results from an old account lifetime or its detached removal fence. */
    private fun canPublishDocument(
        uri: String,
        currentUris: List<String>,
        owner: DraftDocumentOwner,
        removalFence: DraftDocumentRemovalFence,
    ): Boolean =
        documentOwnerFence.isCurrent(owner) &&
            removalFence === documentRemovalFence &&
            removalFence.canPublish(uri, currentUris)

    /** Adds generic video/document bytes idempotently and recovers the authoritative duplicate. */
    private suspend fun stageGenericAttachment(
        accountRef: String,
        attachmentId: String,
        pending: PendingAttachment,
    ): DraftPreparedPhoto? {
        val attachment = pending.toMessageDraftAttachment(attachmentId)
        val committed =
            when (
                appState.messageDraftRepository.addAttachment(
                    accountRef,
                    controller.group.groupIdHex,
                    attachment,
                )
            ) {
                is MessageDraftMutationResult.Success -> attachment
                MessageDraftMutationResult.DuplicateAttachment ->
                    appState.messageDraftRepository
                        .draft(accountRef, controller.group.groupIdHex)
                        .getOrNull()
                        ?.mediaAttachments
                        ?.firstOrNull { it.id == attachmentId }
                else -> null
            } ?: return null
        return DraftPreparedPhoto(committed, committed.editorDigest())
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
            if (result.reason == PhotoEditorSourceFailure.Animated) {
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
    documentUris: List<Uri>,
): ConversationMediaDraftState {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val messages =
        PhotoEditorMessages(
            animationNotEditable = stringResource(R.string.photo_editor_not_editable_animation),
            sourceNotEditable = stringResource(R.string.photo_editor_not_editable_source),
            sourceUnavailable = stringResource(R.string.photo_editor_source_unavailable),
            saveFailed = stringResource(R.string.photo_editor_save_failed),
        )
    val state =
        remember(appState, controller, chatId, controller.boundAccountRef, context, scope, messages) {
            ConversationMediaDraftState(appState, controller, context, scope, messages)
        }
    SideEffect {
        state.updateInputs(mediaSlots, documentUris, controller.boundAccountRef)
    }

    LaunchedEffect(state, mediaSlots, documentUris, controller.boundAccountRef) {
        state.prepareMissingAttachments()
    }
    DisposableEffect(state, chatId) {
        onDispose(state::dispose)
    }
    return state
}

/** Materializes native draft bytes only while their composer is visible. */
private fun materializeDraftAttachment(
    context: Context,
    attachment: MessageDraftAttachmentFfi,
): Uri? =
    runCatching {
        val directory = File(context.cacheDir, "composer_paste/native_drafts").apply { mkdirs() }
        val safeExtension =
            attachment.fileName
                .substringAfterLast('.', "")
                .lowercase()
                .takeIf { it.length in 1..8 && it.all(Char::isLetterOrDigit) }
                ?.let { ".$it" }
                .orEmpty()
        val stableName =
            UUID.nameUUIDFromBytes(attachment.id.toByteArray(StandardCharsets.UTF_8)).toString()
        val file = File(directory, "$stableName$safeExtension")
        file.writeBytes(attachment.plaintext)
        FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            file,
            MediaPipeline.safeDisplayName(attachment.fileName),
        )
    }.getOrNull()

/** Retains one caption acceptance generation across preview recompositions and staged-media edits. */
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
    composerText: () -> ComposerAcceptanceToken,
    onCaptionAccepted: (seededCaption: ComposerAcceptanceToken) -> Unit,
    onAddPhotos: () -> Unit,
    onAddDocuments: () -> Unit,
    onAfterSend: () -> Unit,
    previewIndex: Int? = 0,
    onClosePreview: (() -> Unit)? = null,
) {
    val previewStateHolder = rememberSaveableStateHolder()
    val previewKey =
        rememberSaveable(previewIndex) {
            java.util.UUID
                .randomUUID()
                .toString()
        }
    DisposableEffect(previewKey, onClosePreview != null) {
        onDispose {
            if (onClosePreview != null) previewStateHolder.removeState(previewKey)
        }
    }
    val hasStagedAttachments = mediaSlots.isNotEmpty() || documentUris.isNotEmpty()
    if (previewIndex != null && hasStagedAttachments && state.activeEditor == null) {
        // Preserve the caption's acceptance generation for the lifetime of this
        // preview; recomposition must not rebind its eventual callback to newer text.
        val seededCaption = remember(state, chatId) { composerText() }
        val preparedPreviews =
            remember(state.backedPhotos, state.preparedPhotos) {
                state.preparedPreviews()
            }
        val preparedQualities =
            remember(state.backedPhotos) {
                state.preparedQualities()
            }
        previewStateHolder.SaveableStateProvider(if (onClosePreview == null) chatId else previewKey) {
            MediaPreviewScreen(
                mediaSlots = mediaSlots,
                documentUris = documentUris,
                chatTitle = chatTitle,
                initialCaption = seededCaption.text,
                previewOnly = onClosePreview != null,
                initialIndex = previewIndex,
                onDismiss = {
                    if (onClosePreview != null) {
                        onClosePreview()
                    } else {
                        (state.backedPhotos.keys + state.preparedPhotos.keys).forEach(state::releasePreparedPhoto)
                        documentUris.forEach(state::releasePreparedDocument)
                        onMediaSlotsChange(emptyList())
                        onDocumentUrisChange(emptyList())
                    }
                },
                onSend = { caption, onResult ->
                    mediaSender.sendStagedAttachments(
                        mediaSlots,
                        documentUris,
                        caption,
                        preparedImageAttachments = state.preparedAttachments(),
                        preparedDocumentAttachments = state.preparedDocumentAttachments(),
                        onAccepted = {
                            state.forgetAcceptedAttachments(
                                mediaSlots.mapTo(linkedSetOf()) { it.id },
                                documentUris.toSet(),
                            )
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
                    documentUris.getOrNull(index)?.let(state::releasePreparedDocument)
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
                preparedPhotoPreviews = preparedPreviews,
                preparedPhotoQualities = preparedQualities,
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
