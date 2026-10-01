package dev.ipf.whitenoise.android.ui.screenshot

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import com.github.takahirom.roborazzi.captureRoboImage
import dev.ipf.whitenoise.android.ui.conversation.CONVERSATION_TIMELINE_TAIL_GAP
import dev.ipf.whitenoise.android.ui.conversation.CONVERSATION_TIMELINE_VERTICAL_ARRANGEMENT
import dev.ipf.whitenoise.android.ui.conversation.ConversationScrollIndicatorWindow
import dev.ipf.whitenoise.android.ui.conversation.ConversationTimelineViewport
import dev.ipf.whitenoise.android.ui.conversation.conversationScrollIndicator
import dev.ipf.whitenoise.android.ui.conversation.conversationTimelineContentPadding
import dev.ipf.whitenoise.android.ui.conversation.measureConversationTimelinePadding
import dev.ipf.whitenoise.android.ui.theme.WhiteNoiseTheme
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.launch
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Real reversed native layout and production drawing/projection; no manufactured thumb position. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "en-w360dp-h780dp-mdpi")
class ConversationScrollIndicatorScreenshotTest {
    @get:Rule val composeRule = createComposeRule()
    private lateinit var state: LazyListState
    private lateinit var scope: CoroutineScope

    @Test fun newestLight() = capture("conversation_scroll_newest_light", 0f, Environment())

    @Test fun middleDark() = capture("conversation_scroll_middle_dark", 300f, Environment(dark = true))

    @Test fun oldestAmoled() {
        capture("conversation_scroll_oldest_amoled", 3000f, Environment(dark = true, amoled = true))
    }

    @Test fun largeRtlAndRaisedComposer() =
        capture(
            "conversation_scroll_large_rtl_chrome",
            300f,
            Environment(rtl = true, scale = 2f, chrome = 160),
        )

    private data class Environment(
        val dark: Boolean = false,
        val amoled: Boolean = false,
        val rtl: Boolean = false,
        val scale: Float = 1f,
        val chrome: Int = 80,
    )

    private fun capture(
        name: String,
        offset: Float,
        environment: Environment,
    ) {
        render(environment)
        var drag: Job? = null
        try {
            composeRule.runOnIdle {
                drag =
                    scope.launch {
                        state.scroll {
                            scrollBy(offset)
                            awaitCancellation()
                        }
                    }
            }
            composeRule.waitUntil { state.isScrollInProgress }
            composeRule.waitForIdle()
            composeRule.onNodeWithTag("reading-indicator-fixture").captureRoboImage("src/test/snapshots/$name.png")
        } finally {
            composeRule.runOnIdle { drag?.cancel() }
        }
    }

    private fun render(environment: Environment) {
        composeRule.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(
                LocalDensity provides Density(density.density, environment.scale),
                LocalLayoutDirection provides if (environment.rtl) LayoutDirection.Rtl else LayoutDirection.Ltr,
            ) {
                state = rememberLazyListState()
                scope = rememberCoroutineScope()
                val viewport = remember(state) { ConversationTimelineViewport(state) }
                val keys = remember { listOf<Any>("newest", "tall", "older", "oldest") }
                viewport.enabled = true
                viewport.onComposerMeasured(with(density) { environment.chrome.dp.roundToPx() }, 48)
                viewport.onBottomChromeMeasured(with(density) { environment.chrome.dp.roundToPx() })
                WhiteNoiseTheme(darkTheme = environment.dark, amoled = environment.amoled) {
                    Column(Modifier.fillMaxWidth()) {
                        Text("Conversation", Modifier.padding(16.dp), style = MaterialTheme.typography.titleLarge)
                        Box(
                            Modifier
                                .fillMaxWidth()
                                .height(480.dp)
                                .background(MaterialTheme.colorScheme.surface)
                                .conversationScrollIndicator(
                                    state,
                                    viewport,
                                    ConversationScrollIndicatorWindow(keys, 0, keys.size + 1, "fixture"),
                                    true,
                                ).testTag("reading-indicator-fixture"),
                        ) {
                            FixtureTimeline(viewport, keys, environment.chrome)
                            Surface(
                                modifier =
                                    Modifier
                                        .align(Alignment.BottomCenter)
                                        .fillMaxWidth()
                                        .height(environment.chrome.dp),
                                color = MaterialTheme.colorScheme.surfaceContainerHigh,
                                shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp),
                            ) {
                                Text("Message", Modifier.padding(16.dp))
                            }
                        }
                    }
                }
            }
        }
        composeRule.waitForIdle()
    }

    @Composable
    private fun FixtureMessage(
        key: Any,
        index: Int,
    ) {
        Surface(
            shape = RoundedCornerShape(16.dp),
            color = MaterialTheme.colorScheme.surfaceContainer,
        ) {
            Text(
                "$key\n" + "A long message keeps its reading position.\n".repeat(24),
                Modifier
                    .fillMaxWidth()
                    .height(if (index == 1) 600.dp else 160.dp)
                    .padding(12.dp),
            )
        }
    }

    @Composable
    private fun FixtureTimeline(
        viewport: ConversationTimelineViewport,
        keys: List<Any>,
        chrome: Int,
    ) {
        LazyColumn(
            state = state,
            reverseLayout = true,
            verticalArrangement = CONVERSATION_TIMELINE_VERTICAL_ARRANGEMENT,
            contentPadding = conversationTimelineContentPadding(0.dp, chrome.dp),
            modifier =
                Modifier
                    .fillMaxSize()
                    .measureConversationTimelinePadding(
                        viewport,
                        CONVERSATION_TIMELINE_TAIL_GAP,
                        chrome.dp,
                    ).padding(horizontal = 12.dp)
                    .onGloballyPositioned(viewport::onPaintViewportMeasured),
        ) {
            itemsIndexed(keys, key = { _, key -> key }) { index, key ->
                FixtureMessage(key, index)
            }
            item(key = "top-spacer") { Box(Modifier.height(4.dp)) }
        }
    }
}
