package dev.ipf.whitenoise.android.ui.chats

import android.view.View
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.dp
import androidx.core.graphics.Insets
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import dev.ipf.whitenoise.android.state.AppText
import dev.ipf.whitenoise.android.state.TransientNotice
import dev.ipf.whitenoise.android.ui.common.LocalTopNoticeHost
import dev.ipf.whitenoise.android.ui.common.TOP_NOTICE_REGION_TAG
import dev.ipf.whitenoise.android.ui.common.TopNoticeHost
import dev.ipf.whitenoise.android.ui.common.WhiteNoiseScaffold
import dev.ipf.whitenoise.android.ui.theme.WhiteNoiseTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The chat list's banner slot lives in [ChatListBodyFrame], inside scaffold content the scaffold has already
 * padded for a side cutout or a side navigation bar (#3312). The list column consumes those insets after its
 * padding, so in landscape the banner shares the playback strip's edges instead of coming out narrower than the
 * strip and the list on both sides.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], qualifiers = "w780dp-h360dp-mdpi")
class ChatListBodyFrameInsetTest {
    @get:Rule
    val composeRule = createComposeRule()

    private lateinit var view: View

    /** Under landscape side insets the strip is padded once and the banner lines up with it on both sides. */
    @Test
    fun theBannerLinesUpWithThePlaybackStripUnderSideInsets() {
        render()
        dispatchSideInsets()

        val root = composeRule.onRoot().getUnclippedBoundsInRoot()
        val strip = composeRule.onNodeWithTag(STRIP_TAG).getUnclippedBoundsInRoot()
        val region = composeRule.onNodeWithTag(TOP_NOTICE_REGION_TAG).assertIsDisplayed().getUnclippedBoundsInRoot()
        assertEquals(CUTOUT_PX.dp.value, strip.left.value, TOLERANCE)
        assertEquals((root.right - NAVIGATION_PX.dp).value, strip.right.value, TOLERANCE)
        assertEquals("the banner starts where the strip starts", strip.left.value, region.left.value, TOLERANCE)
        assertEquals("the banner ends where the strip ends", strip.right.value, region.right.value, TOLERANCE)
    }

    /** Applies a left display cutout and a right navigation bar, the landscape side insets. */
    private fun dispatchSideInsets() {
        composeRule.runOnUiThread {
            val insets =
                WindowInsetsCompat
                    .Builder()
                    .setInsets(WindowInsetsCompat.Type.displayCutout(), Insets.of(CUTOUT_PX, 0, 0, 0))
                    .setInsets(WindowInsetsCompat.Type.navigationBars(), Insets.of(0, 0, NAVIGATION_PX, 0))
                    .setVisible(WindowInsetsCompat.Type.navigationBars(), true)
                    .build()
            ViewCompat.dispatchApplyWindowInsets(view.rootView, insets)
        }
        composeRule.waitForIdle()
    }

    /** The list's scaffold content as ChatsScreen builds it: padded by the scaffold, then consuming the side insets. */
    private fun render() {
        val notice = TransientNotice(id = 1L, title = AppText.Plain(NOTICE_TITLE))
        val host = TopNoticeHost(SnackbarHostState(), notice = { notice })
        composeRule.setContent {
            view = LocalView.current
            CompositionLocalProvider(LocalTopNoticeHost provides host) {
                WhiteNoiseTheme {
                    WhiteNoiseScaffold(
                        topBar = { Box(Modifier.fillMaxWidth().height(BAR_HEIGHT)) },
                        topNoticeSlot = false,
                    ) { padding ->
                        Column(
                            Modifier
                                .fillMaxSize()
                                .padding(padding)
                                .consumeWindowInsets(WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal)),
                        ) {
                            ChatListBodyFrame(
                                ttsTransport = { Box(Modifier.fillMaxWidth().height(STRIP_HEIGHT).testTag(STRIP_TAG)) },
                            ) {
                                Box(Modifier.fillMaxSize())
                            }
                        }
                    }
                }
            }
        }
        composeRule.waitForIdle()
    }

    private companion object {
        const val STRIP_TAG = "chat-list-playback-strip"
        const val NOTICE_TITLE = "Invitation accepted"
        const val CUTOUT_PX = 40
        const val NAVIGATION_PX = 48
        const val TOLERANCE = 1f
        val BAR_HEIGHT = 56.dp
        val STRIP_HEIGHT = 40.dp
    }
}
