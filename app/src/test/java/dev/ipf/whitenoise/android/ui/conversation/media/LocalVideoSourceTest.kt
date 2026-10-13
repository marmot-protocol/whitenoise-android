package dev.ipf.whitenoise.android.ui.conversation.media

import android.content.Context
import android.net.Uri
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.media3.exoplayer.ExoPlayer
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class LocalVideoSourceTest {
    @get:Rule val composeRule = createComposeRule()

    @Test
    fun onlyFileAndContentUrisCanCreateProgressiveSources() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        assertNotNull(localVideoSource(context, Uri.parse("file:///selected/movie.m3u8")))
        assertNotNull(localVideoSource(context, Uri.parse("content://fixture/selected/movie")))
        listOf("https://fixture/movie", "rtsp://fixture/movie", "data:movie", "/relative/movie").forEach {
            assertNull(localVideoSource(context, Uri.parse(it)))
        }
    }

    @Test
    fun restoredUnsupportedUriStaysPlayerlessAcrossForegroundTransitions() {
        val lifecycle = FixtureLifecycleOwner()
        var player: ExoPlayer? = null
        composeRule.setContent {
            CompositionLocalProvider(LocalLifecycleOwner provides lifecycle) {
                player = rememberDraftVideoPlayer(Uri.parse("https://fixture/restored"), active = true)
            }
        }
        composeRule.runOnIdle {
            assertNull(player)
            lifecycle.lifecycle.currentState = Lifecycle.State.CREATED
        }
        composeRule.runOnIdle { lifecycle.lifecycle.currentState = Lifecycle.State.RESUMED }
        composeRule.runOnIdle { assertNull(player) }
    }

    private class FixtureLifecycleOwner : LifecycleOwner {
        override val lifecycle = LifecycleRegistry.createUnsafe(this).apply { currentState = Lifecycle.State.RESUMED }
    }
}
