package dev.ipf.whitenoise.android.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onRoot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Draw the real morph host before testing its reverse-frame retention and protection. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "en-w360dp-h780dp-mdpi")
class SettingsPageMorphHostTest {
    @Test fun nestedPageRedrawsReachTheSettledAncestor() {
        val parent = SettingsCardMorph(fullScreen = true)
        val child = SettingsCardMorph(fullScreen = true, parent = parent)
        val color = mutableStateOf(Color.Red)
        val colors = SettingsMorphColors(Color.Black, Color.Black, Color.Unspecified)
        rule.setContent {
            Box(Modifier.fillMaxSize().settingsCardMorphHost(parent, colors)) {
                Box(Modifier.fillMaxSize().settingsCardMorphHost(child, colors)) {
                    Box(Modifier.fillMaxSize().drawWithContent { drawRect(color.value) })
                }
            }
        }
        val source = rule.onRoot().captureToImage().toPixelMap()
        assertTrue(source[8, 80].red > 0.95f)
        // The ancestor's route does not change when the descendant publishes new content.
        rule.runOnIdle { color.value = Color.Blue }
        val updated = rule.onRoot().captureToImage().toPixelMap()
        assertTrue(updated[8, 80].blue > 0.95f)
        rule.runOnIdle { color.value = Color.Green }
        val prepared = rule.onRoot().captureToImage().toPixelMap()
        assertTrue(prepared[8, 80].green > 0.95f)
    }

    @Test fun descendantViewportChangesRefreshTheAncestorExitPicture() {
        val parent = SettingsCardMorph(fullScreen = true)
        val child = SettingsCardMorph(fullScreen = true, parent = parent)
        val viewport = mutableStateOf(0)
        val release = child.observeCaptureInputs { viewport.value }
        rule.setContent {
            Box(
                Modifier.fillMaxSize().settingsCardMorphHost(
                    parent,
                    SettingsMorphColors(Color.Black, Color.Black, Color.Unspecified),
                ),
            ) {
                Box(
                    Modifier.fillMaxSize().graphicsLayer().drawWithContent {
                        drawRect(if (viewport.value == 0) Color.Red else Color.Blue)
                    },
                )
            }
        }
        rule.onRoot().captureToImage()
        rule.runOnIdle {
            parent.page!!.pendingOrigin = Rect(16f, 340f, 344f, 392f)
            parent.beginPage("documents", true, true)
            parent.finish()
            viewport.value = 1
        }
        val source = rule.onRoot().captureToImage().toPixelMap()
        assertTrue(source[8, 80].blue > 0.95f)
        rule.runOnIdle {
            assertTrue(parent.beginPage("details", false, true))
            parent.time = { 0f }
        }
        val outgoing = rule.onRoot().captureToImage().toPixelMap()
        assertEquals(source[8, 80], outgoing[8, 80])
        rule.runOnIdle { release() }
    }

    @Test fun viewportChangedBeforeItsDrawCannotReplayAnOlderExitPicture() {
        val morph = SettingsCardMorph(fullScreen = true)
        val viewport = mutableStateOf(0)
        val release = morph.observeCaptureInputs { viewport.value }
        rule.setContent {
            Box(
                Modifier
                    .fillMaxSize()
                    .settingsCardMorphHost(
                        morph,
                        SettingsMorphColors(Color.Black, Color.Black, Color.Unspecified),
                    ).background(Color.Red),
            )
        }
        rule.onRoot().captureToImage()
        rule.runOnIdle {
            morph.page!!.pendingOrigin = Rect(16f, 340f, 344f, 392f)
            morph.beginPage("documents", true, true)
            morph.finish()
            viewport.value = 1
        }
        rule.onRoot().captureToImage()
        rule.runOnIdle {
            assertTrue(morph.page!!.hasSettledPicture)
            // No intervening draw: a stale retained image must not cover the restored parent.
            viewport.value = 2
            assertFalse(morph.beginPage("details", false, true))
            assertEquals(0, morph.keptPictures)
            release()
        }
    }

    @Test fun layeredSourcePixelsRemainOutsideTheGrowingDestination() {
        val morph = SettingsCardMorph(fullScreen = true)
        val color = mutableStateOf(Color.Red)
        rule.setContent {
            Box(
                Modifier.fillMaxSize().settingsCardMorphHost(
                    morph,
                    SettingsMorphColors(Color.Black, Color.Black, Color.Unspecified),
                ),
            ) {
                Box(Modifier.fillMaxSize().graphicsLayer { alpha = 0.99f }.background(color.value))
            }
        }
        val before = rule.onRoot().captureToImage().toPixelMap()
        rule.runOnIdle {
            morph.page!!.pendingOrigin = Rect(16f, 340f, 344f, 392f)
            assertTrue(morph.beginPage("documents", true, true))
            morph.time = { 0.1f }
            color.value = Color.Blue
        }
        val after = rule.onRoot().captureToImage().toPixelMap()
        // The source is beneath the destination, not an invented solid backplate.
        assertEquals(before[8, 80].red, after[8, 80].red, 0.02f)
        assertTrue(after[8, 80].red > 0.95f)
        assertTrue(after[8, 80].blue < 0.05f)
    }

