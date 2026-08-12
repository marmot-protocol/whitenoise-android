package dev.ipf.whitenoise.android.ui.conversation

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeUp
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import dev.ipf.whitenoise.android.R
import dev.ipf.whitenoise.android.audio.tts.TtsPassage
import dev.ipf.whitenoise.android.audio.tts.TtsState
import dev.ipf.whitenoise.android.ui.theme.WhiteNoiseTheme
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class ConversationTtsFollowComposeTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    @Config(qualifiers = "mdpi")
    fun directDragSuspendsFollowButProgrammaticScrollDoesNotAndResumeIsAccessible() {
        composeRule.setContent {
            WhiteNoiseTheme {
                val listState = rememberLazyListState()
                val policy =
                    remember {
                        ConversationTtsFollowPolicy().apply {
                            observe(speakingState(), ownsSession = true)
                            claimPendingTarget()
                        }
                    }
                LaunchedEffect(listState, policy) {
                    listState.interactionSource.interactions.collectConversationDragInteractions(
                        onStarted = policy::onUserDrag,
                        awaitScrollSettled = {
                            snapshotFlow { listState.isScrollInProgress }.filter { !it }.first()
                        },
                        onSettled = {},
                    )
                }
                Box {
                    LazyColumn(
                        state = listState,
                        modifier = Modifier.height(320.dp).testTag(TRANSCRIPT_TAG),
                    ) {
                        items(80) { index ->
                            Text("Message $index", Modifier.fillMaxWidth().height(56.dp))
                        }
                    }
                    if (policy.showResumeAction) {
                        TtsResumeFollowButton(onClick = policy::resumeFollow)
                    }
                }
            }
        }

        val resumeLabel =
            ApplicationProvider
                .getApplicationContext<android.content.Context>()
                .getString(R.string.tts_resume_follow)
        composeRule.onNodeWithTag(TRANSCRIPT_TAG).performScrollToIndex(30)
        composeRule.onNodeWithText(resumeLabel).assertDoesNotExist()

        composeRule.onNodeWithTag(TRANSCRIPT_TAG).performTouchInput { swipeUp() }
        composeRule
            .onNodeWithText(resumeLabel)
            .assertIsDisplayed()
            .assertHasClickAction()
            .assertHeightIsAtLeast(48.dp)
        composeRule.onNodeWithText(resumeLabel).performClick()
        composeRule.onNodeWithText(resumeLabel).assertDoesNotExist()
    }

    @Test
    fun directDragWhilePagingIsSuspendedPreventsDeferredFollowScroll() {
        val pagingStarted = CompletableDeferred<Unit>()
        val releasePaging = CompletableDeferred<Unit>()
        lateinit var visibleKeys: () -> List<Any>
        val messages = (0 until 100).map { "message-$it" }
        val targetMessageId = "message-5"

        composeRule.setContent {
            WhiteNoiseTheme {
                DeferredFollowHarness(
                    messages = messages,
                    targetMessageId = targetMessageId,
                    pagingStarted = pagingStarted,
                    releasePaging = releasePaging,
                    onVisibleKeys = { visibleKeys = it },
                )
            }
        }
        composeRule.waitUntil { pagingStarted.isCompleted }

        composeRule.onNodeWithTag(TRANSCRIPT_TAG).performTouchInput { swipeUp() }
        val resumeLabel =
            ApplicationProvider
                .getApplicationContext<android.content.Context>()
                .getString(R.string.tts_resume_follow)
        composeRule.onNodeWithText(resumeLabel).assertIsDisplayed()
        composeRule.runOnIdle { releasePaging.complete(Unit) }
        composeRule.waitForIdle()

        composeRule.runOnIdle {
            assertTrue(
                "a deferred follow command must not defeat the direct drag",
                targetMessageId !in visibleKeys(),
            )
        }
    }

    @Test
    fun recycledTargetRowIsRemountedByStableKey() {
        var request by mutableStateOf(0)
        lateinit var visibleKeys: () -> List<Any>
        val messages = (0 until 100).map { "message-$it" }

        composeRule.setContent {
            WhiteNoiseTheme {
                FollowViewportHarness(
                    messages = messages,
                    initialFirstVisibleItemIndex = 90,
                    targetMessageId = "message-5",
                    request = request,
                    onVisibleKeys = { visibleKeys = it },
                )
            }
        }
        composeRule.runOnIdle { request++ }
        composeRule.waitForIdle()

        composeRule.runOnIdle {
            assertTrue("the recycled target key must be remounted", "message-5" in visibleKeys())
        }
    }

    @Test
    fun pagedTargetUsesItsIndexAfterPrependingRows() {
        var messages by mutableStateOf((50 until 100).map { "message-$it" })
        var request by mutableStateOf(0)
        lateinit var visibleKeys: () -> List<Any>

        composeRule.setContent {
            WhiteNoiseTheme {
                FollowViewportHarness(
                    messages = messages,
                    initialFirstVisibleItemIndex = 0,
                    targetMessageId = "message-40",
                    request = request,
                    onVisibleKeys = { visibleKeys = it },
                )
            }
        }
        composeRule.runOnIdle {
            messages = (0 until 100).map { "message-$it" }
            request++
        }
        composeRule.waitForIdle()

        composeRule.runOnIdle {
            assertTrue("the paged target must be resolved after the prepend", "message-40" in visibleKeys())
        }
    }

    @Test
    fun targetEvictedBetweenBoundedScrollPhasesNeverScrollsReplacementRow() {
        var messages by mutableStateOf((0 until 100).map { "message-$it" })
        var request by mutableStateOf(0)
        var resolveCalls = 0
        lateinit var visibleKeys: () -> List<Any>
        val targetMessageId = "message-5"
        val replacementMessageId = "message-6"

        composeRule.setContent {
            WhiteNoiseTheme {
                FollowViewportHarness(
                    messages = messages,
                    initialFirstVisibleItemIndex = 90,
                    targetMessageId = targetMessageId,
                    request = request,
                    onVisibleKeys = { visibleKeys = it },
                    targetIndexResolver = {
                        resolveCalls++
                        if (resolveCalls == 1) {
                            messages.indexOf(targetMessageId).takeIf { it >= 0 }
                        } else {
                            messages = messages.filterNot { it == targetMessageId }
                            null
                        }
                    },
                )
            }
        }
        composeRule.runOnIdle { request++ }
        composeRule.waitForIdle()

        composeRule.runOnIdle {
            assertTrue("the target must be re-resolved after pre-positioning", resolveCalls >= 2)
            assertTrue(
                "the row that replaced the removed target index must not be scrolled into view",
                replacementMessageId !in visibleKeys(),
            )
        }
    }

    @Composable
    private fun FollowViewportHarness(
        messages: List<String>,
        initialFirstVisibleItemIndex: Int,
        targetMessageId: String,
        request: Int,
        onVisibleKeys: (() -> List<Any>) -> Unit,
        targetIndexResolver: (() -> Int?)? = null,
    ) {
        val listState = rememberLazyListState(initialFirstVisibleItemIndex = initialFirstVisibleItemIndex)
        val coordinator =
            remember(listState) {
                ConversationScrollCoordinator(
                    writer = LazyListConversationScrollWriter(listState),
                    initialMode = ConversationScrollMode.ReadingHistory(null, 0),
                )
            }
        onVisibleKeys { listState.layoutInfo.visibleItemsInfo.map { it.key } }
        LaunchedEffect(request, targetMessageId) {
            if (request == 0) return@LaunchedEffect
            val targetIndex = messages.indexOf(targetMessageId)
            if (targetIndex < 0) return@LaunchedEffect
            followTtsTargetInViewport(
                target = followTarget(targetMessageId),
                itemKey = targetMessageId,
                targetIndex = targetIndex,
                estimatedItemHeightPx = null,
                listState = listState,
                scrollCoordinator = coordinator,
                resolveTargetIndex =
                    targetIndexResolver
                        ?: { messages.indexOf(targetMessageId).takeIf { it >= 0 } },
                isCurrentTarget = { true },
                currentScrollAnchor = {
                    ConversationScrollAnchor(
                        listIndex = listState.firstVisibleItemIndex,
                        pixelOffset = listState.firstVisibleItemScrollOffset,
                        itemId =
                            listState.layoutInfo.visibleItemsInfo
                                .firstOrNull()
                                ?.key
                                ?.toString(),
                        messageId =
                            listState.layoutInfo.visibleItemsInfo
                                .firstOrNull()
                                ?.key
                                ?.toString(),
                    )
                },
            )
        }
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxWidth().height(320.dp),
        ) {
            items(messages, key = { it }) { messageId ->
                Text(messageId, Modifier.fillMaxWidth().height(80.dp))
            }
        }
    }

    @Composable
    private fun DeferredFollowHarness(
        messages: List<String>,
        targetMessageId: String,
        pagingStarted: CompletableDeferred<Unit>,
        releasePaging: CompletableDeferred<Unit>,
        onVisibleKeys: (() -> List<Any>) -> Unit,
    ) {
        val listState = rememberLazyListState(initialFirstVisibleItemIndex = 90)
        val coordinator =
            remember(listState) {
                ConversationScrollCoordinator(
                    writer = LazyListConversationScrollWriter(listState),
                    initialMode = ConversationScrollMode.ReadingHistory(null, 0),
                )
            }
        val policy =
            remember {
                ConversationTtsFollowPolicy().apply {
                    observe(speakingState(targetMessageId), ownsSession = true)
                }
            }
        onVisibleKeys { listState.layoutInfo.visibleItemsInfo.map { it.key } }
        LaunchedEffect(listState, policy) {
            listState.interactionSource.interactions.collectConversationDragInteractions(
                onStarted = {
                    policy.onUserDrag()
                    coordinator.onUserGestureStarted(listState.followAnchor())
                },
                awaitScrollSettled = {
                    snapshotFlow { listState.isScrollInProgress }.filter { !it }.first()
                },
                onSettled = {},
            )
        }
        LaunchedEffect(policy, targetMessageId) {
            val target = policy.claimPendingTarget() ?: return@LaunchedEffect
            pagingStarted.complete(Unit)
            releasePaging.await()
            if (!policy.isCurrentTarget(target)) return@LaunchedEffect
            followTtsTargetInViewport(
                target = target,
                itemKey = targetMessageId,
                targetIndex = messages.indexOf(targetMessageId),
                estimatedItemHeightPx = null,
                listState = listState,
                scrollCoordinator = coordinator,
                resolveTargetIndex = { messages.indexOf(targetMessageId).takeIf { it >= 0 } },
                isCurrentTarget = { policy.isCurrentTarget(target) },
                currentScrollAnchor = { listState.followAnchor() },
            )
        }
        Box {
            LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxWidth().height(320.dp).testTag(TRANSCRIPT_TAG),
            ) {
                items(messages, key = { it }) { messageId ->
                    Text(messageId, Modifier.fillMaxWidth().height(80.dp))
                }
            }
            if (policy.showResumeAction) {
                TtsResumeFollowButton(onClick = policy::resumeFollow)
            }
        }
    }

    private fun LazyListState.followAnchor() =
        ConversationScrollAnchor(
            listIndex = firstVisibleItemIndex,
            pixelOffset = firstVisibleItemScrollOffset,
            itemId = null,
            messageId = null,
        )

    private fun speakingState(messageId: String = "message-1"): TtsState.Speaking {
        val target = followTarget(messageId)
        return TtsState.Speaking(
            chunkIndex = 0,
            chunkCount = 1,
            sentenceIndexWithinMessage = target.sentenceIndex,
            sentenceCountWithinMessage = target.sentenceCount,
            messageIndex = 0,
            messageCount = 1,
            messagePreview = "Sentence",
            passage =
                TtsPassage(
                    messageIdHex = target.messageIdHex,
                    sentenceIndex = target.sentenceIndex,
                    projectionId = target.projectionId,
                    timelineAt = target.timelineAt,
                ),
            sessionId = target.sessionId,
        )
    }

    private fun followTarget(messageId: String) =
        ConversationTtsFollowTarget(
            sessionId = 7L,
            messageIdHex = messageId,
            sentenceIndex = 1,
            sentenceCount = 3,
            projectionId = "projection-$messageId",
            timelineAt = 42uL,
        )

    private companion object {
        const val TRANSCRIPT_TAG = "tts-follow-transcript"
    }
}
