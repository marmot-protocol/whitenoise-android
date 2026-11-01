package dev.ipf.whitenoise.android.ui.navigation

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.LayoutDirection
import androidx.core.graphics.Insets
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.test.core.app.ApplicationProvider
import com.github.takahirom.roborazzi.captureRoboImage
import dev.ipf.whitenoise.android.R
import dev.ipf.whitenoise.android.core.MessageTextCopy
import dev.ipf.whitenoise.android.ui.chats.ChatsNewMessageFabContent
import dev.ipf.whitenoise.android.ui.conversation.composer.COMPOSER_PILL_SURFACE_TAG
import dev.ipf.whitenoise.android.ui.conversation.composer.ComposerBar
import dev.ipf.whitenoise.android.ui.theme.WhiteNoiseTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Compare production endpoints under Material placement, not a duplicate baseline formula. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "en-w360dp-h640dp-mdpi")
class ComposerFabBaselineTest {
    @get:Rule val rule = createComposeRule()

    @Test fun restingCentersMatch() = assertBaseline(name = "default")

    @Test fun largeTextRestingCentersMatch() = assertBaseline(name = "large", fontScale = 2f)

    @Test fun largeRtlRestingCentersMatch() = assertBaseline(name = "large_rtl", fontScale = 2f, rtl = true)

    @Test fun navigationBarRemainsClear() = assertBaseline(name = "navigation", navigationBottomPx = 48)

    @Test
    @Config(sdk = [36], qualifiers = "en-w280dp-h360dp-mdpi")
    fun shortRtlRestingCentersMatch() {
        assertBaseline(name = "short_rtl", fontScale = 2f, rtl = true, navigationBottomPx = 24)
    }

    @Test
    fun relayWarningKeepsMaterialGap() {
        assertBaseline(name = "relay_warning", missingRelays = true, navigationBottomPx = 48)
    }

    // Keep the endpoint swap and its measured assertions in one stateful fixture; splitting the
    // composition into a second owner would hide which live inset and endpoint each sample uses.
    @Suppress("LongMethod")
    private fun assertBaseline(
        name: String,
        fontScale: Float = 1f,
        rtl: Boolean = false,
        navigationBottomPx: Int = 0,
        missingRelays: Boolean = false,
    ) {
        var composer by mutableStateOf(false)
        lateinit var rootView: android.view.View
        var density = 1f
        var actualNavigationBottom = 0
        val anchor = FloatingDictationAnchor()
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val layoutDirection = if (rtl) LayoutDirection.Rtl else LayoutDirection.Ltr
        rule.setContent {
            CompositionLocalProvider(LocalLayoutDirection provides layoutDirection) {
                WhiteNoiseTheme(fontScale = fontScale) {
                    rootView = LocalView.current.rootView
                    density = LocalDensity.current.density
                    actualNavigationBottom = WindowInsets.navigationBars.getBottom(LocalDensity.current)
                    CompositionLocalProvider(LocalFloatingDictationAnchor provides anchor) {
                        Box(Modifier.fillMaxSize().testTag(VIEWPORT)) {
                            if (composer) {
                                ComposerBar(
                                    replyingTo = null,
                                    messageTextCopy = MessageTextCopy.Default,
                                    onCancelReply = {},
                                    onSend = { _, _ -> error("Layout fixture cannot send") },
                                    modifier = Modifier.align(Alignment.BottomCenter),
                                )
                            } else {
                                Scaffold(
                                    contentWindowInsets = WindowInsets.navigationBars,
                                    floatingActionButton = { ChatsNewMessageFabContent(missingRelays) {} },
                                ) { innerPadding ->
                                    Box(Modifier.fillMaxSize().padding(innerPadding))
                                }
                            }
                        }
                    }
                }
            }
        }
        rule.waitForIdle()
        rule.runOnIdle {
            val insets =
                WindowInsetsCompat
                    .Builder()
                    .setInsets(WindowInsetsCompat.Type.navigationBars(), Insets.of(0, 0, 0, navigationBottomPx))
                    .setVisible(WindowInsetsCompat.Type.navigationBars(), navigationBottomPx > 0)
                    .build()
            ViewCompat.dispatchApplyWindowInsets(rootView, insets)
        }
        rule.waitForIdle()
        rule.onRoot().captureRoboImage("src/test/snapshots/composer_fab_baseline_${name}_fab.png")
        assertEquals("The production inset source must receive the fixture", navigationBottomPx, actualNavigationBottom)
        val description = if (missingRelays) R.string.chats_check_relays else R.string.new_message
        val fab = rule.onNodeWithContentDescription(context.getString(description)).fetchSemanticsNode().boundsInWindow
        val viewport = rule.onNodeWithTag(VIEWPORT).fetchSemanticsNode().boundsInWindow
        assertTrue("Touch target must clear navigation", fab.bottom <= viewport.bottom - actualNavigationBottom + 1)
        assertTrue(fab.height >= 48 * density)
        val obstacleTop = rule.runOnIdle { requireNotNull(anchor.topInWindow) }
        assertEquals(
            "Dictation keeps the established Material obstacle",
            viewport.bottom - actualNavigationBottom - 16 * density - fab.height,
            obstacleTop,
            1.1f,
        )
        if (missingRelays) {
            assertEquals("Relay recovery keeps its Material placement", obstacleTop, fab.top, 1.1f)
            return
        }
        rule.runOnIdle { composer = true }
        rule.waitForIdle()
        rule.onRoot().captureRoboImage("src/test/snapshots/composer_fab_baseline_${name}_composer.png")
        val pillNode = rule.onNodeWithTag(COMPOSER_PILL_SURFACE_TAG, useUnmergedTree = true)
        val pill = pillNode.fetchSemanticsNode().boundsInWindow
        assertEquals("Real resting endpoints must share a vertical center", pill.center.y, fab.center.y, 1.1f)
        assertTrue("The original obstacle remains above the visible pen", obstacleTop <= fab.top)
    }

    private companion object {
        const val VIEWPORT = "composer-baseline-viewport"
    }
}
