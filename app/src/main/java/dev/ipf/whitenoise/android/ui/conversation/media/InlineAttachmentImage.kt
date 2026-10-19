package dev.ipf.whitenoise.android.ui.conversation.media

import android.graphics.drawable.Drawable
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onVisibilityChanged
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import dev.ipf.whitenoise.android.media.MediaPipeline
import dev.ipf.whitenoise.android.media.decodeOwnedMedia
import dev.ipf.whitenoise.android.state.WhiteNoiseAppState
import dev.ipf.whitenoise.android.state.isConversationReadVisible
import dev.ipf.whitenoise.android.ui.common.rememberSystemAnimationsEnabled
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

/** Only one timeline row may request the single isolated animation worker; waiters keep their still. */
private val inlineAnimationSlot = Semaphore(1)

/** Timeline cards never need the full viewer's 1080-pixel animation output. */
internal const val INLINE_ANIMATION_MAX_EDGE_PX = 512

/** A partially clipped row keeps its grant only while at least one percent remains visible. */
private const val INLINE_ANIMATION_VISIBLE_FRACTION = 0.01f

/** A row-owned, local-only source. Holding this capability never grants permission to download bytes. */
internal class InlineAnimationSource(
    val identity: Any,
    val enabled: Boolean,
    val readLocalBytes: suspend () -> ByteArray?,
)

/** Captures the row's original account/runtime, so a retained row cannot restart in a replacement session. */
@Composable
internal fun rememberInlineAnimationSource(
    identity: Any,
    appState: WhiteNoiseAppState,
    accountRef: String?,
    groupIdHex: String,
    readLocalBytes: suspend () -> ByteArray?,
): InlineAnimationSource {
    val account = remember(identity) { accountRef }
    val runtime = remember(identity) { appState.runtimeGeneration }
    val sameOwner = account != null && account == appState.activeAccountRef && runtime == appState.runtimeGeneration
    val visibleOwner = account?.let { appState.isConversationReadVisible(it, groupIdHex) } == true && appState.pendingProfileNpub == null
    return InlineAnimationSource(
        identity = identity,
        enabled = sameOwner && visibleOwner && !appState.automaticAttachmentDownloadsPaused(),
        readLocalBytes = readLocalBytes,
    )
}

/** Opens admitted bytes only through the permissionless frame worker, with thumbnail-sized output. */
internal suspend fun decodeInlineAttachmentAnimation(bytes: ByteArray): Drawable? =
    decodeOwnedMedia(
        decode = { MediaPipeline.decodeAnimatedDrawable(bytes, INLINE_ANIMATION_MAX_EDGE_PX) },
        discard = { (it as? java.io.Closeable)?.close() },
    )

/** Motion never overrides a missing source, global pause, visibility, lifecycle, or window focus. */
@Suppress("LongParameterList") // Independent host signals must all agree before requesting a grant.
internal fun inlineAnimationEligible(
    sourceEnabled: Boolean,
    animationDeferred: Boolean,
    visible: Boolean,
    foreground: Boolean,
    focused: Boolean,
    motionEnabled: Boolean,
): Boolean = sourceEnabled && animationDeferred && visible && foreground && focused && motionEnabled

/**
 * Keeps the borrowed still visible while queued or refused. A grant lasts until this row loses eligibility;
 * FIFO waiters then take over. The isolated worker independently retains admission until confirmed death.
 * Cancelling a decode or replacing the row closes even a result returned late by a non-cooperative source.
 */
@Composable
@Suppress(
    "FunctionNaming",
    "LongParameterList",
    "LongMethod",
    "TooGenericExceptionCaught",
) // One effect owns every result; optional local-read failures keep the still, while cancellation propagates.
internal fun InlineAttachmentImage(
    still: DecodedAttachmentPresentation.Static,
    source: InlineAnimationSource?,
    contentDescription: String?,
    contentScale: ContentScale,
    modifier: Modifier = Modifier,
    decode: suspend (ByteArray) -> Drawable? = ::decodeInlineAttachmentAnimation,
) {
    if (!still.animationDeferred || source == null) {
        Image(still.toImageBitmap(), contentDescription, modifier, contentScale = contentScale)
        return
    }
    // Visibility belongs to the layout node, which need not report again when its row source changes.
    var visible by remember { mutableStateOf(false) }
    val lifecycle by LocalLifecycleOwner.current.lifecycle.currentStateFlow
        .collectAsState()
    val eligible =
        inlineAnimationEligible(
            sourceEnabled = source.enabled,
            animationDeferred = still.animationDeferred,
            visible = visible,
            foreground = lifecycle.isAtLeast(Lifecycle.State.RESUMED),
            focused = LocalWindowInfo.current.isWindowFocused,
            motionEnabled = rememberSystemAnimationsEnabled(),
        )
    val drawableState = remember(source.identity, still, eligible) { mutableStateOf<Drawable?>(null) }
    val latestRead by rememberUpdatedState(source.readLocalBytes)
    LaunchedEffect(drawableState) {
        if (!eligible) return@LaunchedEffect
        inlineAnimationSlot.withPermit {
            var owned: Drawable? = null
            try {
                val bytes = latestRead() ?: return@withPermit
                currentCoroutineContext().ensureActive()
                owned = decode(bytes)
                currentCoroutineContext().ensureActive()
                // staleness-exempt: this state belongs to the exact row/eligibility effect; cancellation fences publication.
                drawableState.value = owned
                if (owned != null) awaitCancellation()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                // Local bytes may have been evicted or the worker may refuse; the admitted still remains usable.
            } finally {
                drawableState.value = null
                (owned as? java.io.Closeable)?.close()
            }
        }
    }
    val visibilityModifier = modifier.onVisibilityChanged(minFractionVisible = INLINE_ANIMATION_VISIBLE_FRACTION) { visible = it }
    val drawable = drawableState.value
    Box(visibilityModifier.clipToBounds()) {
        if (drawable == null) {
            Image(still.toImageBitmap(), contentDescription, Modifier.fillMaxSize(), contentScale = contentScale)
        } else {
            AnimatedDrawableAttachmentImage(drawable, contentDescription, contentScale, Modifier.fillMaxSize())
        }
    }
}