    @Test fun unmeasuredReentryCannotReuseAnEarlierLaunchRectangle() {
        val morph = SettingsCardMorph(fullScreen = true)
        val color = mutableStateOf(Color.Red)
        rule.setContent {
            Box(
                Modifier.fillMaxSize().settingsCardMorphHost(
                    morph,
                    SettingsMorphColors(Color.Black, Color.Black, Color.Unspecified),
                ),
            ) {
                Box(Modifier.fillMaxSize().background(color.value))
            }
        }
        rule.onRoot().captureToImage()
        rule.runOnIdle {
            morph.page!!.pendingOrigin = Rect(16f, 340f, 344f, 392f)
            assertTrue(morph.beginPage("documents", true, true))
            morph.finish()
            color.value = Color.Blue
        }
        rule.onRoot().captureToImage()
        rule.runOnIdle {
            // A different route/programmatic entry replaces this presentation without a measured tap.
            assertFalse(morph.beginPage("documents", true, true))
            color.value = Color.Green
        }
        rule.onRoot().captureToImage()
        rule.runOnIdle {
            assertTrue(morph.page!!.hasSettledPicture)
            assertFalse(morph.beginPage("details", false, true))
        }
    }

    @get:Rule val rule = createComposeRule()

    @Test fun settledPageReturnsToTheExactClickedRowWithoutKeepingItsControls() {
        val morph = SettingsCardMorph(fullScreen = true)
        val color = mutableStateOf(Color.Red)
        val host = Rect(0f, 0f, 360f, 780f)
        val row = Rect(16f, 340f, 344f, 392f)
        rule.setContent {
            Box(
                Modifier.fillMaxSize().settingsCardMorphHost(
                    morph,
                    SettingsMorphColors(Color.Black, Color.Black, Color.Unspecified),
                ),
            ) {
                Box(Modifier.fillMaxSize().background(color.value))
            }
        }
        rule.onRoot().captureToImage()
        rule.runOnIdle {
            morph.page!!.pendingOrigin = row
            assertTrue(morph.beginPage("documents", true, true))
            color.value = Color.Blue
        }
        rule.onRoot().captureToImage()
        rule.runOnIdle {
            assertTrue(morph.beginPage("details", false, true))
            assertEquals(1, morph.keptPictures)
            assertEquals(row, morph.page!!.bounds(host, 1f))
        }
    }

    @Test fun secureHoldClearsBothSettledAndAlreadyKeptPagePictures() {
        val morph = SettingsCardMorph(fullScreen = true)
        val color = mutableStateOf(Color.Red)
        rule.setContent {
            Box(
                Modifier
                    .fillMaxSize()
                    .settingsCardMorphHost(
                        morph,
                        SettingsMorphColors(Color.Black, Color.Black, Color.Unspecified),
                    ).background(color.value),
            )
        }
        rule.onRoot().captureToImage()
        rule.runOnIdle {
            morph.page!!.pendingOrigin = Rect(16f, 340f, 344f, 392f)
            morph.beginPage("documents", true, true)
            color.value = Color.Blue
        }
        rule.onRoot().captureToImage()
        rule.runOnIdle {
            assertTrue(morph.page!!.hasSettledPicture)
            assertTrue(morph.beginPage("details", false, true))
            assertEquals(1, morph.keptPictures)
            val release = morph.holdSecure()
            assertEquals(0, morph.keptPictures)
            assertFalse(morph.page!!.hasSettledPicture)
            assertFalse(morph.page!!.active)
            release()
            assertFalse(morph.beginPage("other", false, true))
        }
    }

    @Test fun chatCaptureIsExcludedWhileTheSafeSettingsExitFrameSurvives() {
        val morph = SettingsCardMorph(fullScreen = true)
        val capture = mutableStateOf(false)
        rule.setContent {
            morph.captureContent = capture.value
            Box(
                Modifier.fillMaxSize().settingsCardMorphHost(
                    morph,
                    SettingsMorphColors(Color.Black, Color.Black, Color.Unspecified),
                ),
            )
        }
        rule.onRoot().captureToImage()
        rule.runOnIdle {
            assertFalse(morph.page!!.hasSettledPicture)
            morph.page!!.pendingOrigin = Rect(16f, 24f, 64f, 72f)
            assertTrue(morph.beginPage("settings", true, true))
            assertEquals(0, morph.keptPictures)
            capture.value = true
        }
        rule.onRoot().captureToImage()
        rule.runOnIdle {
            assertTrue(morph.page!!.hasSettledPicture)
            assertTrue(morph.beginPage("chats", false, true))
            capture.value = false
        }
        rule.onRoot().captureToImage()
        rule.runOnIdle {
            assertFalse(morph.page!!.hasSettledPicture)
            assertEquals(1, morph.keptPictures)
            morph.finish()
        }
        rule.onRoot().captureToImage()
        rule.runOnIdle { assertFalse(morph.page!!.hasSettledPicture) }
    }

    @Test fun aSecureDescendantClearsEveryEnclosingPageRecorder() {
        val parent = SettingsCardMorph(fullScreen = true)
        val child = SettingsCardMorph(fullScreen = true, parent = parent)
        rule.setContent {
            Box(
                Modifier.fillMaxSize().settingsCardMorphHost(
                    parent,
                    SettingsMorphColors(Color.Black, Color.Black, Color.Unspecified),
                ),
            ) {
                Box(
                    Modifier.fillMaxSize().settingsCardMorphHost(
                        child,
                        SettingsMorphColors(Color.Black, Color.Black, Color.Unspecified),
                    ),
                )
            }
        }
        rule.onRoot().captureToImage()
        rule.runOnIdle {
            assertTrue(parent.page!!.hasSettledPicture)
            assertTrue(child.page!!.hasSettledPicture)
            val release = child.holdSecure()
            assertFalse(parent.recording)
            assertFalse(child.recording)
            assertFalse(parent.page!!.hasSettledPicture)
            assertFalse(child.page!!.hasSettledPicture)
            release()
            assertTrue(parent.recording)
            assertTrue(child.recording)
        }
    }
}
