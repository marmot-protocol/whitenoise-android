package dev.ipf.whitenoise.android.ui.navigation

import android.content.Context
import android.os.Looper
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import dev.ipf.whitenoise.android.ui.settings.DIAGNOSTICS_CONTENT_TAG
import dev.ipf.whitenoise.android.ui.settings.SETTINGS_BUBBLE_OPEN_MILLIS
import dev.ipf.whitenoise.android.ui.settings.SETTINGS_HOME_CONTENT_TAG
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The production shell's list-pane motion, through the mounted shell rather than its parts: opening Settings from the
 * chat list plays master's top-level bubble, and opening Diagnostics slides it in. Mid-motion the screen differs from
 * where it settles. Without the motion the new route is drawn settled from its first frame.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], qualifiers = "en-w1280dp-h800dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ShellSectionMotionContractTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    /** Asserts every route audit this test drove found a well-formed stack and a matching saved section. */
    @get:Rule
    val routeAudit = ShellRouteAuditRule()

    private val shell = MountedContractShell(composeRule)
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private var originalAnimatorScale = 1f

    /** Pins the animator scale, as the bubble screenshots do, because an earlier suite can leave another value. */
    @Before
    fun enableTestMotion() {
        originalAnimatorScale =
            Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f)
        setAnimatorScale(1f)
    }

    /** Restores the animator scale this class found. */
    @After
    fun restoreMotionPolicy() {
        setAnimatorScale(originalAnimatorScale)
    }

    /** Opening Settings from the chat list is mid-bubble a third of the way in, then settles on Settings home. */
    @Test
    fun openingSettingsFromTheChatListPlaysTheBubble() {
        shell.withMountedShell(mutableStateOf(COMPACT_WIDTH)) { _, holder ->
            val (moving, settled) = midAndSettled { holder.dispatchShellNav(ShellNavEvent.OpenSettings(null)) }

            assertTrue("Settings home is shown once settled", composed(SETTINGS_HOME_CONTENT_TAG))
            val changed = changedShare(moving, settled)
            assertTrue("mid-bubble frame differs from the settled one by only $changed", changed > MIN_CHANGED_SHARE)
        }
    }

    /** Opening Diagnostics from Developer is mid-slide a third of the way in, then settles on Diagnostics. */
    @Test
    fun openingDiagnosticsFromDeveloperSlidesItIn() {
        shell.withMountedShell(mutableStateOf(COMPACT_WIDTH)) { _, holder ->
            composeRule.runOnIdle { holder.restoreSection(MainSection.Settings, SettingsDetail.Developer) }
            shell.settle()

            val (moving, settled) = midAndSettled { holder.dispatchShellNav(ShellNavEvent.OpenDiagnostics) }

            assertTrue("Diagnostics is shown once settled", composed(DIAGNOSTICS_CONTENT_TAG))
            val changed = changedShare(moving, settled)
            assertTrue("mid-slide frame differs from the settled one by only $changed", changed > MIN_CHANGED_SHARE)
        }
    }

    /**
     * Sends [navigate] with the clock held, captures the screen a third of the way into the open motion, then lets
     * everything settle and captures it again.
     */
    private fun midAndSettled(navigate: () -> Unit): Pair<ImageBitmap, ImageBitmap> {
        composeRule.mainClock.autoAdvance = false
        val moving =
            try {
                composeRule.runOnUiThread {
                    navigate()
                    Snapshot.sendApplyNotifications()
                }
                repeat((SETTINGS_BUBBLE_OPEN_MILLIS / 3L / FRAME_MILLIS).toInt().coerceAtLeast(2)) {
                    composeRule.mainClock.advanceTimeByFrame()
                    shadowOf(Looper.getMainLooper()).idle()
                }
                composeRule.onRoot().captureToImage()
            } finally {
                composeRule.mainClock.autoAdvance = true
            }
        shell.settle()
        return moving to composeRule.onRoot().captureToImage()
    }

    /** The share of pixels that differ between [first] and [second], which have the same size. */
    private fun changedShare(
        first: ImageBitmap,
        second: ImageBitmap,
    ): Float {
        val a = first.toPixelMap()
        val b = second.toPixelMap()
        var changed = 0
        for (x in 0 until a.width) {
            for (y in 0 until a.height) if (a[x, y] != b[x, y]) changed += 1
        }
        val share = changed.toFloat() / (a.width * a.height)
        println("shell-section-motion changed share=$share")
        return share
    }

    /** True while a node carrying [tag] is composed. */
    private fun composed(tag: String): Boolean {
        val nodes = composeRule.onAllNodes(hasTestTag(tag), useUnmergedTree = true)
        return nodes.fetchSemanticsNodes().isNotEmpty()
    }

    /** Writes the animator scale the shell's motion reads and tells its observers. */
    private fun setAnimatorScale(scale: Float) {
        Settings.Global.putFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, scale)
        context.contentResolver.notifyChange(Settings.Global.getUriFor(Settings.Global.ANIMATOR_DURATION_SCALE), null)
    }

    private companion object {
        val COMPACT_WIDTH = 839.dp
        const val FRAME_MILLIS = 16L

        /**
         * Measured: without the section motion the two captures differ by at most 0.2% of pixels (a caret or a late
         * draw), with it by about 5% mid-slide and 23% mid-bubble. Two percent sits clear of both.
         */
        const val MIN_CHANGED_SHARE = 0.02f
    }
}
