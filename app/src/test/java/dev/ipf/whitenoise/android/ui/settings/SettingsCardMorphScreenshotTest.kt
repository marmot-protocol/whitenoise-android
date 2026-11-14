package dev.ipf.whitenoise.android.ui.settings

import android.content.Context
import android.provider.Settings
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import com.github.takahirom.roborazzi.captureRoboImage
import dev.ipf.whitenoise.android.ui.theme.WhiteNoiseTheme
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowLooper
import java.util.concurrent.TimeUnit
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor

/** Production native Settings routes retain opaque target chrome during both directions. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], qualifiers = "en-w360dp-h780dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class SettingsCardMorphScreenshotTest {
    @get:Rule val composeRule = createComposeRule()
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private var originalAnimatorScale = 1f
    private val route = mutableStateOf(0)
    private val animations = mutableStateOf(true)
    private var morph: SettingsCardMorph? = null
    private var headerBounds = Rect.Zero

    @Before
    fun enableTestMotion() {
        originalAnimatorScale =
            Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f)
        setAnimatorScale(1f)
    }

    @After
    fun restoreMotionPolicy() {
        setAnimatorScale(originalAnimatorScale)
    }

    private fun setAnimatorScale(scale: Float) {
        Settings.Global.putFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, scale)
        context.contentResolver.notifyChange(Settings.Global.getUriFor(Settings.Global.ANIMATOR_DURATION_SCALE), null)
    }

    @Test fun forwardMorphsCardsInOrderLight() =
        navigate(
            "forward_light",
            dark = false,
            largeRtl = false,
            from = 0,
            to = 1,
        )

    @Test fun backMorphsCardsInOrderLargeRtlDark() =
        navigate(
            "back_large_rtl_dark",
            dark = true,
            largeRtl = true,
            from = 1,
            to = 0,
        )

    @Test fun reducedMotionSwitchesAtOnce() {
        render(dark = false, largeRtl = false, start = 0)
        update { animations.value = false }
        composeRule.mainClock.advanceTimeByFrame()
        composeRule.onNodeWithText("Appearance", useUnmergedTree = true).performClick()
        composeRule.mainClock.advanceTimeByFrame()
        assertFalse(requireNotNull(morph?.page).active)
        capture("settings_card_morph_reduced_motion_light")
    }

    private fun navigate(
        variant: String,
        dark: Boolean,
        largeRtl: Boolean,
        from: Int,
        to: Int,
    ) {
        render(dark, largeRtl, start = 0)
        composeRule.onRoot().captureToImage()
        if (from == 1) {
            composeRule.onNodeWithText("Appearance", useUnmergedTree = true).performClick()
            advanceFrames(SETTLE_MILLIS)
            assertFalse(requireNotNull(morph?.page).active)
        }
        // The paused test looper only draws on capture; draw the settled route once like a real frame.
        composeRule.onRoot().captureToImage()
        if (to == 1) {
            composeRule.onNodeWithText("Appearance", useUnmergedTree = true).performClick()
        } else {
            update { route.value = to }
        }
        // Commit the route, admit its measured destination, then start the production animation clock.
        repeat(3) {
            composeRule.mainClock.advanceTimeByFrame()
            composeRule.onRoot().captureToImage()
        }
        advanceFrames(SETTINGS_CARD_MORPH_MILLIS / 6L)
        val early = captureActiveFrame("settings_card_morph_early_$variant")
        advanceFrames(SETTINGS_CARD_MORPH_MILLIS / 4L)
        val mid = captureActiveFrame("settings_card_morph_mid_$variant")
        advanceFrames(SETTINGS_CARD_MORPH_MILLIS.toLong())
        assertFalse(requireNotNull(morph?.page).active)
        capture("settings_card_morph_settled_$variant")
        val settled = composeRule.onRoot().captureToImage()
        assertNativeHeader(settled, early)
        assertNativeHeader(settled, mid)
    }

    private fun render(
        dark: Boolean,
        largeRtl: Boolean,
        start: Int,
    ) {
        route.value = start
        composeRule.mainClock.autoAdvance = false
        composeRule.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(
                LocalLayoutDirection provides if (largeRtl) LayoutDirection.Rtl else LayoutDirection.Ltr,
                LocalDensity provides Density(density.density, if (largeRtl) 1.6f else 1f),
            ) {
                WhiteNoiseTheme(darkTheme = dark) {
                    Surface(Modifier.fillMaxSize()) {
                        SettingsRouteMotion(
                            target = route.value,
                            depth = route.value,
                            ownerKey = "account",
                            animationsEnabled = animations.value,
                            cardMorph = true,
                        ) { target ->
                            val owner = LocalSettingsCardMorph.current
                            SideEffect { morph = owner }
                            Route(
                                target,
                                Modifier.onGloballyPositioned { headerBounds = it.boundsInRoot() },
                                onNavigate = { route.value = 1 },
                            )
                        }
                    }
                }
            }
        }
        advanceFrames(SETTLE_MILLIS)
        assertNotNull(morph?.page)
    }

    private fun advanceFrames(millis: Long) {
        repeat((millis / FRAME_MILLIS).toInt().coerceAtLeast(1)) {
            composeRule.mainClock.advanceTimeByFrame()
            ShadowLooper.idleMainLooper(FRAME_MILLIS, TimeUnit.MILLISECONDS)
        }
    }

    private fun update(block: () -> Unit) {
        composeRule.runOnUiThread {
            block()
            Snapshot.sendApplyNotifications()
        }
    }

    private fun captureActiveFrame(name: String): ImageBitmap {
        val image = composeRule.onRoot().captureToImage()
        val owner = requireNotNull(morph)
        assertTrue("Native page must be active at $name", requireNotNull(owner.page).active)
        assertTrue("Native page clock must advance at $name: ${owner.time()}", owner.time() > 0f && owner.time() < 1f)
        capture(name)
        return image
    }

    /** Compare real target title/back-icon pixels, excluding the header/body boundary. */
    private fun assertNativeHeader(
        reference: ImageBitmap,
        frame: ImageBitmap,
    ) {
        assertTrue("Native header must have measured bounds", !headerBounds.isEmpty)
        val expected = reference.toPixelMap()
        val actual = frame.toPixelMap()
        for (y in ceil(headerBounds.top).toInt() + 1 until floor(headerBounds.bottom).toInt() - 1) {
            for (x in ceil(headerBounds.left).toInt() + 1 until floor(headerBounds.right).toInt() - 1) {
                val a = expected[x, y]
                val b = actual[x, y]
                assertTrue(
                    "Target header changed at ($x, $y)",
                    abs(a.red - b.red) <= 2f / 255f &&
                        abs(a.green - b.green) <= 2f / 255f &&
                        abs(a.blue - b.blue) <= 2f / 255f &&
                        abs(a.alpha - b.alpha) <= 2f / 255f,
                )
            }
        }
    }

    private fun capture(name: String) {
        composeRule.onRoot().captureRoboImage("src/test/snapshots/$name.png")
    }
}

