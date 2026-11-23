package dev.ipf.whitenoise.android.ui.conversation

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import dev.ipf.whitenoise.android.R
import dev.ipf.whitenoise.android.ui.settings.SettingsCardMorph
import dev.ipf.whitenoise.android.ui.settings.SettingsScaffold
import dev.ipf.whitenoise.android.ui.theme.WhiteNoiseTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowLooper
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "w360dp-h780dp-mdpi")
class ConversationSettingsMotionTest {
    @get:Rule val rule = createComposeRule()
    private val context = ApplicationProvider.getApplicationContext<Context>()

    @Test fun theLiveViewportFadesInBothDirectionsAtItsNativeCoordinates() {
        val route = mutableStateOf<ConversationSettingsRoute?>(null)
        val owner = mutableStateOf("first")
        rule.setContent {
            WhiteNoiseTheme(darkTheme = true, amoled = true) {
                Box(Modifier.fillMaxSize().background(Color.Black)) {
                    val morph = remember(owner.value) { SettingsCardMorph(fullScreen = true) }
                    val motion = rememberConversationSettingsMotion(route.value, owner.value, morph, animate = true)
                    ConversationSettingsPage(motion, Modifier) {
                        SettingsScaffold(title = "Chat settings", onBack = { route.value = null }) {
                            Box(Modifier.size(40.dp).testTag("settings-action").clickable {})
                        }
                    }
                    if (motion.keepTranscript) {
                        ConversationSettingsLayer(Modifier.fillMaxSize(), motion.transcriptActive) {
                            Box(Modifier.conversationSettingsHost(motion).padding(start = 32.dp, top = 160.dp).conversationSettingsBody(motion)) {
                                Box(Modifier.size(32.dp).background(Color.Red).testTag("message"))
                            }
                        }
                    }
                }
            }
        }
        rule.onNodeWithTag("message").assertIsDisplayed()
        assertEquals(1f, messageRed(), 0.01f)
        rule.mainClock.autoAdvance = false
        rule.runOnUiThread {
            route.value = ConversationSettingsRoute.Details
            Snapshot.sendApplyNotifications()
        }
        repeat(6) {
            rule.mainClock.advanceTimeByFrame()
            ShadowLooper.idleMainLooper(16L, TimeUnit.MILLISECONDS)
        }
        val openingRed = messageRed()
        assertTrue("opening transcript red=$openingRed", openingRed in 0.02f..0.98f)
        rule.onNodeWithTag("message").assertDoesNotExist()
        rule.onNodeWithTag("settings-action").assertDoesNotExist()
        rule.onNodeWithContentDescription(context.getString(R.string.back)).assertIsDisplayed().performClick()
        rule.runOnIdle { Snapshot.sendApplyNotifications() }
        repeat(4) {
            rule.mainClock.advanceTimeByFrame()
            ShadowLooper.idleMainLooper(16L, TimeUnit.MILLISECONDS)
        }
        val reversingRed = messageRed()
        assertTrue("reversing transcript red=$reversingRed", reversingRed in 0.02f..0.98f)
        rule.mainClock.advanceTimeBy(1_000L)
        assertEquals(1f, messageRed(), 0.01f)
        rule.onNodeWithTag("message").assertIsDisplayed()

        rule.runOnUiThread {
            route.value = ConversationSettingsRoute.Composer
            Snapshot.sendApplyNotifications()
        }
        repeat(6) {
            rule.mainClock.advanceTimeByFrame()
            ShadowLooper.idleMainLooper(16L, TimeUnit.MILLISECONDS)
        }
        val composerRed = messageRed()
        assertTrue("composer settings opening transcript red=$composerRed", composerRed in 0.02f..0.98f)
        rule.runOnUiThread {
            owner.value = "replacement"
            Snapshot.sendApplyNotifications()
        }
        rule.mainClock.advanceTimeByFrame()
        ShadowLooper.idleMainLooper(16L, TimeUnit.MILLISECONDS)
        assertEquals(0f, messageRed(), 0.01f)
    }

    private fun messageRed() =
        rule
            .onRoot()
            .captureToImage()
            .toPixelMap()[48, 176]
            .red
}
