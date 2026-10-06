package dev.ipf.whitenoise.android.ui.profile

import android.content.ContentResolver
import android.net.Uri
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import dev.ipf.whitenoise.android.media.IdentityImageCrop
import dev.ipf.whitenoise.android.media.IdentityImageCropSource
import dev.ipf.whitenoise.android.media.editor.PhotoEditorInspectResult
import dev.ipf.whitenoise.android.media.editor.PhotoEditorRenderer
import dev.ipf.whitenoise.android.media.preparePrivateContactPicture
import dev.ipf.whitenoise.android.media.readIdentityImageSource
import dev.ipf.whitenoise.android.state.ContactPictureChange
import dev.ipf.whitenoise.android.state.ContactPictureReference
import dev.ipf.whitenoise.android.state.ContactPictureStore
import dev.ipf.whitenoise.android.state.runCatchingCancellable
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Transient local editing state; Cancel simply drops these bytes without creating a file. */
internal data class ContactPictureEditorState(
    val hasPicture: Boolean = false,
    val preview: ImageBitmap? = null,
    val crop: IdentityImageCropSource? = null,
    val busy: Boolean = false,
    val failed: Boolean = false,
)

/** Owns picker/render results for one contact editor and rejects them as soon as its owner expires. */
internal class ContactPictureEditorController(
    private val store: ContactPictureStore,
    private val initial: ContactPictureReference?,
    private val scope: CoroutineScope,
    private val ownerIsCurrent: () -> Boolean,
) {
    @Volatile private var active = true
    private var change: ContactPictureChange = ContactPictureChange.Keep
    private var sourceBytes: ByteArray? = null
    private val mutableState = MutableStateFlow(ContactPictureEditorState(hasPicture = initial != null))
    val state = mutableState.asStateFlow()

    /** Invalidate before cancelling the composition's jobs, including queued storage writes. */
    fun dispose() {
        active = false
    }

    /** Closing the editor revokes queued picker/Save callbacks before the UI removes its composition. */
    fun dismiss(onDismiss: () -> Unit) {
        dispose()
        onDismiss()
    }

    /** Scope predicate is also passed through to the durable atomic commit. */
    fun isCurrent(): Boolean = active && ownerIsCurrent()

    /** Read at most the identity-source cap from the system picker; never persist its URI. */
    fun pick(
        resolver: ContentResolver,
        uri: Uri,
    ) = edit {
        openCrop(readIdentityImageSource(resolver, uri))
    }

    /** Reposition the in-memory original draft, or the bounded saved square after reopening. */
    fun reposition() =
        edit {
            val source = sourceBytes ?: withContext(Dispatchers.IO) { initial?.let(store::read) }
            openCrop(checkNotNull(source))
        }

    private suspend fun openCrop(bytes: ByteArray) {
        val renderer = PhotoEditorRenderer()
        val inspected = renderer.inspect(bytes) as? PhotoEditorInspectResult.Success
        val preview = checkNotNull(renderer.decodePreview(bytes))
        val source = IdentityImageCropSource(bytes, preview, checkNotNull(inspected).source.orientedSize)
        if (isCurrent()) mutableState.value = mutableState.value.copy(crop = source)
    }

    /** Crop confirmation replaces only the editor draft; Save remains the sole persistence action. */
    fun confirm(crop: IdentityImageCrop) =
        edit {
            val source = checkNotNull(mutableState.value.crop)
            val normalized = preparePrivateContactPicture(source.bytes, crop)
            val preview = checkNotNull(PhotoEditorRenderer().decodePreview(normalized)).asImageBitmap()
            if (isCurrent()) {
                sourceBytes = source.bytes
                change = ContactPictureChange.Replace(normalized)
                mutableState.value = mutableState.value.copy(hasPicture = true, preview = preview, crop = null)
            }
        }

    /** Dismissing crop preserves the last accepted draft and stored image. */
    fun dismissCrop() {
        if (isCurrent() && !mutableState.value.busy) mutableState.value = mutableState.value.copy(crop = null)
    }

    /** Clear is also a draft: the published avatar becomes visible only after Save. */
    fun clear() {
        if (!isCurrent() || mutableState.value.busy) return
        change = ContactPictureChange.Clear
        sourceBytes = null
        mutableState.value = ContactPictureEditorState()
    }

    /** Serializes Save with image preparation; failed saves retain all editable values for retry. */
    fun save(
        commit: suspend (ContactPictureChange, () -> Boolean) -> Boolean,
        onSaved: () -> Unit,
    ) = edit {
        check(commit(change, ::isCurrent))
        if (isCurrent()) onSaved()
    }

    /** Show a stable localized failure without exposing picker URIs, paths, or decoder exceptions. */
    private fun edit(operation: suspend () -> Unit) {
        if (!isCurrent() || mutableState.value.busy) return
        mutableState.value = mutableState.value.copy(busy = true, failed = false)
        scope.launch {
            try {
                runCatchingCancellable { operation() }.onFailure {
                    if (isCurrent()) mutableState.value = mutableState.value.copy(failed = true)
                }
            } finally {
                if (isCurrent()) mutableState.value = mutableState.value.copy(busy = false)
            }
        }
    }
}
