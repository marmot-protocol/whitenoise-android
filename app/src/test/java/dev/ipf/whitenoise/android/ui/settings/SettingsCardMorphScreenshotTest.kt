package dev.ipf.whitenoise.android.ui.settings

import android.content.Context
import android.provider.Settings
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import com.github.takahirom.roborazzi.captureRoboImage
import dev.ipf.whitenoise.android.ui.theme.WhiteNoiseTheme
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowLooper
import java.util.concurrent.TimeUnit

/**
 * Moving between Settings routes morphs card N of the old screen into card N of the new one, in
 * both directions; extra old cards fade where they were and extra new cards fade in. Reduced
 * motion settles at once.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], qualifiers = "en-w360dp-h780dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class SettingsCardMorphScreenshotTest {
    @get:Rule val composeRule = createComposeRule()
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private var originalAnimatorScale = 1f
    private val route = mutableStateOf(0)
    private val animations = mutableStateOf(true)

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
        update { route.value = 1 }
        composeRule.mainClock.advanceTimeByFrame()
        capture("settings_card_morph_reduced_motion_light")
    }

    private fun navigate(
        variant: String,
        dark: Boolean,
        largeRtl: Boolean,
        from: Int,
        to: Int,
    ) {
        render(dark, largeRtl, start = from)
        // The paused test looper only draws on capture; draw the settled route once like a real frame.
        composeRule.onRoot().captureToImage()
        update { route.value = to }
        composeRule.mainClock.advanceTimeByFrame()
        advanceFrames(SETTINGS_CARD_MORPH_MILLIS / 6L)
        capture("settings_card_morph_early_$variant")
        advanceFrames(SETTINGS_CARD_MORPH_MILLIS / 4L)
        capture("settings_card_morph_mid_$variant")
        advanceFrames(SETTINGS_CARD_MORPH_MILLIS.toLong())
        capture("settings_card_morph_settled_$variant")
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
                        val morph = remember { SettingsCardMorph() }
                        val (_, host) = rememberSettingsCardMorphHost(morph, route.value, "account", animations.value)
                        CompositionLocalProvider(LocalSettingsCardMorph provides morph) {
                            Box(Modifier.fillMaxSize().then(host)) { Route(route.value) }
                        }
                    }
                }
            }
        }
        advanceFrames(SETTLE_MILLIS)
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

    private fun capture(name: String) {
        composeRule.onRoot().captureRoboImage("src/test/snapshots/$name.png")
    }
}

/** Home has three cards of different heights; the detail has two, so one old card has no partner. */
@Composable
private fun Route(route: Int) {
    val cards = if (route == 0) HOME_CARDS else DETAIL_CARDS
    Column(Modifier.fillMaxSize().padding(top = 16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Text(
            if (route == 0) "Settings" else "Appearance",
            style = MaterialTheme.typography.headlineSmall,
            modifier = Modifier.padding(horizontal = 32.dp),
        )
        cards.forEach { rows ->
            SettingsPanel {
                rows.forEach { row -> Text(row, Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 14.dp)) }
            }
        }
    }
}

private const val SETTLE_MILLIS = 1_000L
private const val FRAME_MILLIS = 16L
private val HOME_CARDS = listOf(listOf("Profile"), listOf("Appearance", "Notifications", "Privacy"), listOf("About"))
private val DETAIL_CARDS = listOf(listOf("Theme", "AMOLED black", "Text size", "Animations"), listOf("Language"))
