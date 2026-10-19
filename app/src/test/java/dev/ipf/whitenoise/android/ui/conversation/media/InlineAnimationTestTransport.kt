package dev.ipf.whitenoise.android.ui.conversation.media

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.drawable.Drawable
import dev.ipf.whitenoise.android.media.sandbox.AnimationSessionControl
import dev.ipf.whitenoise.android.media.sandbox.IsolatedAnimationDrawable
import dev.ipf.whitenoise.android.media.sandbox.MediaDecodeTransport
import dev.ipf.whitenoise.android.media.sandbox.TestMediaDecodeTransport
import java.util.concurrent.CopyOnWriteArrayList

/** Replaces only IPC in row integration tests, returning the production raw-pixel drawable. */
internal class InlineAnimationTestTransport : MediaDecodeTransport by TestMediaDecodeTransport {
    val animations = CopyOnWriteArrayList<IsolatedAnimationDrawable>()

    /** Rejects an accidental viewer-sized inline request and returns a controllable copied-pixel frame. */
    override fun animation(
        bytes: ByteArray,
        edge: Int,
    ): Drawable {
        check(edge == INLINE_ANIMATION_MAX_EDGE_PX)
        val session =
            object : AnimationSessionControl {
                private var open = true

                /** Playback is driven by explicit raw frame delivery in these JVM tests. */
                override fun play() = Unit

                /** The drawable itself rejects late frames when paused. */
                override fun pause() = Unit

                /** A retired test worker cannot restart. */
                override fun isOpen() = open

                /** Records retirement without launching a real Binder worker. */
                override fun close() {
                    open = false
                }
            }
        return IsolatedAnimationDrawable(pixels(Color.RED), session).also(animations::add)
    }

    /** Generates one solid-colour frame for visible before/after assertions. */
    fun pixels(color: Int): Bitmap = Bitmap.createBitmap(2, 2, Bitmap.Config.ARGB_8888).apply { eraseColor(color) }
}

/** Publishes synthetic host visibility without starting the app's native foreground services. */
internal fun showInlineTestConversation(
    state: dev.ipf.whitenoise.android.state.WhiteNoiseAppState,
    group: String?,
) {
    val visibility =
        dev.ipf.whitenoise.android.state.NotificationSuppression(
            inForeground = true,
            activeConversationAccountRef = state.activeAccountRef,
            activeConversationGroupIdHex = group,
        )
    state.javaClass
        .getDeclaredMethod("updateNotificationSuppression", visibility.javaClass)
        .apply { isAccessible = true }
        .invoke(state, visibility)
}
