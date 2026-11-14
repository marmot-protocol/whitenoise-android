package dev.ipf.whitenoise.android.ui.navigation

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.dp
import androidx.core.graphics.Insets
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.test.core.app.ApplicationProvider
import dev.ipf.whitenoise.android.R
import dev.ipf.whitenoise.android.ui.chats.ChatsNewMessageFabContent
import dev.ipf.whitenoise.android.ui.common.WhiteNoiseScaffold
import dev.ipf.whitenoise.android.ui.theme.WhiteNoiseTheme
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "en-w360dp-h640dp-mdpi")
internal class ComposerFabTallSourceTest : DictationNavigationRenderFixture() {
    /** A synthetic 160dp source measured through the real endpoint cannot raise the home pen. */
    @Test
    fun tallComposerReturnsToCompactBaseline() = assertTallComposerReturn(48)

    @Test
    @Config(sdk = [36], qualifiers = "en-w640dp-h360dp-mdpi")
    fun tallComposerReturnsInsideShortViewport() = assertTallComposerReturn(24)

    // One tall-source scenario keeps native measurements and return-clock assertions sequential.
    @Suppress("LongMethod")
    private fun assertTallComposerReturn(navigationBottomPx: Int) {
        val fixture = Fixture()
        var composer by mutableStateOf(true)
        lateinit var rootView: android.view.View
        composeRule.setContent {
            WhiteNoiseTheme {
                MainShellNoticeLayout(
                    notice = null,
                    dictationController = fixture.controller,
                    dictationComposerRoute = fixture.route,
                    appLockScreenVisible = false,
                ) {
                    rootView = LocalView.current.rootView
                    Box(Modifier.fillMaxSize().testTag("tall-source-viewport")) {
                        if (composer) {
                            Box(
                                Modifier
                                    .fillMaxWidth()
                                    .height(160.dp)
                                    .align(Alignment.BottomCenter)
                                    .composerFabSurface(
                                        ComposerFabEndpoint.Composer,
                                        MaterialTheme.colorScheme.surfaceContainerHigh,
                                        MaterialTheme.colorScheme.outlineVariant,
                                        radiusDp = 24f,
                                        morphing = false,
                                    ),
                            )
                        } else {
                            WhiteNoiseScaffold(
                                floatingActionButton = { ChatsNewMessageFabContent(false) {} },
                            ) { padding -> Box(Modifier.fillMaxSize().padding(padding)) }
                        }
                    }
                }
            }
        }
        composeRule.runOnUiThread {
            ViewCompat.dispatchApplyWindowInsets(
                rootView,
                WindowInsetsCompat
                    .Builder()
                    .setInsets(WindowInsetsCompat.Type.navigationBars(), Insets.of(0, 0, 0, navigationBottomPx))
                    .setVisible(WindowInsetsCompat.Type.navigationBars(), true)
                    .build(),
            )
        }
        composeRule.waitForIdle()
        composeRule.runOnIdle { composer = false }
        composeRule.waitForIdle()
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val pen =
            composeRule
                .onNodeWithContentDescription(context.getString(R.string.new_message))
                .fetchSemanticsNode()
                .boundsInWindow
        val viewport = composeRule.onNodeWithTag("tall-source-viewport").fetchSemanticsNode().boundsInWindow
        assertEquals(
            "The tall source cannot raise the settled pen",
            6f,
            viewport.bottom - navigationBottomPx - pen.bottom,
            1.1f,
        )
        assertEquals("The destination remains compact", 48f, pen.height, 1.1f)
    }
}
