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

private data class DraftRestorationVersion(
    val owner: DraftDocumentOwner,
    val nativeRevision: Long,
    val inputsRevision: Long,
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

    private var restoredDraftRevision: DraftRestorationVersion? = null
    private var inputsRevision = 0L
    private var editsRevision = 0L
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
        if (slots != currentSlots || documentUris != currentDocumentUris) inputsRevision += 1L
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
        // Saved document grants may still work; restore their native ownership before staging.
        if (restoredDraftRevision == null) return
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

    /** Starts a user quality edit and revokes any earlier send's ownership before replacing native bytes. */
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

        editsRevision += 1L
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

    /** Revokes send ownership immediately, including removal before background preparation has completed. */
    fun releasePreparedPhoto(slotId: String) {
        editsRevision += 1L
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

    /** Fences an accepted crop or quality edit before its asynchronous native write begins. */
    fun saveEditor(
        editor: ActivePhotoEditor,
        recipe: PhotoEditRecipe,
        quality: MediaQuality,
    ) {
        if (recipe != editor.photo.recipe || quality != editor.photo.quality) editsRevision += 1L
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

    fun preparedPreviews(): Map<String, PreparedPhotoPreview> = preparedPhotoPreviews(backedPhotos, preparedPhotos)

    fun preparedQualities(): Map<String, PreparedPhotoQuality> = preparedPhotoQualities(backedPhotos, renderer)

    fun preparedAttachments() =
        backedPhotos.mapValues { (_, photo) -> photo.pendingAttachment() } +
            preparedPhotos.mapValues { (_, photo) -> photo.pendingAttachment() }

    /** Returns native-owned document bytes in the URI order used by the shelf. */
    fun preparedDocumentAttachments(): Map<Uri, PendingAttachment> {
        val documents = preparedDocuments
        return documents.mapValues { (_, document) -> document.pendingAttachment() }
    }

    /**
     * Captures picker occurrences and explicit edits for a later durable send completion. Background
     * preparation may populate bytes without replacing that owner; user edits and replacements invalidate it.
     */
    fun captureSendSettlement(): () -> Boolean {
        val revision = inputsRevision
        val account = currentAccountRef
        val edits = editsRevision
        return {
            val sameOwner =
                revision == inputsRevision && account == currentAccountRef && account == appState.activeAccountRef
            sameOwner && edits == editsRevision
        }
    }

    /** Removes a document only after an explicit shelf action, never because the screen was disposed. */
    fun releasePreparedDocument(uri: Uri) {
        editsRevision += 1L
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

    /** Reconciles native-restored shelf entries when the authoritative draft presentation changes. */
    @Suppress("LongMethod", "ReturnCount") // One guarded pass reads, materializes and publishes one native snapshot.
    suspend fun restorePersistedAttachments(
        canPublish: () -> Boolean = { true },
    ): RestoredConversationAttachments? =
        preparationMutex.withLock {
            val owner = documentOwnerFence.current() ?: return@withLock null
            val cleanupRevision = appState.nativeComposerCleanupRevision(owner.accountRef, controller.group.groupIdHex)
            val requested = DraftRestorationVersion(owner, cleanupRevision, inputsRevision)
            val restored = restoredDraftRevision
            if (restored?.owner == owner && restored.nativeRevision == requested.nativeRevision) {
                return@withLock null
            }
            val accountRef = owner.accountRef
            val attachments =
                readDraftForRestoration(appState.messageDraftRepository, accountRef, controller.group.groupIdHex)
                    .getOrElse { return@withLock null }
                    ?.mediaAttachments
                    .orEmpty()
            if (
                !documentOwnerFence.isCurrent(owner) ||
                appState.nativeComposerCleanupRevision(owner.accountRef, controller.group.groupIdHex) != cleanupRevision
            ) {
                return@withLock null
            }
            // Reconcile against picks/removals made while the native read was suspended.
            // A later change during materialization still invalidates the publication below.
            val version = requested.copy(inputsRevision = inputsRevision)
            val nativeIds = attachments.mapTo(mutableSetOf()) { it.id }
            // Only bytes restored from native state are reconciled away. Freshly
            // prepared picker occurrences survive an older send's cleanup.
            val removedPhotos = restoredPhotosMissingFrom(currentSlots, preparedPhotos, backedPhotos, nativeIds)
            val removedDocuments =
                restoredDocumentsMissingFrom(currentDocumentUris, preparedDocuments, nativeIds)
            val retainedSlots = currentSlots.filterNot { it.id in removedPhotos }
            val retainedDocuments = currentDocumentUris.filterNot { it in removedDocuments }
            val reconciliation =
                reconcilePersistedDraftAttachments(
                    accountRef = accountRef,
                    groupIdHex = controller.group.groupIdHex,
                    mediaSlotIds = retainedSlots.map(PendingMediaSlot::id),
                    documentUriStrings = retainedDocuments.map(Uri::toString),
                    attachments = attachments,
                    removedAttachmentIds = removedDraftAttachmentIds(accountRef),
                )
            val materialized =
                withContext(Dispatchers.IO) {
                    reconciliation.unmatched.mapNotNull { attachment ->
                        materializeDraftAttachment(context, attachment)?.let { attachment to it }
                    }
                }
            if (
                !restorationIsCurrent(
                    version,
                    documentOwnerFence,
                    appState.nativeComposerCleanupRevision(version.owner.accountRef, controller.group.groupIdHex),
                    inputsRevision,
                )
            ) {
                return@withLock null
            }
            // Check the screen snapshot before committing owner state or its restored version.
            // The caller applies the result on the main thread without another suspension.
            if (!canPublish()) return@withLock null
            restoredDraftRevision = version
            val restoredPhotos =
                nativePhotosNeedingRestoration(reconciliation.mediaBySlotId, backedPhotos.keys, preparedPhotos)
            preparedPhotos =
                preparedPhotos.filterKeys { it !in removedPhotos } +
                restoredPhotos.mapValues { (_, attachment) -> attachment.asRestoredPhoto() }
            preparedDocuments =
                preparedDocuments.filterKeys { it !in removedDocuments } +
                nativeDocumentsNeedingRestoration(reconciliation.documentsByUriString, preparedDocuments)
                    .mapValues { (_, attachment) -> attachment.asRestoredPhoto() }
            activeEditor?.takeIf { it.slot.id in removedPhotos }?.let(::dismissEditor)
            backedPhotos = backedPhotos.filterKeys { it !in removedPhotos }
            nonEditableDescriptions = nonEditableDescriptions.filterKeys { it !in removedPhotos }
            restoredPhotos.forEach { (slotId, attachment) ->
                if (attachment.mediaType.startsWith("image/", ignoreCase = true)) {
                    nonEditableDescriptions += slotId to messages.sourceUnavailable
                }
            }
            val media = retainedSlots.toMutableList()
            val documents = retainedDocuments.toMutableList()
            appendRestoredAttachments(accountRef, materialized, media, documents)
            RestoredConversationAttachments(media, documents)
        }

    private fun appendRestoredAttachments(
        accountRef: String,
        materialized: List<Pair<MessageDraftAttachmentFfi, Uri>>,
        media: MutableList<PendingMediaSlot>,
        documents: MutableList<Uri>,
    ) {
        materialized.forEach { (attachment, uri) ->
            if (attachment.id in removedDraftAttachmentIds(accountRef)) return@forEach
            val prepared = attachment.asRestoredPhoto()
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
                        legacyOccurrenceIndex = legacyOccurrenceIndex(currentSlots, slot),
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
        val prepared =
            stageGenericAttachment(
                appState.messageDraftRepository,
                controller.group.groupIdHex,
                accountRef,
                attachmentId,
                pending,
            ) ?: return
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
            val prepared =
                stageGenericAttachment(
                    appState.messageDraftRepository,
                    controller.group.groupIdHex,
                    accountRef,
                    attachmentId,
                    pending,
                ) ?: return
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

private fun restorationIsCurrent(
    version: DraftRestorationVersion,
    ownerFence: DraftDocumentOwnerFence,
    cleanupRevision: Long,
    inputsRevision: Long,
): Boolean =
    ownerFence.isCurrent(version.owner) &&
        cleanupRevision == version.nativeRevision &&
        inputsRevision == version.inputsRevision

private fun preparedPhotoPreviews(
    backedPhotos: Map<String, DraftBackedPhoto>,
    preparedPhotos: Map<String, DraftPreparedPhoto>,
): Map<String, PreparedPhotoPreview> =
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

private fun preparedPhotoQualities(
    backedPhotos: Map<String, DraftBackedPhoto>,
    renderer: PhotoEditorRenderer,
): Map<String, PreparedPhotoQuality> =
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

/** A native refresh cannot replace locally editable bytes or relabel freshly prepared picks. */
internal fun nativePhotosNeedingRestoration(
    nativeBySlot: Map<String, MessageDraftAttachmentFfi>,
    backedSlotIds: Set<String>,
    prepared: Map<String, DraftPreparedPhoto>,
): Map<String, MessageDraftAttachmentFfi> =
    nativeBySlot.filterKeys {
        it !in backedSlotIds && prepared[it]?.restoredFromNative != false
    }

/** Documents prepared by this composer retain their local lifetime across native refreshes. */
internal fun nativeDocumentsNeedingRestoration(
    nativeByUri: Map<String, MessageDraftAttachmentFfi>,
    prepared: Map<Uri, DraftPreparedPhoto>,
): Map<Uri, MessageDraftAttachmentFfi> =
    nativeByUri
        .mapKeys { (uri, _) -> Uri.parse(uri) }
        .filterKeys { prepared[it]?.restoredFromNative != false }

private fun MessageDraftAttachmentFfi.asRestoredPhoto(): DraftPreparedPhoto =
    DraftPreparedPhoto(
        this,
        editorDigest(),
        restoredFromNative = true,
    )

internal fun restoredPhotosMissingFrom(
    slots: List<PendingMediaSlot>,
    prepared: Map<String, DraftPreparedPhoto>,
    backed: Map<String, DraftBackedPhoto>,
    nativeIds: Set<String>,
): Set<String> =
    slots
        .filter { slot ->
            prepared[slot.id]?.let { it.restoredFromNative && it.attachment.id !in nativeIds } == true ||
                backed[slot.id]?.let { it.restoredFromNative && it.attachment.id !in nativeIds } == true
        }.mapTo(mutableSetOf()) { it.id }

private fun restoredDocumentsMissingFrom(
    documents: List<Uri>,
    prepared: Map<Uri, DraftPreparedPhoto>,
    nativeIds: Set<String>,
): Set<Uri> =
    documents
        .filter { uri ->
            prepared[uri]?.let { it.restoredFromNative && it.attachment.id !in nativeIds } == true
        }.toSet()

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
                    val canSettle = state.captureSendSettlement()
                    mediaSender.sendStagedAttachments(
                        mediaSlots,
                        documentUris,
                        caption,
                        preparedImageAttachments = state.preparedAttachments(),
                        preparedDocumentAttachments = state.preparedDocumentAttachments(),
                        onSettled = {
                            if (canSettle()) {
                                state.forgetAcceptedAttachments(
                                    mediaSlots.mapTo(linkedSetOf()) { it.id },
                                    documentUris.toSet(),
                                )
                                onMediaSlotsChange(emptyList())
                                onDocumentUrisChange(emptyList())
                                onCaptionAccepted(seededCaption)
                            }
                        },
                        onAccepted = { onResult(true) },
                        onRejected = { onResult(false) },
                        onAfterSend = onAfterSend,
                    )
                },
                onRemoveAt = { index ->
                    mediaSlots.getOrNull(index)?.id?.let(state::releasePreparedPhoto)
                    onMediaSlotsChange(mediaSlots.filterIndexed { itemIndex, _ -> itemIndex != index })
                },
                onRemoveDocumentAt = { index ->
                    documentUris.getOrNull(index)?.let(state::releasePreparedDocument)
                    onDocumentUrisChange(
                        documentUris.filterIndexed { itemIndex, _ -> itemIndex != index },
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
