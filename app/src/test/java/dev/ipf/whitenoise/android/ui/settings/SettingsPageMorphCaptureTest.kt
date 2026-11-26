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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.dp
import dev.ipf.whitenoise.android.ui.theme.WhiteNoiseTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * A full-page host records its settled frame into a Picture only when a leaving contour transition can
 * replay it: never in the borderless light and dark themes.
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
                    val release = morph.observeCaptureInputs { scroll.value }
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

    /** A route entered without motion in AMOLED still records, because its next leave may run the contour motion. */
    @Test
    fun amoledRouteEnteredWithoutMotionStillRecords() = assertRecords(amoled = true, motion = false, expected = true)

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

    private companion object {
        const val PAGE = "page"
        const val ROWS = 30
    }
}