/** Home has three cards of different heights; the detail has two, so one old card has no partner. */
@Composable
private fun Route(
    route: Int,
    topBarModifier: Modifier,
    onNavigate: () -> Unit,
) {
    val cards = if (route == 0) HOME_CARDS else DETAIL_CARDS
    SettingsScaffold(
        title = if (route == 0) "Settings" else "Appearance",
        onBack = {},
        topBarModifier = topBarModifier,
    ) {
        SettingsNativeViewport { viewport ->
            Column(
                Modifier.fillMaxSize().then(viewport).padding(top = 16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                cards.forEachIndexed { index, rows ->
                    SettingsPanel(surface = route to index) {
                        rows.forEach { row ->
                            val (origin, navigate) = rememberSettingsRouteClick(onNavigate)
                            Text(
                                row,
                                Modifier
                                    .fillMaxWidth()
                                    .then(
                                        if (route == 0 && row == "Appearance") {
                                            origin.clickable(onClick = navigate)
                                        } else {
                                            Modifier
                                        },
                                    ).padding(horizontal = 16.dp, vertical = 14.dp),
                            )
                        }
                    }
                }
            }
        }
    }
}

private const val SETTLE_MILLIS = 1_000L
private const val FRAME_MILLIS = 16L
private val HOME_CARDS = listOf(listOf("Profile"), listOf("Appearance", "Notifications", "Privacy"), listOf("About"))
private val DETAIL_CARDS = listOf(listOf("Theme", "AMOLED black", "Text size", "Animations"), listOf("Language"))
