package dev.ipf.whitenoise.android.ui.conversation.media

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import dev.ipf.marmotkit.MediaAttachmentReferenceFfi
import dev.ipf.whitenoise.android.R
import dev.ipf.whitenoise.android.audio.tts.TtsState
import dev.ipf.whitenoise.android.state.ConversationController
import dev.ipf.whitenoise.android.state.WhiteNoiseAppState
import dev.ipf.whitenoise.android.state.attachmentsFor
import dev.ipf.whitenoise.android.state.runCatchingCancellable
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/** Exact parent-owned source actions, revoked when their file row is replaced or detached. */
internal class TextAttachmentNativeActions(
    private val sourceIsCurrent: () -> Boolean,
    private val saveSource: suspend () -> Unit,
) {
    private var attached = true
    private var saving = false

    /** True while the actions are attached and their source message is still current. */
    fun isCurrent(): Boolean = attached && sourceIsCurrent()

    /** Serializes explicit saves and keeps cancellation separate from native export failures. */
    suspend fun save() {
        if (!isCurrent() || saving) return
        saving = true
        try {
            saveSource()
        } finally {
            saving = false
        }
    }

    /** Invalidates captured save/read-aloud callbacks without touching another source's native transfer. */
    fun release() {
        attached = false
    }
}

/** Prevents a suspended native download from publishing after its reader owner has changed. */
internal suspend fun <T> saveOwnedTextAttachment(
    isCurrent: () -> Boolean,
    materialize: suspend () -> T,
    publish: suspend (T) -> Unit,
) {
    if (!isCurrent()) throw CancellationException("text reader owner changed")
    val source = materialize()
    currentCoroutineContext().ensureActive()
    if (!isCurrent()) throw CancellationException("text reader owner changed")
    publish(source)
}

/** Reuses the native Downloads publisher and cancellation-aware SAF fallback for this exact attachment index. */
@Composable
@Suppress("LongParameterList", "LongMethod", "CyclomaticComplexMethod")
internal fun rememberTextAttachmentNativeActions(
    controller: ConversationController,
    appState: WhiteNoiseAppState,
    messageIdHex: String,
    attachmentIndex: Int,
    reference: MediaAttachmentReferenceFfi,
    mine: Boolean,
): TextAttachmentNativeActions {
    val context = LocalContext.current
    val fallback = rememberDocumentSaveFallback()
    val actions =
        remember(controller, messageIdHex, attachmentIndex, reference, mine, fallback) {
            val account = controller.boundAccountRef
            val runtime = appState.runtimeGeneration
            val group = controller.group.groupIdHex
            lateinit var owned: TextAttachmentNativeActions

            /** Whether the source message still exists and has not expired. */
            fun sourceIsCurrent(): Boolean {
                val item = controller.timeline.firstOrNull { it.record.messageIdHex == messageIdHex }
                val expiry = item?.record?.retentionExpiresAt?.takeIf { it > 0uL }
                val now = (System.currentTimeMillis().coerceAtLeast(0L) / 1_000L).toULong()
                val recordEpoch = item?.record?.sourceEpoch?.takeIf { it > 0uL }
                return account != null &&
                    appState.activeAccountRef == account &&
                    appState.runtimeGeneration == runtime &&
                    controller.boundAccountRef == account &&
                    controller.group.groupIdHex == group &&
                    item != null &&
                    messageIdHex !in controller.deletedMessageIds &&
                    messageIdHex !in controller.pendingTimelineRemovedMessageIds &&
                    item.projected?.deleted != true &&
                    item.projected?.invalidationStatus == null &&
                    (expiry == null || expiry > now) &&
                    (recordEpoch == null || recordEpoch == reference.sourceEpoch) &&
                    controller.isMessageMine(item.record) == mine &&
                    controller.attachmentsFor(item).firstOrNull { it.index == attachmentIndex }?.value == reference
            }
            owned =
                TextAttachmentNativeActions(::sourceIsCurrent) {
                    val outcome =
                        runCatchingCancellable {
                            saveOwnedTextAttachment(
                                isCurrent = owned::isCurrent,
                                materialize = {
                                    materializeDocumentAttachment(context, messageIdHex, attachmentIndex, reference) {
                                        requireNotNull(
                                            loadMediaFileBytes(
                                                controller,
                                                messageIdHex,
                                                attachmentIndex,
                                                reference,
                                                mine,
                                            ),
                                        )
                                    }
                                },
                                publish = { file ->
                                    val saved =
                                        saveDocumentWithFallback(
                                            context,
                                            file,
                                            reference.fileName,
                                            reference.mediaType,
                                            fallback,
                                        )
                                    check(saved) {
                                        "MediaStore save returned false"
                                    }
                                },
                            )
                        }
                    if (owned.isCurrent()) {
                        appState.presentMediaSaveOutcome(
                            outcome,
                            R.string.shared_media_saved,
                            R.string.shared_media_save_failed,
                            "TEXT_READER_SAVE",
                        )
                    }
                }
            owned
        }
    DisposableEffect(actions) { onDispose { actions.release() } }
    return actions
}

/** Toggle only this native attachment source; another message's queue must never be stopped by this reader. */
internal fun textAttachmentOwnsSpeech(
    state: TtsState,
    messageIdHex: String,
    attachmentIndex: Int,
): Boolean =
    (state is TtsState.Speaking || state is TtsState.Paused) &&
        state.passage?.messageIdHex == "attachment:$messageIdHex:$attachmentIndex"

/** Selected visible text enters native speech projection without including unselected Markdown. */
internal fun textAttachmentSelectedPreview(
    preview: TextAttachmentPreview,
    selectedText: String,
): TextAttachmentPreview {
    if (selectedText == preview.text) return preview
    return preview.copy(text = selectedText, markdownDocument = null)
}
