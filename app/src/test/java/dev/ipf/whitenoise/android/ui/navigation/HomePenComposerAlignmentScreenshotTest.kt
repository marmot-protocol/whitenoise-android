package dev.ipf.whitenoise.android.ui.navigation

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performTouchInput
import androidx.core.graphics.Insets
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.github.takahirom.roborazzi.captureRoboImage
import dev.ipf.whitenoise.android.ui.conversation.composer.APP_DICTATION_CONTROL_TAG
import dev.ipf.whitenoise.android.ui.conversation.composer.COMPOSER_PILL_SURFACE_TAG
import dev.ipf.whitenoise.android.ui.conversation.composer.COMPOSER_RESIZE_ACCESSIBILITY_TAG
import dev.ipf.whitenoise.android.ui.conversation.composer.COMPOSER_RESIZE_GESTURE_TAG
import dev.ipf.whitenoise.android.ui.navigation.DictationNavigationFixtureValues.ACCOUNT
import dev.ipf.whitenoise.android.ui.navigation.DictationNavigationFixtureValues.GROUP
import dev.ipf.whitenoise.android.ui.navigation.DictationNavigationFixtureValues.origin
import kotlinx.coroutines.ExperimentalCoroutinesApi
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Measures the shared shell and real endpoints across retained composer heights. */
@RunWith(RobolectricTestRunner::class)
@OptIn(ExperimentalCoroutinesApi::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "en-w360dp-h780dp-mdpi")
internal class HomePenComposerAlignmentScreenshotTest : DictationNavigationRenderFixture() {
    @After
    fun releaseVoiceFixture() = releasePreviewVoiceController()

    @Test
    fun homePenAndBubbleKeepCompactBaselineAcrossComposerExpansion() {
        val fixture = render(withComposerMorph = true, configured = true)
        composeRule.runOnIdle {
            checkNotNull(fixture.toolbarState)
            fixture.bindDraftOwner = true
        }
        composeRule.runOnUiThread {
            ViewCompat.dispatchApplyWindowInsets(
                renderedView.rootView,
                WindowInsetsCompat
                    .Builder()
                    .setInsets(WindowInsetsCompat.Type.statusBars(), Insets.of(0, 24, 0, 0))
                    .setInsets(WindowInsetsCompat.Type.navigationBars(), Insets.of(0, 0, 0, 48))
                    .setVisible(WindowInsetsCompat.Type.statusBars(), true)
                    .setVisible(WindowInsetsCompat.Type.navigationBars(), true)
                    .build(),
            )
        }
        composeRule.waitForIdle()
        assertEquals("No IME changes the reference viewport", 0, observedImeBottom)
        val resting = composerBounds()
        val homeBaseline = assertHomeAlignment(fixture, "resting", resting)
        composeRule.onNodeWithTag(COMPOSER_RESIZE_GESTURE_TAG, useUnmergedTree = true).performTouchInput {
            down(center)
            moveBy(Offset(0f, -160f), delayMillis = 200)
            up()
        }
        composeRule.waitForIdle()
        val manual = composerBounds()
        assertHomeAlignment(fixture, "manual", manual, homeBaseline)
        assertTrue("The diagnostic really enlarged the composer", manual.height > resting.height + 1f)
        val expand =
            composeRule
                .onNodeWithTag(COMPOSER_RESIZE_ACCESSIBILITY_TAG, useUnmergedTree = true)
                .fetchSemanticsNode()
                .config[SemanticsActions.CustomActions]
                .single()
        composeRule.runOnIdle { assertTrue(expand.action()) }
        composeRule.waitForIdle()
        val fullscreen = composerBounds()
        assertTrue("Full-screen state must exceed the manual state", fullscreen.height > manual.height + 1f)
        assertHomeAlignment(fixture, "fullscreen", fullscreen, homeBaseline)
    }

    /** Home keeps its measured resting baseline; the conversation retains its own expanded geometry. */
    private fun assertHomeAlignment(
        fixture: Fixture,
        phase: String,
        composer: Rect,
        homeBaseline: Pair<Rect, Rect>? = null,
    ): Pair<Rect, Rect> {
        val capsuleBefore = composeRule.onNodeWithTag(APP_DICTATION_CONTROL_TAG).fetchSemanticsNode().boundsInRoot
        val retention = checkNotNull(fixture.toolbarState).composerExpansionStateRetention
        val retainedBefore = retention.preferenceFor(ACCOUNT, GROUP)
        if (phase != "resting") {
            assertTrue("Explicit expansion reaches the real draft owner", retainedBefore != null)
        }
        captureAndReport("${phase}_composer", composer)
        composeRule.runOnIdle { fixture.route = origin.copy(selectedChatId = null, selectedGroupIdHex = null) }
        composeRule.waitForIdle()
        val pen = composeRule.onNodeWithContentDescription("New Message").fetchSemanticsNode().boundsInRoot
        val capsuleAfter = composeRule.onNodeWithTag(APP_DICTATION_CONTROL_TAG).fetchSemanticsNode().boundsInRoot
        assertEquals("The pen width stays unchanged", 48f, pen.width, 1.1f)
        assertEquals("The pen height stays unchanged", 48f, pen.height, 1.1f)
        val expectedPen = homeBaseline?.first ?: composer
        val expectedCapsule = homeBaseline?.second ?: capsuleBefore
        assertEquals("The home pen keeps its compact resting upper border", expectedPen.top, pen.top, 1.1f)
        assertEquals("The home bubble keeps its compact resting top", expectedCapsule.top, capsuleAfter.top, 1.1f)
        assertEquals(
            "The home bubble keeps its compact resting bottom",
            expectedCapsule.bottom,
            capsuleAfter.bottom,
            1.1f,
        )
        captureAndReport("${phase}_home", pen)
        composeRule.runOnIdle { fixture.route = origin }
        composeRule.waitForIdle()
        assertEquals(
            "Navigation keeps the real owner's expansion preference",
            retainedBefore,
            retention.preferenceFor(ACCOUNT, GROUP),
        )
        assertEquals("Returning preserves the real composer height", composer.top, composerBounds().top, 1.1f)
        val returnedCapsule = composeRule.onNodeWithTag(APP_DICTATION_CONTROL_TAG).fetchSemanticsNode().boundsInRoot
        assertEquals("Returning preserves the conversation bubble top", capsuleBefore.top, returnedCapsule.top, 1.1f)
        assertEquals(
            "Returning preserves the conversation bubble bottom",
            capsuleBefore.bottom,
            returnedCapsule.bottom,
            1.1f,
        )
        return pen to capsuleAfter
    }

    private fun composerBounds(): Rect =
        composeRule
            .onNodeWithTag(COMPOSER_PILL_SURFACE_TAG, useUnmergedTree = true)
            .fetchSemanticsNode()
            .boundsInRoot

    /** Captures actual endpoint and capsule geometry after the same production motion settles. */
    private fun captureAndReport(
        phase: String,
        control: Rect,
    ) {
        val capsule = composeRule.onNodeWithTag(APP_DICTATION_CONTROL_TAG).fetchSemanticsNode().boundsInRoot
        println("HOME_PEN_GEOMETRY phase=$phase control=$control capsule=$capsule")
        composeRule.onRoot().captureRoboImage("src/test/snapshots/home_pen_alignment_$phase.png")
        println("HOME_PEN_GEOMETRY phase=${phase}_captured")
    }
}
