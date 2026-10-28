package dev.ipf.whitenoise.android.ui.conversation.media

import android.content.res.Configuration
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.size
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composer
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.CompositionTracer
import androidx.compose.runtime.InternalComposeTracingApi
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.unit.dp
import dev.ipf.whitenoise.android.ui.theme.WhiteNoiseTheme
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.util.concurrent.atomic.AtomicInteger

/** Production-frame measurements and live-screen icon lifetime must not add work to every dialog opening. */
@OptIn(InternalComposeTracingApi::class)
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "w360dp-h780dp-mdpi")
class MediaViewerOpeningWorkTest {
    @get:Rule
    val composeRule = createComposeRule()

    /** Restores the process-global compiler tracer for unrelated Compose tests. */
    @After
    fun clearTracer() = Composer.setTracer(null)

    /** Counts actual compiler frame entries, then checks large-text and hidden-chrome snackbar placement. */
    @Test
    fun measuringTheHeaderDoesNotRecomposeTheFrameAndSnackbarsStillClearVisibleChrome() {
        val frames = AtomicInteger()
        Composer.setTracer(
            object : CompositionTracer {
                override fun isTraceInProgress() = true

                override fun traceEventStart(
                    key: Int,
                    dirty1: Int,
                    dirty2: Int,
                    info: String,
                ) {
                    if (info.startsWith("dev.ipf.whitenoise.android.ui.conversation.media.MediaViewerFrame (")) {
                        frames.incrementAndGet()
                    }
                }

                override fun traceEventEnd() = Unit
            },
        )
        val snackbars = SnackbarHostState()
        var showSnackbar by mutableStateOf(false)
        var chromeVisible by mutableStateOf(true)
        composeRule.setContent {
            WhiteNoiseTheme(fontScale = 1.6f) {
                LaunchedEffect(showSnackbar) {
                    if (showSnackbar) snackbars.showSnackbar("Viewer notice", duration = SnackbarDuration.Indefinite)
                }
                Box(Modifier.size(360.dp, 780.dp)) {
                    MediaViewerFrame(
                        senderLabel = "Alexandria Example",
                        recordedAtLabel = "Oct 28, 2026",
                        onDismiss = {},
                        onSave = {},
                        onShare = {},
                        snackbarHostState = snackbars,
                        contentWindowInsets = WindowInsets(top = 42.dp, bottom = 28.dp),
                        chromeVisible = chromeVisible,
                    ) { Text("Media body") }
                }
            }
        }
        composeRule.runOnIdle {
            assertEquals("measuring the header must not schedule another frame composition", 1, frames.get())
            showSnackbar = true
        }
        val header = composeRule.onNodeWithTag(MEDIA_VIEWER_TOP_CHROME_TAG).fetchSemanticsNode().boundsInRoot
        val visibleNotice = composeRule.onNodeWithText("Viewer notice").fetchSemanticsNode().boundsInRoot
        assertTrue(visibleNotice.top >= header.bottom)
        composeRule.runOnIdle { chromeVisible = false }
        val hiddenNotice = composeRule.onNodeWithText("Viewer notice").fetchSemanticsNode().boundsInRoot
        assertTrue(hiddenNotice.top >= 42f)
        assertTrue(hiddenNotice.top < visibleNotice.top)
    }

    /** A closed viewer keeps its live-screen artwork, while configuration replacement starts a fresh lazy holder. */
    @Test
    fun iconsStayLazyAcrossViewerClosuresAndRefreshWithConfiguration() {
        var open by mutableStateOf(false)
        var changedConfiguration by mutableStateOf(false)
        lateinit var provider: Lazy<MediaViewerChromeIcons>
        var shown: MediaViewerChromeIcons? = null
        composeRule.setContent {
            val original = LocalConfiguration.current
            val configuration = Configuration(original).apply { if (changedConfiguration) densityDpi = 240 }
            CompositionLocalProvider(LocalConfiguration provides configuration) {
                val icons = rememberMediaViewerChromeIconProvider()
                SideEffect { provider = icons }
                if (open) {
                    val artwork = icons.value
                    SideEffect { shown = artwork }
                    Text("Open viewer")
                }
            }
        }
        composeRule.runOnIdle {
            assertFalse(provider.isInitialized())
            open = true
        }
        val first = composeRule.runOnIdle { checkNotNull(shown).also { open = false } }
        composeRule.runOnIdle { open = true }
        composeRule.runOnIdle {
            assertSame(first, shown)
            open = false
            changedConfiguration = true
        }
        composeRule.runOnIdle {
            assertFalse(provider.isInitialized())
            open = true
        }
        composeRule.runOnIdle { assertNotSame(first, shown) }
    }
}
