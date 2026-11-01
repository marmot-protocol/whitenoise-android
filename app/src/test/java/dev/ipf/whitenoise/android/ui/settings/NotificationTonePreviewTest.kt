package dev.ipf.whitenoise.android.ui.settings

import android.content.Context
import android.media.MediaPlayer
import dev.ipf.whitenoise.android.notifications.NotificationTone
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowMediaPlayer
import org.robolectric.util.ReflectionHelpers

/** Delayed native media callbacks cannot restart a stopped or replaced screen-owned audition. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30])
class NotificationTonePreviewTest {
    @Test
    fun replacingAndLeavingReleasePlayersAndIgnoreLateCallbacks() {
        val players = mutableListOf<Pair<MediaPlayer, ShadowMediaPlayer>>()
        ShadowMediaPlayer.setMediaInfoProvider { ShadowMediaPlayer.MediaInfo(650, 100) }
        ShadowMediaPlayer.setCreateListener { player, shadow -> players += player to shadow }
        try {
            val context: Context = RuntimeEnvironment.getApplication()
            val preview = NotificationTonePreview(context)
            var finished = 0
            var failures = 0
            preview.play(NotificationTone.DING, onFailure = { failures++ }) { finished++ }
            val first = players.single()
            val stalePrepared = first.second.onPreparedListener
            val staleFinished = first.second.onCompletionListener
            val staleError = ReflectionHelpers.getField<MediaPlayer.OnErrorListener>(first.second, "errorListener")
            preview.play(NotificationTone.PULSE, onFailure = { failures++ }) { finished++ }
            assertEquals(ShadowMediaPlayer.State.END, first.second.state)
            stalePrepared.onPrepared(first.first)
            staleFinished.onCompletion(first.first)
            staleError.onError(first.first, 1, 0)
            assertEquals(0, failures)
            assertEquals(0, finished)
            val second = players.last()
            val secondPrepared = second.second.onPreparedListener
            second.second.invokeErrorListener(1, 0)
            assertEquals(1, failures)
            assertEquals(ShadowMediaPlayer.State.END, second.second.state)
            preview.stop()
            preview.stop()
            secondPrepared.onPrepared(second.first)
            assertEquals(ShadowMediaPlayer.State.END, second.second.state)
            assertEquals(0, finished)
        } finally {
            ShadowMediaPlayer.resetStaticState()
        }
    }
}
