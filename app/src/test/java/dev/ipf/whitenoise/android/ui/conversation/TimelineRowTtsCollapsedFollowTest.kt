package dev.ipf.whitenoise.android.ui.conversation

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import dev.ipf.whitenoise.android.R
import dev.ipf.whitenoise.android.audio.tts.projectTtsSpeakableEntry
import dev.ipf.whitenoise.android.state.ConversationController
import dev.ipf.whitenoise.android.state.MessageStatus
import dev.ipf.whitenoise.android.state.TimelineMessage
import dev.ipf.whitenoise.android.state.WhiteNoiseAppState
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.util.Locale

/** Checks actual selected-sentence exposure after a production collapsed row expands. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "w360dp-h780dp-mdpi")
class TimelineRowTtsCollapsedFollowTest {
    @get:Rule
    val composeRule = createComposeRule()
    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()
    private val engine = FakePaintTtsSpeechEngine()
    private val sentenceLayouts = ConversationTtsSentenceLayoutRegistry()
    private val accountId = "01" + "00".repeat(31)
    private val senderId = "02" + "00".repeat(31)
    private val groupId = "04" + "00".repeat(31)
    private val messageId = "07" + "00".repeat(31)
    private lateinit var appState: WhiteNoiseAppState
    private lateinit var fixture: PaintTimelineRow

    @Before
    fun setUp() {
        appState = paintTestAppState(context, "personal", accountId)
        val controller = ConversationController(appState, paintTestGroup(groupId, accountId))
        appState.ttsController.attachEngine(engine)
        fixture = PaintTimelineRow(controller, appState, sentenceLayouts)
    }

    @Test
    fun collapsedPlainStartRevealsSelectedLine() = assertCollapsedStartFollows(markdown = false)

    @Test
    fun collapsedMarkdownStartRevealsSelectedLine() = assertCollapsedStartFollows(markdown = true)

    private fun assertCollapsedStartFollows(markdown: Boolean) {
        val body = (1..90).joinToString("\n") { "Sentence $it about bright things." }
        val document = if (markdown) plainPaintDocument(body) else emptyPaintDocument()
        val record = speakablePaintRecord(messageId, body, groupId, senderId).copy(contentTokens = document)
        val entry = runBlocking { projectTtsSpeakableEntry(record, null, "Alice", { document })!! }
        composeRule.setContent {
            CollapsedStartFollowRow(TimelineMessage("msg:${record.messageIdHex}", record, MessageStatus.Received))
        }
        composeRule.waitForIdle()
        composeRule.onNodeWithText(context.getString(R.string.message_read_more)).assertExists()

        // This line is initially in the collapsed excerpt, as with a retained
        // long-press hit. Expansion in the reversed list moves it off screen.
        composeRule.runOnIdle {
            check(appState.ttsController.speak(listOf(entry), Locale.US, startSentenceIndex = 2))
        }
        composeRule.waitUntil(10_000) {
            val target =
                appState.ttsController.state.value
                    .conversationFollowTargetOrNull()
            target?.sentenceIndex == 2 &&
                ttsSentenceWasRevealed(
                    sentenceLayouts.completeSentenceBounds(target),
                    sentenceLayouts.viewportBoundsInWindow,
                )
        }
        composeRule.onNodeWithText(context.getString(R.string.message_read_more)).assertDoesNotExist()
        val target =
            requireNotNull(
                appState.ttsController.state.value
                    .conversationFollowTargetOrNull(),
            )
        val bounds = requireNotNull(sentenceLayouts.completeSentenceBounds(target))
        val viewport = requireNotNull(sentenceLayouts.viewportBoundsInWindow)
        assertEquals("Selected sentence starts below the player", viewport.top, bounds.top, 2f)
        assertTrue(bounds.bottom <= viewport.bottom + 2f)
    }

    @Composable
    @Suppress("FunctionNaming", "LongMethod")
    private fun CollapsedStartFollowRow(item: TimelineMessage) {
        val listState = rememberLazyListState()
        val policy = remember { ConversationTtsFollowPolicy() }
        val coordinator =
            remember(listState) {
                ConversationScrollCoordinator(
                    LazyListConversationScrollWriter(listState),
                    initialMode = ConversationScrollMode.ReadingHistory(null, 0),
                )
            }
        val state by appState.ttsController.state.collectAsState()
        LaunchedEffect(state.conversationFollowSignal()) {
            policy.observe(state, ownsSession = true)
            var request = policy.claimPendingRequest()
            while (request != null) {
                val current = request
                val succeeded =
                    followTtsTargetInViewport(
                        target = current.target,
                        direction = current.direction,
                        itemKey = item.record.messageIdHex,
                        targetIndex = 0,
                        estimatedItemHeightPx = null,
                        listState = listState,
                        scrollCoordinator = coordinator,
                        sentenceLayouts = sentenceLayouts,
                        claimPreposition = { policy.claimPreposition(current.target) },
                        claimCorrectiveScroll = { policy.claimCorrectiveScroll(current.target) },
                        resolveTargetIndex = { 0 },
                        isCurrentTarget = { policy.isCurrentTarget(current.target) },
                        currentScrollAnchor = {
                            ConversationScrollAnchor(0, listState.firstVisibleItemScrollOffset, null, null)
                        },
                    )
                if (succeeded || !policy.retryFailedFollowAttempt(current.target)) break
                request = policy.claimPendingRequest()
            }
        }
        Column {
            Text("Read aloud player", Modifier.height(48.dp))
            LazyColumn(
                state = listState,
                reverseLayout = true,
                contentPadding = PaddingValues(top = 23.dp, bottom = 51.dp),
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .height(320.dp)
                        .onGloballyPositioned { sentenceLayouts.updateViewportBounds(it.boundsInWindow()) },
            ) {
                item(key = item.record.messageIdHex) { fixture.Render(item, collapseLongMessages = true) }
            }
        }
    }
}
