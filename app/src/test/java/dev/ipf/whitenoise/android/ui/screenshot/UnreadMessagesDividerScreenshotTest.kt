package dev.ipf.whitenoise.android.ui.screenshot

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import com.github.takahirom.roborazzi.captureRoboImage
import dev.ipf.whitenoise.android.ui.conversation.UNREAD_MESSAGES_DIVIDER_CONTENT_TAG
import dev.ipf.whitenoise.android.ui.conversation.UnreadMessagesDivider
import dev.ipf.whitenoise.android.ui.theme.WhiteNoiseTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "w360dp-h780dp-mdpi")
class UnreadMessagesDividerScreenshotTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun unreadDividerHasBalancedCompactGapsInLightLtr() {
        render(darkTheme = false, rtl = false, fontScale = 1f)
        assertBalancedGaps()
        composeRule.onNodeWithTag(ROOT_TAG).captureRoboImage("src/test/snapshots/unread_divider_light_ltr.png")
    }

    @Test
    fun unreadDividerHasBalancedCompactGapsInDarkLargeRtl() {
        render(darkTheme = true, rtl = true, fontScale = 1.6f)
        assertBalancedGaps()
        composeRule.onNodeWithTag(ROOT_TAG).captureRoboImage("src/test/snapshots/unread_divider_dark_large_rtl.png")
    }

    private fun assertBalancedGaps() {
        val older = composeRule.onNodeWithTag(OLDER_TAG).fetchSemanticsNode().boundsInRoot
        val divider = composeRule.onNodeWithTag(UNREAD_MESSAGES_DIVIDER_CONTENT_TAG).fetchSemanticsNode().boundsInRoot
        val unread = composeRule.onNodeWithTag(UNREAD_TAG).fetchSemanticsNode().boundsInRoot
        assertEquals(divider.top - older.bottom, unread.top - divider.bottom, 1f)
    }

    private fun render(
        darkTheme: Boolean,
        rtl: Boolean,
        fontScale: Float,
    ) {
        composeRule.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(
                LocalDensity provides Density(density.density, fontScale),
                LocalLayoutDirection provides if (rtl) LayoutDirection.Rtl else LayoutDirection.Ltr,
            ) {
                WhiteNoiseTheme(darkTheme = darkTheme) {
                    Surface(modifier = Modifier.width(360.dp).height(180.dp).testTag(ROOT_TAG)) {
                        LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            item(key = OLDER_TAG) {
                                Box(
                                    Modifier
                                        .fillMaxWidth()
                                        .height(36.dp)
                                        .background(MaterialTheme.colorScheme.surfaceContainer)
                                        .testTag(OLDER_TAG),
                                )
                            }
                            item(key = UNREAD_TAG) {
                                Column {
                                    UnreadMessagesDivider(count = 5)
                                    Box(
                                        Modifier
                                            .fillMaxWidth()
                                            .height(48.dp)
                                            .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                                            .testTag(UNREAD_TAG),
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
        composeRule.waitForIdle()
    }

    private companion object {
        const val ROOT_TAG = "unread-divider-root"
        const val OLDER_TAG = "unread-divider-older-message"
        const val UNREAD_TAG = "unread-divider-first-unread-message"
    }
}
