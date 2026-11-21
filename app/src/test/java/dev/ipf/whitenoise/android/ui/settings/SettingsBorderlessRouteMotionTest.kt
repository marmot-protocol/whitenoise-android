package dev.ipf.whitenoise.android.ui.settings

import android.app.Application
import android.graphics.Bitmap
import android.os.Looper
import android.provider.Settings
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import dev.ipf.whitenoise.android.ui.theme.WhiteNoiseTheme
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Card-morph hosts in borderless themes move instead of cutting: a container from the row, a slide back. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "en-w360dp-h780dp-mdpi")
class SettingsBorderlessRouteMotionTest {
    @get:Rule val rule = createComposeRule()
    private val app: Application = ApplicationProvider.getApplicationContext()
    private var originalAnimatorScale = 1f

    @Before
    fun enableMotion() {
        originalAnimatorScale =
            Settings.Global.getFloat(app.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f)
        setAnimatorScale(1f)
    }

    @After
    fun restoreMotion() = setAnimatorScale(originalAnimatorScale)

    @Test fun lightRowOpensAContainerAndBackSlides() {
        val depth = mutableStateOf(0)
        rule.setContent {
            WhiteNoiseTheme(darkTheme = false) {
                SettingsRouteMotion(
                    target = depth.value,
                    depth = depth.value,
                    ownerKey = "host",
                    cardMorph = true,
                ) { route ->
                    if (route == 0) {
                        val (origin, click) = rememberSettingsRouteClick({ depth.value = 1 })
                        Box(Modifier.fillMaxSize().background(Color.Blue)) {
                            Box(
                                Modifier
                                    .offset(y = 200.dp)
                                    .fillMaxWidth()
                                    .height(56.dp)
                                    .then(origin)
                                    .testTag(ROW)
                                    .clickable { click() }
                                    .background(Color.Green),
                            )
                        }
                    } else {
                        Box(Modifier.fillMaxSize().background(Color.Red))
                    }
                }
            }
        }
        rule.mainClock.advanceTimeBy(SETTLE_MILLIS)
        rule.mainClock.autoAdvance = false
        rule.onNodeWithTag(ROW).performClick()
        repeat(3) { step() }
        val opening = frame()
        // The far corner is outside the container growing from the row; the row's centre is inside it.
        assertNotEquals(RED, opening.getPixel(350, 770))
        assertNotEquals(BLUE, opening.getPixel(350, 770))
        assertNotEquals(GREEN, opening.getPixel(180, 228))
        rule.mainClock.advanceTimeBy(SETTLE_MILLIS)
        step()
        assertEquals(RED, frame().getPixel(350, 770))

        rule.runOnUiThread {
            depth.value = 0
            Snapshot.sendApplyNotifications()
        }
        repeat(3) { step() }
        val closing = frame()
        // Back slides the live parent in from the start edge, exposing the backplate at the end edge.
        assertTrue(closing.getPixel(2, 400) == BLUE && closing.getPixel(358, 400) != BLUE)
        rule.mainClock.advanceTimeBy(SETTLE_MILLIS)
        step()
        assertEquals(BLUE, frame().getPixel(2, 400))
    }

    private fun step() {
        rule.mainClock.advanceTimeByFrame()
        shadowOf(Looper.getMainLooper()).idle()
    }

    private fun frame(): Bitmap = rule.onRoot().captureToImage().asAndroidBitmap()

    private fun setAnimatorScale(scale: Float) {
        Settings.Global.putFloat(app.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, scale)
        app.contentResolver.notifyChange(Settings.Global.getUriFor(Settings.Global.ANIMATOR_DURATION_SCALE), null)
    }

    private companion object {
        const val ROW = "borderless.row"
        const val SETTLE_MILLIS = 1_000L
        val RED = Color.Red.toArgb()
        val BLUE = Color.Blue.toArgb()
        val GREEN = Color.Green.toArgb()
    }
}
