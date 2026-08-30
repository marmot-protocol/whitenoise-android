package dev.ipf.whitenoise.android.ui.navigation

import androidx.compose.animation.core.updateTransition
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.dp
import com.github.takahirom.roborazzi.captureRoboImage
import dev.ipf.whitenoise.android.ui.theme.WhiteNoiseTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "w360dp-h780dp-mdpi")
class ConversationRouteTransitionScreenshotTest {
    @get:Rule
    val composeRule = createComposeRule()

    /** Captures midpoint and terminal route frames in the light theme. */
    @Test
    fun midpointAndTerminalFramesLight() {
        captureFrames(darkTheme = false, amoled = false, themeName = "light")
    }

    /** Captures midpoint and terminal route frames on an AMOLED surface. */
    @Test
    fun midpointAndTerminalFramesAmoled() {
        captureFrames(darkTheme = true, amoled = true, themeName = "amoled")
    }

    /** Drives the production route through deterministic visual checkpoints. */
    private fun captureFrames(
        darkTheme: Boolean,
        amoled: Boolean,
        themeName: String,
    ) {
        var route by mutableStateOf<String?>(null)
        composeRule.mainClock.autoAdvance = false
        composeRule.setContent {
            WhiteNoiseTheme(darkTheme = darkTheme, amoled = amoled) {
                Surface(
                    modifier = Modifier.fillMaxSize().testTag(ROOT_TAG),
                    color = MaterialTheme.colorScheme.background,
                ) {
                    val transition = updateTransition(route, label = "conversation route screenshot")
                    ConversationRouteAnimatedContent(
                        transition = transition,
                        routeForwardDirection = 1,
                        suppressMotion = false,
                        contentKey = { it ?: "chat-list" },
                    ) { destination ->
                        if (destination == null) ChatListFrame() else ConversationFrame()
                    }
                }
            }
        }
        composeRule.runOnUiThread { route = "Design team" }
        // Commit the new target on its own frame. Otherwise the first large
        // clock step only starts the Transition and the captures lag behind
        // their advertised animation timestamps.
        composeRule.mainClock.advanceTimeByFrame()
        composeRule.runOnIdle { }
        composeRule.mainClock.advanceTimeBy(CONVERSATION_ROUTE_TRANSITION_MILLIS / 2L)
        composeRule.runOnIdle { }
        capture("conversation_route_midpoint_$themeName.png")

        composeRule.mainClock.advanceTimeBy(CONVERSATION_ROUTE_TRANSITION_MILLIS / 2L)
        composeRule.mainClock.advanceTimeByFrame()
        composeRule.runOnIdle { }
        capture("conversation_route_terminal_$themeName.png")
    }

    /** Writes one root-surface Roborazzi baseline. */
    private fun capture(fileName: String) {
        composeRule
            .onNodeWithTag(ROOT_TAG)
            .captureRoboImage("src/test/snapshots/$fileName")
    }

    /** Renders the stable chat-list source used by the transition baseline. */
    @androidx.compose.runtime.Composable
    private fun ChatListFrame() {
        Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
            Text(
                text = "Chats",
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 22.dp),
                style = MaterialTheme.typography.headlineMedium,
            )
            HorizontalDivider()
            ChatRow("Design team", "Mina: The review is ready", "2")
            ChatRow("Release planning", "Version 0.7.0 checklist", "")
            ChatRow("Mina", "See you tomorrow", "")
        }
    }

    /** Renders one deterministic source-list row. */
    @androidx.compose.runtime.Composable
    private fun ChatRow(
        title: String,
        preview: String,
        unread: String,
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                Modifier
                    .size(48.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.primaryContainer),
            )
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(title, style = MaterialTheme.typography.titleMedium)
                Text(
                    preview,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (unread.isNotEmpty()) {
                Box(
                    modifier =
                        Modifier
                            .size(24.dp)
                            .clip(CircleShape)
                            .background(MaterialTheme.colorScheme.primary),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(unread, color = MaterialTheme.colorScheme.onPrimary)
                }
            }
        }
    }

    /** Renders the stable conversation destination used by the baseline. */
    @androidx.compose.runtime.Composable
    private fun ConversationFrame() {
        Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("‹", style = MaterialTheme.typography.headlineMedium)
                Spacer(Modifier.width(12.dp))
                Box(
                    Modifier
                        .size(40.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.primaryContainer),
                )
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
            HorizontalDivider()
            Spacer(Modifier.weight(1f))
            MessageBubble("The review is ready for a final pass.", own = false)
            MessageBubble("Great — I’ll take a look now.", own = true)
            Spacer(Modifier.height(18.dp))
            Surface(
                modifier = Modifier.fillMaxWidth().padding(12.dp),
                shape = CircleShape,
                color = MaterialTheme.colorScheme.surfaceContainerHigh,
            ) {
                Text(
                    text = "Message",
                    modifier = Modifier.padding(horizontal = 18.dp, vertical = 14.dp),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }

    /** Renders one deterministic destination message bubble. */
    @androidx.compose.runtime.Composable
    private fun MessageBubble(
        text: String,
        own: Boolean,
    ) {
        Box(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 5.dp),
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
                Text(text = text, modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp))
            }
        }
    }

    private companion object {
        const val ROOT_TAG = "conversation-route-screenshot-root"
    }
}
