package dev.ipf.whitenoise.android.ui.settings

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.dp
import dev.ipf.whitenoise.android.ui.theme.WhiteNoiseTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * A full-page host records its settled frame into a Picture only when a leaving contour transition can
 * replay it, and a details page records once a scroll stops instead of on every scroll frame.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], qualifiers = "en-w400dp-h800dp-mdpi")
class SettingsPageMorphCaptureTest {
    @get:Rule val rule = createComposeRule()

    private lateinit var scroll: ScrollState

    /** Mounts a scrolling page in a full-screen morph host that observes its scroll like Group details. */
    private fun mount(
        morph: SettingsCardMorph,
        amoled: Boolean,
        motion: Boolean,
    ) {
        rule.setContent {
            WhiteNoiseTheme(darkTheme = true, amoled = amoled) {
                scroll = rememberScrollState()
                DisposableEffect(morph, scroll) {
                    val release = morph.observeCaptureInputs(settled = { !scroll.isScrollInProgress }) { scroll.value }
                    onDispose { release() }
                }
                val (_, host) = rememberSettingsCardMorphHost(morph, "details", Unit, motion = motion)
                Box(Modifier.fillMaxSize().then(host)) {
                    Column(Modifier.fillMaxSize().verticalScroll(scroll).testTag(PAGE)) {
                        repeat(ROWS) { row ->
                            val color = if (row % 2 == 0) Color.Red else Color.Blue
                            Box(Modifier.fillMaxWidth().height(80.dp).background(color))
                        }
                    }
                }
            }
        }
    }

    /** Light and dark themes have no contour transition, so they draw live and never record. */
    @Test
    fun borderlessThemeNeverRecordsTheSettledPage() = assertRecords(amoled = false, motion = true, expected = false)

    /** With motion off no transition replays the page, so it is never recorded. */
    @Test
    fun reducedMotionNeverRecordsTheSettledPage() = assertRecords(amoled = true, motion = false, expected = false)

    /** The AMOLED contour transition replays the settled page, so it is recorded. */
    @Test
    fun amoledContourMotionRecordsTheSettledPage() = assertRecords(amoled = true, motion = true, expected = true)

    /** Checks whether a settled page draw records a Picture for the given theme and motion setting. */
    private fun assertRecords(
        amoled: Boolean,
        motion: Boolean,
        expected: Boolean,
    ) {
        val morph = SettingsCardMorph(fullScreen = true)
        mount(morph, amoled, motion)
        rule.onRoot().captureToImage()
        rule.runOnIdle { assertEquals(expected, requireNotNull(morph.page).hasSettledPicture) }
    }

    /** Frames drawn while a drag scrolls the page record nothing; the page is recorded once it stops. */
    @Test
    fun amoledScrollRecordsOnlyAfterTheScrollStops() {
        val morph = SettingsCardMorph(fullScreen = true)
        val page = requireNotNull(morph.page)
        mount(morph, amoled = true, motion = true)
        rule.onRoot().captureToImage()
        rule.runOnIdle { assertTrue("The resting page is recorded", page.hasSettledPicture) }

        rule.onNodeWithTag(PAGE).performTouchInput { down(center) }
        repeat(DRAG_STEPS) { step ->
            rule.onNodeWithTag(PAGE).performTouchInput { moveBy(Offset(0f, -40f)) }
            rule.onRoot().captureToImage()
            rule.runOnIdle {
                assertTrue("The drag scrolls the page", scroll.isScrollInProgress && scroll.value > 0)
                assertFalse("Scroll frame $step must not record the page", page.hasSettledPicture)
            }
        }
        rule.onNodeWithTag(PAGE).performTouchInput { up() }
        rule.waitForIdle()
        rule.onRoot().captureToImage()
        rule.runOnIdle {
            assertFalse(scroll.isScrollInProgress)
            assertTrue("The stopped page is recorded once more", page.hasSettledPicture)
        }
    }

    private companion object {
        const val PAGE = "page"
        const val ROWS = 30
        const val DRAG_STEPS = 4
    }
}
