package dev.ipf.whitenoise.android.ui.navigation

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import com.github.takahirom.roborazzi.captureRoboImage
import dev.ipf.whitenoise.android.ui.theme.WhiteNoiseTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Header band frames on chat open and Back: it fades through with the page instead of cutting. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "w360dp-h780dp-mdpi")
class ConversationRouteHeaderMotionScreenshotTest {
    @get:Rule
    val rule = createComposeRule()

    @Test
    fun openAndBackAmoled() {
        capture(darkTheme = true, amoled = true, name = "amoled", back = true)
    }

    @Test
    fun openLight() {
        capture(darkTheme = false, amoled = false, name = "light")
    }

    @Test
    fun openRtlLargeFont() {
        capture(darkTheme = true, amoled = true, name = "rtl_font150", rtl = true, fontScale = 1.5f)
    }

    @Suppress("LongParameterList")
    private fun capture(
        darkTheme: Boolean,
        amoled: Boolean,
        name: String,
        back: Boolean = false,
        rtl: Boolean = false,
        fontScale: Float = 1f,
    ) {
        var route by mutableStateOf<String?>(null)
        rule.mainClock.autoAdvance = false
        rule.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(
                LocalLayoutDirection provides if (rtl) LayoutDirection.Rtl else LayoutDirection.Ltr,
                LocalDensity provides Density(density.density, fontScale),
            ) {
                WhiteNoiseTheme(darkTheme = darkTheme, amoled = amoled) {
                    Surface(Modifier.fillMaxSize().testTag(ROOT_TAG), color = MaterialTheme.colorScheme.background) {
                        ConversationRouteAnimatedContent(
                            transition = rememberConversationRouteTransition(route, suppressMotion = false),
                            routeForwardDirection = 1,
                            suppressMotion = false,
                            contentKey = { it ?: "chat-list" },
                            activatingBounds = { if (it != null) Rect(16f, 150f, 344f, 226f) else null },
                        ) { if (it == null) ListPage() else ChatPage() }
                    }
                }
            }
        }
        rule.mainClock.advanceTimeBy(400)
        rule.runOnUiThread { route = "Design team" }
        rule.mainClock.advanceTimeByFrame()
        rule.runOnIdle { }
        if (!rtl) {
            rule.mainClock.advanceTimeBy(48)
            shoot("conversation_route_header_open_early_$name.png")
            rule.mainClock.advanceTimeBy(64)
        } else {
            rule.mainClock.advanceTimeBy(112)
        }
        shoot("conversation_route_header_open_mid_$name.png")
        if (back) {
            rule.mainClock.advanceTimeBy(400)
            rule.runOnUiThread { route = null }
            rule.mainClock.advanceTimeByFrame()
            rule.runOnIdle { }
            rule.mainClock.advanceTimeBy(150)
            shoot("conversation_route_header_back_mid_$name.png")
        }
    }

    private fun shoot(file: String) {
        rule.onNodeWithTag(ROOT_TAG).captureRoboImage("src/test/snapshots/$file")
    }

    @Composable
    private fun ListPage() {
        Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
            Column(Modifier.conversationRouteHeaderMotion()) {
                Text(
                    "Chats",
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 22.dp),
                    style = MaterialTheme.typography.headlineMedium,
                )
                Row(Modifier.padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf("All", "Unread", "Groups").forEach { Pill(it) }
                }
            }
            Column(Modifier.fillMaxWidth().weight(1f).padding(top = 8.dp).conversationRouteBodyMotion()) {
                ChatRow("Design team", "Mina: The review is ready")
                ChatRow("Release planning", "Version 0.7.0 checklist")
                ChatRow("Mina", "See you tomorrow")
            }
        }
    }

    @Composable
    private fun ChatPage() {
        Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
            Row(
                Modifier.fillMaxWidth().conversationRouteHeaderMotion().padding(horizontal = 16.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("←", style = MaterialTheme.typography.headlineMedium)
                Spacer(Modifier.width(12.dp))
                Box(Modifier.size(40.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primary))
                Spacer(Modifier.width(12.dp))
                Column {
                    Text("Design team", style = MaterialTheme.typography.titleMedium)
                    Text(
                        "7 members",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            Column(
                Modifier.fillMaxWidth().weight(1f).conversationRouteBodyMotion(),
                verticalArrangement = Arrangement.Bottom,
            ) {
                Bubble("The review is ready for a final pass.", own = false)
                Bubble("Great, I'll take a look now.", own = true)
            }
            Surface(
                modifier = Modifier.fillMaxWidth().padding(12.dp),
                shape = CircleShape,
                color = MaterialTheme.colorScheme.surfaceContainerHigh,
            ) {
                Text(
                    "Message",
                    modifier = Modifier.padding(horizontal = 18.dp, vertical = 14.dp),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }

    @Composable
    private fun Pill(label: String) {
        Surface(shape = CircleShape, color = MaterialTheme.colorScheme.surfaceContainerHigh) {
            Text(label, modifier = Modifier.padding(horizontal = 14.dp, vertical = 6.dp))
        }
    }

    @Composable
    private fun ChatRow(
        title: String,
        preview: String,
    ) {
        Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(48.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primary))
            Spacer(Modifier.width(14.dp))
            Column {
                Text(title, style = MaterialTheme.typography.titleMedium)
                Text(preview, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }

    @Composable
    private fun Bubble(
        text: String,
        own: Boolean,
    ) {
        Box(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 5.dp),
            contentAlignment = if (own) Alignment.CenterEnd else Alignment.CenterStart,
        ) {
            Surface(
                shape = MaterialTheme.shapes.large,
                color =
                    if (own) {
                        MaterialTheme.colorScheme.primaryContainer
                    } else {
                        MaterialTheme.colorScheme.surfaceContainerHigh
                    },
            ) {
                Text(text, modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp))
            }
        }
    }

    private companion object {
        const val ROOT_TAG = "conversation-route-header-root"
    }
}
