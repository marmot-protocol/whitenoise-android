package dev.ipf.whitenoise.android.ui.conversation

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.movableContentOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp

internal val LocalConversationReaderComposer = staticCompositionLocalOf<ConversationReaderComposer?> { null }

/** One fully wired composer, moved into the reader without losing its editing or recording UI state. */
@Suppress("FunctionNaming") // Compose entry points follow the surrounding UI naming convention.
internal class ConversationReaderComposer {
    internal class Session(
        val onAfterSend: () -> Unit,
        private val dismiss: (Session) -> Unit,
        val owner: Any? = null,
    ) {
        fun onDismiss() = dismiss(this)
    }

    var session by mutableStateOf<Session?>(null)
        private set

    private var content by mutableStateOf<(@Composable () -> Unit)?>(null)
    private var dictationSendReader: Session? = null

    /** Pins media acceptance to its submitting reader, without acting on a replacement dialog. */
    internal fun captureSendAccepted(): () -> Boolean {
        val submittingReader = session
        return { onSendAccepted(submittingReader) }
    }

    internal fun onDictationSendRequested() {
        dictationSendReader = session
    }

    /** A reader must return its composer before the timeline can recycle its owning row. */
    internal fun onDictationSendAccepted(): Boolean {
        val requestedReader = dictationSendReader
        dictationSendReader = null
        return onSendAccepted(requestedReader)
    }

    private fun onSendAccepted(submittingReader: Session?): Boolean {
        val currentReader = session ?: return false
        if (submittingReader === currentReader) currentReader.onAfterSend()
        // Keep a newer/unrelated reader open rather than scrolling its row out from under its dialog.
        return true
    }

    internal fun attach(reader: Session) {
        session = reader
    }

    internal fun detach(reader: Session) {
        if (session === reader) session = null
    }

    @Composable
    fun Host(
        modifier: Modifier = Modifier,
        body: @Composable () -> Unit,
    ) {
        val latestBody by rememberUpdatedState(body)
        val motion by rememberUpdatedState(LocalComposerSendMotion.current)
        val movable =
            remember(this) {
                movableContentOf {
                    CompositionLocalProvider(
                        LocalComposerSendMotion provides motion.takeIf { session == null },
                        LocalConversationReaderComposer provides this@ConversationReaderComposer,
                    ) {
                        latestBody()
                    }
                }
            }
        SideEffect { content = movable }
        var restingHeight by remember { mutableStateOf(0.dp) }
        val density = LocalDensity.current
        Box(modifier) {
            if (session == null) {
                Box(Modifier.onSizeChanged { restingHeight = with(density) { it.height.toDp() } }) {
                    movable()
                }
            } else {
                // Keep the underlying timeline and its reader-owning row in place.
                Spacer(Modifier.height(restingHeight))
            }
        }
    }

    @Composable
    fun Reader(
        onAfterSend: () -> Unit,
        onDismiss: () -> Unit,
        owner: Any = this,
    ) {
        val latestAfterSend by rememberUpdatedState(onAfterSend)
        val latestDismiss by rememberUpdatedState(onDismiss)
        val reader =
            remember(this, owner) {
                Session(
                    onAfterSend = { latestAfterSend() },
                    dismiss = { owned ->
                        if (session === owned) {
                            // Transfer before removing the dialog, not in its later disposal frame.
                            detach(owned)
                            latestDismiss()
                        }
                    },
                    owner = owner,
                )
            }
        DisposableEffect(this, reader) {
            attach(reader)
            onDispose { detach(reader) }
        }
        if (session === reader) content?.invoke()
    }
}
