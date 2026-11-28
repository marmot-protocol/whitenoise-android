package dev.ipf.whitenoise.android.ui.screenshot

import android.app.Application
import android.graphics.Bitmap
import androidx.activity.OnBackPressedDispatcher
import androidx.activity.compose.LocalOnBackPressedDispatcherOwner
import androidx.compose.material3.Surface
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.unit.LayoutDirection
import androidx.test.core.app.ApplicationProvider
import com.github.takahirom.roborazzi.captureRoboImage
import com.github.takahirom.roborazzi.captureScreenRoboImage
import dev.ipf.whitenoise.android.R
import dev.ipf.whitenoise.android.state.DraftPersistence
import dev.ipf.whitenoise.android.state.DraftStore
import dev.ipf.whitenoise.android.state.WhiteNoiseAppState
import dev.ipf.whitenoise.android.ui.navigation.SettingsDetail
import dev.ipf.whitenoise.android.ui.settings.SETTINGS_CARD_MORPH_MILLIS
import dev.ipf.whitenoise.android.ui.settings.SettingsHomeViewport
import dev.ipf.whitenoise.android.ui.settings.SettingsScreen
import dev.ipf.whitenoise.android.ui.theme.WhiteNoiseTheme
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/** Actual Settings destination at the start, middle and end of its card morph. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "en-w360dp-h780dp-mdpi")
class SettingsRouteMotionScreenshotTest {
    @get:Rule val rule = createComposeRule()
    private val captureFrames = System.getenv("ANIMATION_CAPTURE_FRAMES") == "1"

    @Test fun light() = capture("light")

    @Test fun dark() = capture("dark", dark = true)

    @Test fun amoled() = capture("amoled", dark = true, amoled = true)

    @Test fun largeRtl() = capture("large_rtl", dark = true, amoled = true, rtl = true)

    /** Real home navigation supplies the connected source and restores it through repeated Back cycles. */
    @Test fun notificationsUsesTheActualHomeEntryAcrossThreeCycles() =
        captureActualHomeEntry(
            SettingsDetail.Notifications,
            R.string.notifications,
            "notifications",
            interruptedProbe = true,
        )

    @Test fun accountUsesActualHomeEntryAcrossThreeCycles() {
        captureActualHomeEntry(SettingsDetail.Account, R.string.account, "account")
    }

    @Test fun appearanceUsesActualHomeEntryAcrossThreeCycles() {
        captureActualHomeEntry(SettingsDetail.Appearance, R.string.appearance, "appearance")
    }

    private fun actualHomeState(app: Application): WhiteNoiseAppState =
        WhiteNoiseAppState(
            app,
            DraftStore(
                object : DraftPersistence {
                    override fun read(): Map<String, String> = emptyMap()

                    override fun write(
                        key: String,
                        value: String?,
                    ) = Unit
                },
            ),
            accountIdHexResolver = { null },
            accounts =
                listOf(
                    dev.ipf.marmotkit.AccountSummaryFfi(
                        label = "motion-fixture",
                        accountIdHex = "11".repeat(32),
                        localSigning = true,
                        externalSigning = false,
                        signedOut = false,
                        running = true,
                        unlinked = false,
                    ),
                ),
            activeAccountRef = "motion-fixture",
            profileReader = { null },
            profileDisplayNameReader = { "Motion fixture" },
        )

    // Keep three actual-home cycles and interrupted navigation checks on their shared frame clock.
    @Suppress("LongMethod", "CyclomaticComplexMethod")
    private fun captureActualHomeEntry(
        destination: SettingsDetail,
        titleRes: Int,
        name: String,
        interruptedProbe: Boolean = false,
    ) {
        val app: Application = ApplicationProvider.getApplicationContext()
        val state = actualHomeState(app)
        val detail = mutableStateOf<SettingsDetail?>(null)
        var backDispatcher: OnBackPressedDispatcher? = null
        rule.setContent {
            backDispatcher = LocalOnBackPressedDispatcherOwner.current?.onBackPressedDispatcher
            WhiteNoiseTheme(darkTheme = true, amoled = true) {
                SettingsScreen(
                    state,
                    {},
                    {},
                    {},
                    detail.value,
                    { detail.value = it },
                    SettingsHomeViewport.Top,
                    {},
                    selfUpdateEnabled = true,
                )
            }
        }
        repeat(if (captureFrames && interruptedProbe) 4 else 3) { cycle ->
            rule.mainClock.autoAdvance = true
            val entry = rule.onNodeWithText(app.getString(titleRes))
            entry.performScrollTo()
            val sourceBounds = entry.fetchSemanticsNode().boundsInRoot
            val sourcePixels = rule.onRoot().captureToImage().asAndroidBitmap()
            rule.mainClock.autoAdvance = false
            captureDenseSource("${name}_cycle_$cycle")
            entry.performClick()
            rule.mainClock.advanceTimeByFrame()
            org.junit.Assert.assertEquals(destination, detail.value)
            // Capture the production draw path while its real destination controls are inert.
            val early = rule.onRoot().captureToImage()
            assertTrue(early.width > 0 && early.height > 0)
            if (destination == SettingsDetail.Notifications || destination == SettingsDetail.Account) {
                assertOpaqueNativeSides(early.asAndroidBitmap())
            }
            rule.onNodeWithContentDescription(app.getString(R.string.back)).assertDoesNotExist()
            if (interruptedProbe && cycle == (if (captureFrames) 3 else 0)) {
                // System Back remains available while the actual route's pointer controls are inert.
                rule.mainClock.advanceTimeBy(80L)
                rule.runOnIdle { requireNotNull(backDispatcher).onBackPressed() }
            } else {
                if (captureFrames) {
                    captureDenseTransition("${name}_cycle_$cycle")
                } else {
                    // Probe either side of the rejected 40% blackout as well as late arrival.
                    for (delta in listOf(64L, 64L, 16L, 80L, 80L)) {
                        rule.mainClock.advanceTimeBy(delta)
                        if (destination == SettingsDetail.Notifications || destination == SettingsDetail.Account) {
                            assertOpaqueNativeSides(rule.onRoot().captureToImage().asAndroidBitmap())
                        }
                    }
                    rule.mainClock.advanceTimeBy(64L)
                    // Allow for admission and animateTo's first sample before checking the settled route.
                    rule.mainClock.advanceTimeByFrame()
                }
                captureDenseSource("${name}_cycle_${cycle}_back")
                rule.onNodeWithContentDescription(app.getString(R.string.back)).performClick()
            }
            rule.mainClock.advanceTimeByFrame()
            val returning = rule.onRoot().captureToImage()
            assertTrue(returning.width == early.width && returning.height == early.height)
            if (destination == SettingsDetail.Notifications || destination == SettingsDetail.Account) {
                assertOpaqueNativeSides(returning.asAndroidBitmap())
            }
            if (captureFrames && cycle < 3) {
                captureDenseTransition("${name}_cycle_${cycle}_back")
            } else {
                for (delta in listOf(64L, 64L, 16L, 80L, 80L)) {
                    rule.mainClock.advanceTimeBy(delta)
                    if (destination == SettingsDetail.Notifications || destination == SettingsDetail.Account) {
                        assertOpaqueNativeSides(rule.onRoot().captureToImage().asAndroidBitmap())
                    }
                }
                rule.mainClock.advanceTimeBy(64L)
                // Include a final frame for route cleanup after the animation settles.
                rule.mainClock.advanceTimeByFrame()
            }
            org.junit.Assert.assertEquals(null, detail.value)
            entry.assertIsDisplayed()
            org.junit.Assert.assertEquals(sourceBounds, entry.fetchSemanticsNode().boundsInRoot)
            val restoredPixels = rule.onRoot().captureToImage().asAndroidBitmap()
            // The real restored source keeps its native side and owned bottom seam at the endpoint.
            val sideX = sourceBounds.left.toInt()
            val sideY = sourceBounds.center.y.toInt()
            assertTrue(sourcePixels.getPixel(sideX, sideY) != android.graphics.Color.BLACK)
            org.junit.Assert.assertEquals(sourcePixels.getPixel(sideX, sideY), restoredPixels.getPixel(sideX, sideY))
            val seamX = sourceBounds.center.x.toInt()
            val seamY = sourceBounds.bottom.toInt() - 1
            org.junit.Assert.assertEquals(sourcePixels.getPixel(seamX, seamY), restoredPixels.getPixel(seamX, seamY))
        }
    }

    /** Real border sides occupy the compact margin; labels/icons cannot satisfy this paired run. */
    private fun assertOpaqueNativeSides(bitmap: Bitmap) {
        var run = 0
        var longest = 0
        for (y in 80 until bitmap.height - 24) {
            fun opaque(x: Int): Boolean {
                val pixel = bitmap.getPixel(x, y)
                return android.graphics.Color.red(pixel) >= 250 &&
                    android.graphics.Color.green(pixel) >= 250 &&
                    android.graphics.Color.blue(pixel) >= 250
            }
            val left = (16..18).any(::opaque)
            val right = (bitmap.width - 19..bitmap.width - 17).any(::opaque)
            run = if (left && right) run + 1 else 0
            longest = maxOf(longest, run)
        }
        assertTrue(
            "actual route keeps an opaque pair of native outline sides, including the former blackout",
            longest >= 24,
        )
    }

    private fun captureDenseSource(name: String) {
        if (!captureFrames) return
        val directory = File("build/settings-route-motion/$name").apply { mkdirs() }
        captureScreenRoboImage(File(directory, "source.png").path)
    }

    private fun captureDenseTransition(name: String) {
        val directory = File("build/settings-route-motion/$name").apply { mkdirs() }
        repeat(32) { frame ->
            if (name.startsWith("notifications_") ||
                name.startsWith("account_")
            ) {
                assertOpaqueNativeSides(rule.onRoot().captureToImage().asAndroidBitmap())
            }
            captureScreenRoboImage(File(directory, "frame-%03d.png".format(frame)).path)
            rule.mainClock.advanceTimeBy(16)
        }
    }

    // Keep this single route capture setup and frame assertions together.
    @Suppress("LongMethod")
    private fun capture(
        name: String,
        dark: Boolean = false,
        amoled: Boolean = false,
        rtl: Boolean = false,
    ) {
        val app: Application = ApplicationProvider.getApplicationContext()
        val appState =
            WhiteNoiseAppState(
                app,
                DraftStore(
                    object : DraftPersistence {
                        override fun read(): Map<String, String> = emptyMap()

                        override fun write(
                            key: String,
                            value: String?,
                        ) = Unit
                    },
                ),
                accountIdHexResolver = { null },
                accounts =
                    listOf(
                        dev.ipf.marmotkit.AccountSummaryFfi(
                            label = "motion-fixture",
                            accountIdHex = "11".repeat(32),
                            localSigning = true,
                            externalSigning = false,
                            signedOut = false,
                            running = true,
                            unlinked = false,
                        ),
                    ),
                activeAccountRef = "motion-fixture",
                profileReader = { null },
                profileDisplayNameReader = { "Motion fixture" },
            )
        val detail = mutableStateOf<SettingsDetail?>(SettingsDetail.Chats)
        rule.mainClock.autoAdvance = false
        rule.setContent {
            CompositionLocalProvider(
                LocalLayoutDirection provides if (rtl) LayoutDirection.Rtl else LayoutDirection.Ltr,
            ) {
                WhiteNoiseTheme(darkTheme = dark, amoled = amoled, fontScale = if (rtl) 2f else 1f) {
                    Surface {
                        SettingsScreen(
                            appState,
                            {},
                            {},
                            {},
                            detail.value,
                            { detail.value = it },
                            SettingsHomeViewport.Top,
                            {},
                            // Pin the flavor-dependent update card so Play and Zapstore share one baseline.
                            selfUpdateEnabled = true,
                        )
                    }
                }
            }
        }
        rule.mainClock.autoAdvance = true
        val gestures = rule.onNodeWithText(app.getString(R.string.gestures_title))
        gestures.performScrollTo()
        rule.mainClock.autoAdvance = false
        gestures.performClick()
        rule.mainClock.advanceTimeByFrame()
        // The actual Gestures row supplies the container origin; capture its first, middle and settled frames.
        captureFrame(name, "first", inked = false)
        rule.mainClock.advanceTimeBy(SETTINGS_CARD_MORPH_MILLIS / 2L)
        captureFrame(name, "middle")
        rule.mainClock.advanceTimeBy(SETTINGS_CARD_MORPH_MILLIS.toLong())
        captureFrame(name, "settled")
    }

    /** Inked frames must contain actual gesture-row ink, not merely its top bar or semantics. */
    private fun captureFrame(
        name: String,
        frame: String,
        inked: Boolean = true,
    ) {
        val bitmap = rule.onRoot().captureToImage().asAndroidBitmap()
        val bounds =
            if (frame == "settled") {
                rule
                    .onNodeWithTag("gestures.MessageLeft", useUnmergedTree = true)
                    .assertIsDisplayed()
                    .fetchSemanticsNode()
                    .boundsInRoot
            } else {
                androidx.compose.ui.geometry
                    .Rect(0f, bitmap.height * 0.15f, bitmap.width.toFloat(), bitmap.height.toFloat())
            }
        val left = bounds.left.toInt().coerceIn(0, bitmap.width - 1)
        val right = bounds.right.toInt().coerceIn(left + 1, bitmap.width)
        val top = bounds.top.toInt().coerceIn(0, bitmap.height - 1)
        val bottom = bounds.bottom.toInt().coerceIn(top + 1, bitmap.height)
        if (inked) assertGestureRowInk(bitmap, left, top, right, bottom)
        bitmap.captureRoboImage("src/test/snapshots/settings_motion_${name}_$frame.png")
    }

    /** Empty-body captures have one flat fill; real text and controls provide many anti-aliased colors. */
    private fun assertGestureRowInk(
        bitmap: Bitmap,
        left: Int,
        top: Int,
        right: Int,
        bottom: Int,
    ) {
        val colors = mutableSetOf<Int>()
        for (y in top until bottom) {
            for (x in left until right) colors += bitmap.getPixel(x, y)
            if (colors.size > 8) return
        }
        assertTrue("Gesture row body must render once the page fades in", colors.size > 8)
    }
}
