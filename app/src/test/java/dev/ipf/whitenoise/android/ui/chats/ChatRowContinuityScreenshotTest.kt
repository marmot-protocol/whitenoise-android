package dev.ipf.whitenoise.android.ui.chats

import android.content.Context
import android.provider.Settings
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Surface
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import com.github.takahirom.roborazzi.captureRoboImage
import dev.ipf.whitenoise.android.R
import dev.ipf.whitenoise.android.ui.theme.WhiteNoiseTheme
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Pins the actual unread row at distinct selection frames, including narrow double-size RTL metadata. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "en-rUS-w360dp-h780dp-mdpi")
class ChatRowContinuityScreenshotTest {
    @get:Rule val rule = createComposeRule()
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private var previousScale = 1f

    /** Keep the clock-controlled captures independent of the host's reduced-motion preference. */
    @Before
    fun enableMotion() {
        previousScale = Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f)
        Settings.Global.putFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f)
    }

    /** Restore the isolated Android setting after each image. */
    @After
    fun restoreMotion() {
        Settings.Global.putFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, previousScale)
    }

    /** The first selected frame keeps the existing metadata while the new leading strip starts moving. */
    @Test
    fun firstSelectedFrame() = capture("first", elapsed = 0)

    /** The middle frame pins actual layout movement instead of only its final checked state. */
    @Test
    fun middleSelectedFrame() = capture("middle", elapsed = 80)

    /** The settled row retains unread count and timestamp beside its selected leading target. */
    @Test
    fun settledSelectedFrame() = capture("settled", elapsed = 240)

    /** Dark-mode selected metadata keeps its normal trailing palette and hierarchy. */
    @Test
    fun darkSelectedFrame() = capture("dark", elapsed = 240, dark = true)

    /** AMOLED and double-size RTL text keep the selected unread row legible in a narrow viewport. */
    @Test
    fun amoledLargeRtlSelectedFrame() = capture("amoled_large_rtl", elapsed = 240, dark = true, largeRtl = true)

    /** Changes only selection state; the same production row and unread projection survive every captured phase. */
    private fun capture(
        variant: String,
        elapsed: Long,
        dark: Boolean = false,
        largeRtl: Boolean = false,
    ) {
        val selected = mutableStateOf(false)
        var committedSelection = false
        var fullStripWidth = 0f
        val appState = ChatRowPortFixtures.state(context)
        val item = ChatRowPortFixtures.item(unread = true)
        rule.setContent {
            val density = LocalDensity.current
            val currentSelection = selected.value
            SideEffect {
                committedSelection = currentSelection
                fullStripWidth = with(density) { 32.dp.toPx() }
            }
            CompositionLocalProvider(
                LocalDensity provides Density(density.density, if (largeRtl) 2f else 1f),
                LocalLayoutDirection provides if (largeRtl) LayoutDirection.Rtl else LayoutDirection.Ltr,
            ) {
                WhiteNoiseTheme(darkTheme = dark, amoled = largeRtl) {
                    Surface(Modifier.width(if (largeRtl) 280.dp else 320.dp).testTag("continuity.row")) {
                        ChatRow(
                            item,
                            appState,
                            onClick = {},
                            onOpenProfile = {},
                            selectionMode = currentSelection,
                            selected = currentSelection,
                            modifier = Modifier.testTag("continuity.selection"),
                        )
                    }
                }
            }
        }
        val initial = titleLeadingEdge(largeRtl)
        rule.mainClock.autoAdvance = false
        rule.runOnUiThread {
            selected.value = true
            Snapshot.sendApplyNotifications()
        }
        rule.mainClock.advanceTimeByFrame()
        rule.waitForIdle()
        assertTrue("Selection must be committed before measuring its animation", committedSelection)
        rule.onNodeWithTag("continuity.selection").assertIsSelected()
        // The next animation frame establishes t=0 after the target's recomposition frame.
        rule.mainClock.advanceTimeByFrame()
        if (elapsed > 0) rule.mainClock.advanceTimeBy(elapsed)
        rule.waitForIdle()
        val leading = titleLeadingEdge(largeRtl)
        val movement = if (largeRtl) initial - leading else leading - initial
        when (elapsed) {
            0L -> assertEquals(0f, movement, 0.5f)
            80L ->
                assertTrue(
                    "Middle frame must be between the endpoints",
                    movement > 0f && movement < fullStripWidth,
                )
            else -> assertEquals(fullStripWidth, movement, 0.5f)
        }
        assertAuthoritativeUnreadBadge()
        rule.onNodeWithTag("continuity.row").captureRoboImage("src/test/snapshots/chat_row_continuity_$variant.png")
    }

    /** Selects the semantic count host rather than its duplicate decorative text child. */
    private fun assertAuthoritativeUnreadBadge() {
        val unreadDescription = context.resources.getQuantityString(R.plurals.unread_messages_count, 3, 3)
        rule
            .onNode(hasText("3") and hasContentDescription(unreadDescription), useUnmergedTree = true)
            .assertExists()
    }

    /** Reads the title's leading edge so the same phase contract applies to LTR and RTL rows. */
    private fun titleLeadingEdge(rtl: Boolean): Float {
        val bounds =
            rule
                .onNodeWithText(ChatRowPortFixtures.TITLE, useUnmergedTree = true)
                .fetchSemanticsNode()
                .boundsInRoot
        return if (rtl) bounds.right else bounds.left
    }
}
