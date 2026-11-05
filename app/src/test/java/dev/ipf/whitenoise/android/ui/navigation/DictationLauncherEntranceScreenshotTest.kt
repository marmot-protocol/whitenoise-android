package dev.ipf.whitenoise.android.ui.navigation

import android.content.Context
import android.provider.Settings
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.test.core.app.ApplicationProvider
import com.github.takahirom.roborazzi.captureRoboImage
import dev.ipf.whitenoise.android.ui.conversation.composer.APP_DICTATION_CONTROL_TAG
import dev.ipf.whitenoise.android.ui.conversation.composer.COMPOSER_DICTATION_COMPACT_ACTIONS_TAG
import dev.ipf.whitenoise.android.ui.navigation.DictationNavigationFixtureValues.ACCOUNT
import dev.ipf.whitenoise.android.ui.navigation.DictationNavigationFixtureValues.GROUP
import dev.ipf.whitenoise.android.ui.navigation.DictationNavigationFixtureValues.origin
import kotlinx.coroutines.ExperimentalCoroutinesApi
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Records actual idle-to-start frames, rather than mounting an already running session. */
@RunWith(RobolectricTestRunner::class)
@OptIn(ExperimentalCoroutinesApi::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "w360dp-h780dp-mdpi")
internal class DictationLauncherEntranceScreenshotTest : DictationNavigationScenarioFixture() {
    protected override fun captureGolden(path: String) {
        composeRule.onRoot().captureRoboImage(path)
    }

    @After
    fun releaseMotionVoiceFixture() = releasePreviewVoiceController()

    /** The normal origin emerges from its measured mic without replacing that toolbar target. */
    @Test
    fun sameChatLauncherLight() = captureEntry(largeRtl = false)

    /** Physical-side and measured-origin behavior survive doubled fonts and RTL. */
    @Test
    fun sameChatLauncherLargeRtlDark() = captureEntry(largeRtl = true)

    /** Navigation in the opening phase continues from current pixels and the same entrance opacity. */
    @Test
    fun entryContinuesAcrossRapidChatListNavigation() {
        val fixture = startEntry()
        composeRule.mainClock.advanceTimeBy(64)
        val motion = requireNotNull(fixture.dictationMotion)
        val beforeAlpha = motion.renderedDecorationAlpha
        assertTrue("Rapid navigation begins at a real intermediate opacity", beforeAlpha > 0f && beforeAlpha < 1f)
        captureGolden("src/test/snapshots/dictation_launcher_rapid_before_departure.png")
        firstNavigationFrame { fixture.route = origin.copy(selectedChatId = null, selectedGroupIdHex = null) }
        val continuedMotion = requireNotNull(fixture.dictationMotion)
        val afterAlpha = continuedMotion.renderedDecorationAlpha
        assertSame("Navigation keeps the session's presentation clock", motion, continuedMotion)
        assertTrue(continuedMotion.entranceActive)
        // The helper advances a frame; exact prepare-instant equality is covered by the clock unit test.
        assertTrue("The next frame keeps a real intermediate opacity", afterAlpha > 0f && afterAlpha < 1f)
        assertTrue("The next frame progresses rather than restarting opacity", afterAlpha >= beforeAlpha)
        assertTrue(
            "Navigation does not snap the opening capsule to its final slot",
            requireNotNull(continuedMotion.renderedBounds) != requireNotNull(continuedMotion.targetBounds),
        )
        captureGolden("src/test/snapshots/dictation_launcher_rapid_first_departure.png")
        composeRule.mainClock.advanceTimeBy(256)
        assertSingleActionSet()
        assertEquals(0, fixture.platform.cancels)
        assertTrue(fixture.writes.isEmpty())
        captureGolden("src/test/snapshots/dictation_launcher_rapid_settled_chats.png")
    }

    /** Lock recreates the motion object, but unlocking the same session must not replay entry. */
    @Test
    fun lockUnlockDoesNotReplayTheLauncherEntrance() {
        val fixture = startEntry()
        composeRule.mainClock.advanceTimeBy(64)
        firstNavigationFrame { fixture.locked = true }
        composeRule.onAllNodes(hasTestTag(APP_DICTATION_CONTROL_TAG)).assertCountEquals(0)
        firstNavigationFrame { fixture.locked = false }
        assertPaintedComposerGap()
        assertTrue("Unlocking a current session does not restart entry", !requireNotNull(fixture.dictationMotion).entranceActive)
        assertSingleActionSet()
        assertEquals(0, fixture.platform.cancels)
        assertTrue(fixture.writes.isEmpty())
        captureGolden("src/test/snapshots/dictation_launcher_unlock_settled.png")
    }

    /** System-disabled motion uses authoritative final placement on the first committed frame. */
    @Test
    fun reducedMotionStartUsesFinalCapsulePlacement() {
        val resolver = ApplicationProvider.getApplicationContext<Context>().contentResolver
        val old = Settings.Global.getFloat(resolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f)
        try {
            Settings.Global.putFloat(resolver, Settings.Global.ANIMATOR_DURATION_SCALE, 0f)
            startEntry()
            assertPaintedComposerGap()
            assertSingleActionSet()
            captureGolden("src/test/snapshots/dictation_launcher_reduced_motion_settled.png")
        } finally {
            Settings.Global.putFloat(resolver, Settings.Global.ANIMATOR_DURATION_SCALE, old)
        }
    }

    private fun captureEntry(largeRtl: Boolean) {
        val fixture = startEntry(largeRtl)
        val variant = if (largeRtl) "large_rtl_dark" else "light"
        val first = capsuleBounds()
        assertTrue(requireNotNull(fixture.dictationMotion).entranceActive)
        assertEquals(0f, requireNotNull(fixture.dictationMotion).renderedDecorationAlpha, 0f)
        captureGolden("src/test/snapshots/dictation_launcher_first_$variant.png")
        composeRule.mainClock.advanceTimeBy(96)
        val middle = capsuleBounds()
        val middleAlpha = requireNotNull(fixture.dictationMotion).renderedDecorationAlpha
        assertTrue("Capture uses a real middle phase", middleAlpha > 0f && middleAlpha < 1f)
        assertTrue("Midpoint rises from the launcher", middle.top < first.top)
        captureGolden("src/test/snapshots/dictation_launcher_midpoint_$variant.png")
        composeRule.mainClock.advanceTimeBy(192)
        val last = capsuleBounds()
        assertTrue(!requireNotNull(fixture.dictationMotion).active)
        assertTrue("The measured trajectory reaches its floating slot", last.top < middle.top)
        assertPaintedComposerGap()
        assertSingleActionSet()
        assertEquals(0, fixture.platform.cancels)
        assertTrue(fixture.writes.isEmpty())
        captureGolden("src/test/snapshots/dictation_launcher_settled_$variant.png")
    }

    private fun startEntry(largeRtl: Boolean = false): Fixture {
        val fixture = render(dark = largeRtl, largeRtl = largeRtl, configured = true, startImmediately = false)
        firstNavigationFrame {
            fixture.controller.requestStart(ACCOUNT, GROUP, fixture.draft)
            fixture.platform.listener.onReady()
        }
        composeRule.onNodeWithContentDescription("Dictate text").assertIsNotEnabled()
        composeRule.onAllNodes(hasTestTag(COMPOSER_DICTATION_COMPACT_ACTIONS_TAG)).assertCountEquals(1)
        return fixture
    }

    private fun capsuleBounds() = composeRule.onNodeWithTag(APP_DICTATION_CONTROL_TAG).fetchSemanticsNode().boundsInRoot
}
