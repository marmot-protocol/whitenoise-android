package dev.ipf.whitenoise.android.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onRoot
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

    /** How often the host read the page's viewport inputs while drawing. */
    private var inputReads = 0

    /** Mounts a page in a full-screen morph host that observes a viewport input the way Group details does. */
    private fun mount(
        morph: SettingsCardMorph,
        dark: Boolean,
        amoled: Boolean,
        motion: Boolean,
    ) {
        rule.setContent {
            WhiteNoiseTheme(darkTheme = dark, amoled = amoled) {
                DisposableEffect(morph) {
                    val release = morph.observeCaptureInputs { inputReads += 1 }
                    onDispose { release() }
                }
                val (_, host) = rememberSettingsCardMorphHost(morph, "details", Unit, motion = motion)
                Box(Modifier.fillMaxSize().then(host).background(Color.Red))
            }
        }
    }

    /** The light theme has no contour transition: it neither records the page nor reads its viewport. */
    @Test
    fun lightThemeNeverRecordsOrReadsTheViewport() = assertRecords(dark = false, amoled = false, expected = false)

    /** The dark theme has no contour transition: it neither records the page nor reads its viewport. */
    @Test
    fun darkThemeNeverRecordsOrReadsTheViewport() = assertRecords(dark = true, amoled = false, expected = false)

    /** The AMOLED contour transition replays the settled page, so it reads the viewport and records the page. */
    @Test
    fun amoledContourMotionRecordsTheSettledPage() = assertRecords(dark = true, amoled = true, expected = true)

    /** A route entered without motion in AMOLED still records, because its next leave may run the contour motion. */
    @Test
    fun amoledRouteEnteredWithoutMotionStillRecords() {
        assertRecords(dark = true, amoled = true, motion = false, expected = true)
    }

    /** Checks whether a settled page draw reads the viewport and records a Picture for the given theme. */
    private fun assertRecords(
        dark: Boolean,
        amoled: Boolean,
        motion: Boolean = true,
        expected: Boolean,
    ) {
        val morph = SettingsCardMorph(fullScreen = true)
        mount(morph, dark, amoled, motion)
        rule.onRoot().captureToImage()
        rule.runOnIdle {
            assertEquals("recorded", expected, requireNotNull(morph.page).hasSettledPicture)
            assertEquals("viewport read while drawing", expected, inputReads > 0)
        }
    }
}
