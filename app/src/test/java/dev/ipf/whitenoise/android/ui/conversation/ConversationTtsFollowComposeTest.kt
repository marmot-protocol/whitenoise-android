package dev.ipf.whitenoise.android.ui.conversation

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertWidthIsAtLeast
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeUp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import dev.ipf.marmotkit.MarkdownBlockFfi
import dev.ipf.marmotkit.MarkdownDocumentFfi
import dev.ipf.marmotkit.MarkdownInlineFfi
import dev.ipf.whitenoise.android.R
import dev.ipf.whitenoise.android.audio.tts.TtsPassage
import dev.ipf.whitenoise.android.audio.tts.TtsState
import dev.ipf.whitenoise.android.ui.MarkdownMessageBody
import dev.ipf.whitenoise.android.ui.conversation.messages.TtsSentenceProjectionSegment
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
@Suppress("LargeClass")
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
            .assertWidthIsAtLeast(48.dp)
        composeRule.onNodeWithText(resumeLabel).performClick()
        composeRule.onNodeWithText(resumeLabel).assertDoesNotExist()
    }

    @Test
    fun manualScrollSuspensionSurvivesRestorationForTheActiveSession() {
        val restorationTester = StateRestorationTester(composeRule)
        var groupId by mutableStateOf("group-1")
        var state: TtsState by mutableStateOf(speakingState())
        lateinit var suspendFollow: () -> Unit
        restorationTester.setContent {
            WhiteNoiseTheme {
                val policy = rememberConversationTtsFollowPolicy(groupIdHex = groupId)
                suspendFollow = policy::onUserDrag
                LaunchedEffect(state) {
                    policy.observe(state, ownsSession = true)
                }
                if (policy.showResumeAction) {
                    TtsResumeFollowButton(onClick = policy::resumeFollow)
                }
            }
        }
        composeRule.waitForIdle()
        val resumeLabel =
            ApplicationProvider
                .getApplicationContext<android.content.Context>()
                .getString(R.string.tts_resume_follow)

        composeRule.runOnIdle { suspendFollow() }
        composeRule.onNodeWithText(resumeLabel).assertIsDisplayed()

        restorationTester.emulateSavedInstanceStateRestore()
        composeRule.runOnIdle {
            val passage = requireNotNull(state.passage)
            state =
                (state as TtsState.Speaking).copy(
                    sentenceIndexWithinMessage = 2,
                    passage = passage.copy(sentenceIndex = 2),
                )
        }

        composeRule.onNodeWithText(resumeLabel).assertIsDisplayed()

        composeRule.runOnIdle { groupId = "group-2" }
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

    @Test
    fun staleLargeRowHeightEstimateCannotLeaveCompactTargetUnmounted() {
        var request by mutableStateOf(0)
        lateinit var visibleKeys: () -> List<Any>
        val messages = (0 until 100).map { "message-$it" }
        val targetMessageId = "message-5"

        composeRule.setContent {
            WhiteNoiseTheme {
                FollowViewportHarness(
                    messages = messages,
                    initialFirstVisibleItemIndex = 90,
                    targetMessageId = targetMessageId,
                    request = request,
                    onVisibleKeys = { visibleKeys = it },
                    estimatedItemHeightPx = 10_000,
                )
            }
        }
        composeRule.runOnIdle { request++ }
        composeRule.waitForIdle()

        composeRule.runOnIdle {
            assertTrue("the provisional jump must mount the target row", targetMessageId in visibleKeys())
        }
    }

    @Test
    fun markdownLeafReportsItsNewWindowPositionAfterParentRelayout() {
        var offsetPx by mutableStateOf(0)
        val reportedTops = mutableListOf<Float>()
        val document =
            MarkdownDocumentFfi(
                blocks = listOf(MarkdownBlockFfi.Paragraph(listOf(MarkdownInlineFfi.Text("Sentence")))),
                truncated = false,
                blankLinesBefore = byteArrayOf(0),
            )
        composeRule.setContent {
            WhiteNoiseTheme {
                Box(Modifier.offset { IntOffset(0, offsetPx) }) {
                    MarkdownMessageBody(
                        document = document,
                        ttsSentenceLayoutReporter = { _, _, layout, coordinates ->
                            if (layout != null && coordinates != null) {
                                reportedTops += coordinates.positionInWindow().y
                            }
                        },
                    )
                }
            }
        }
        composeRule.waitForIdle()
        val initialTop = reportedTops.last()

        composeRule.runOnIdle { offsetPx = 120 }
        composeRule.waitForIdle()

        composeRule.runOnIdle {
            assertTrue(
                "a placement-only move must publish fresh sentence coordinates",
                reportedTops.last() >= initialTop + 119f,
            )
        }
    }

    @Test
    fun tallMediaBeforeSentenceUsesMeasuredSentenceGeometry() {
        assertMeasuredShapeFollowsSentence(FollowTargetShape.TallMediaBeforeSentence)
    }

    @Test
    fun markdownSentenceSplitAcrossRenderedLeavesWaitsForCompleteGeometry() {
        assertMeasuredShapeFollowsSentence(FollowTargetShape.SplitMarkdown)
    }

    @Test
    fun replyAndFooterDoNotBiasBodySentenceFollow() {
        assertMeasuredShapeFollowsSentence(FollowTargetShape.ReplyAndFooterAroundBody)
    }

    private fun assertMeasuredShapeFollowsSentence(shape: FollowTargetShape) {
        var request by mutableStateOf(0)
        lateinit var targetLayout: () -> Pair<Rect?, Rect?>
        val messages = (0 until 30).map { "message-$it" }
        composeRule.setContent {
            WhiteNoiseTheme {
                FollowViewportHarness(
                    messages = messages,
                    initialFirstVisibleItemIndex = 25,
                    targetMessageId = "message-5",
                    request = request,
                    onVisibleKeys = {},
                    targetShape = shape,
                    onTargetLayout = { targetLayout = it },
                )
            }
        }
        composeRule.runOnIdle { request++ }
        composeRule.waitForIdle()

        composeRule.runOnIdle {
            val (sentenceBounds, viewportBounds) = targetLayout()
            val sentence = requireNotNull(sentenceBounds)
            val viewport = requireNotNull(viewportBounds)
            val safeTop = viewport.top + viewport.height * 0.20f
            val safeBottom = viewport.bottom - viewport.height * 0.20f
            assertTrue(
                "sentence top ${sentence.top} must be at or below safe top $safeTop in viewport $viewport",
                sentence.top >= safeTop - 2f,
            )
            assertTrue("sentence bottom must be inside the measured safe band", sentence.bottom <= safeBottom + 2f)
        }
    }

    @Composable
    @Suppress("LongMethod")
    private fun FollowViewportHarness(
        messages: List<String>,
        initialFirstVisibleItemIndex: Int,
        targetMessageId: String,
        request: Int,
        onVisibleKeys: (() -> List<Any>) -> Unit,
        targetIndexResolver: (() -> Int?)? = null,
        targetShape: FollowTargetShape = FollowTargetShape.Compact,
        onTargetLayout: ((() -> Pair<Rect?, Rect?>) -> Unit)? = null,
        estimatedItemHeightPx: Int? = null,
    ) {
        val listState = rememberLazyListState(initialFirstVisibleItemIndex = initialFirstVisibleItemIndex)
        val sentenceLayouts = remember { ConversationTtsSentenceLayoutRegistry() }
        val coordinator =
            remember(listState) {
                ConversationScrollCoordinator(
                    writer = LazyListConversationScrollWriter(listState),
                    initialMode = ConversationScrollMode.ReadingHistory(null, 0),
                )
            }
        onVisibleKeys { listState.layoutInfo.visibleItemsInfo.map { it.key } }
        onTargetLayout?.invoke {
            sentenceLayouts.completeSentenceBounds(followTarget(targetMessageId)) to
                sentenceLayouts.viewportBoundsInWindow
        }
        LaunchedEffect(request, targetMessageId) {
            if (request == 0) return@LaunchedEffect
            val targetIndex = messages.indexOf(targetMessageId)
            if (targetIndex < 0) return@LaunchedEffect
            followTtsTargetInViewport(
                target = followTarget(targetMessageId),
                direction = TtsFollowDirection.Forward,
                itemKey = targetMessageId,
                targetIndex = targetIndex,
                estimatedItemHeightPx = estimatedItemHeightPx,
                listState = listState,
                scrollCoordinator = coordinator,
                sentenceLayouts = sentenceLayouts,
                claimPreposition = { true },
                claimCorrectiveScroll = { true },
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
            modifier =
                Modifier
                    .fillMaxWidth()
                    .height(320.dp)
                    .onGloballyPositioned { sentenceLayouts.updateViewportBounds(it.boundsInWindow()) },
        ) {
            items(messages, key = { it }) { messageId ->
                if (messageId == targetMessageId) {
                    ProductionShapedFollowTargetRow(
                        messageId = messageId,
                        target = followTarget(messageId),
                        shape = targetShape,
                        sentenceLayouts = sentenceLayouts,
                    )
                } else {
                    Text(messageId, Modifier.fillMaxWidth().height(80.dp))
                }
            }
        }
    }

    @Composable
    @Suppress("LongMethod")
    private fun DeferredFollowHarness(
        messages: List<String>,
        targetMessageId: String,
        pagingStarted: CompletableDeferred<Unit>,
        releasePaging: CompletableDeferred<Unit>,
        onVisibleKeys: (() -> List<Any>) -> Unit,
    ) {
        val listState = rememberLazyListState(initialFirstVisibleItemIndex = 90)
        val sentenceLayouts = remember { ConversationTtsSentenceLayoutRegistry() }
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
            val request = policy.claimPendingRequest() ?: return@LaunchedEffect
            val target = request.target
            pagingStarted.complete(Unit)
            releasePaging.await()
            if (!policy.isCurrentTarget(target)) return@LaunchedEffect
            followTtsTargetInViewport(
                target = target,
                direction = request.direction,
                itemKey = targetMessageId,
                targetIndex = messages.indexOf(targetMessageId),
                estimatedItemHeightPx = null,
                listState = listState,
                scrollCoordinator = coordinator,
                sentenceLayouts = sentenceLayouts,
                claimPreposition = { policy.claimPreposition(target) },
                claimCorrectiveScroll = { policy.claimCorrectiveScroll(target) },
                resolveTargetIndex = { messages.indexOf(targetMessageId).takeIf { it >= 0 } },
                isCurrentTarget = { policy.isCurrentTarget(target) },
                currentScrollAnchor = { listState.followAnchor() },
            )
        }
        Box {
            LazyColumn(
                state = listState,
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .height(320.dp)
                        .testTag(TRANSCRIPT_TAG)
                        .onGloballyPositioned { sentenceLayouts.updateViewportBounds(it.boundsInWindow()) },
            ) {
                items(messages, key = { it }) { messageId ->
                    if (messageId == targetMessageId) {
                        ProductionShapedFollowTargetRow(
                            messageId = messageId,
                            target = followTarget(messageId),
                            shape = FollowTargetShape.Compact,
                            sentenceLayouts = sentenceLayouts,
                        )
                    } else {
                        Text(messageId, Modifier.fillMaxWidth().height(80.dp))
                    }
                }
            }
            if (policy.showResumeAction) {
                TtsResumeFollowButton(onClick = policy::resumeFollow)
            }
        }
    }

    @Composable
    @Suppress("LongMethod")
    private fun ProductionShapedFollowTargetRow(
        messageId: String,
        target: ConversationTtsFollowTarget,
        shape: FollowTargetShape,
        sentenceLayouts: ConversationTtsSentenceLayoutRegistry,
    ) {
        val rowInstance = remember(messageId) { Any() }
        val segments =
            remember(shape) {
                when (shape) {
                    FollowTargetShape.SplitMarkdown ->
                        listOf(
                            TtsSentenceProjectionSegment("markdown-a", 0, 8),
                            TtsSentenceProjectionSegment("markdown-b", 8, 16),
                        )
                    else -> listOf(TtsSentenceProjectionSegment("plain", 0, 16))
                }.toSet()
            }
        DisposableEffect(sentenceLayouts, messageId, rowInstance) {
            sentenceLayouts.mountRow(messageId, rowInstance)
            onDispose { sentenceLayouts.unmountRow(messageId, rowInstance) }
        }
        Column(Modifier.fillMaxWidth()) {
            when (shape) {
                FollowTargetShape.Compact ->
                    FollowSentenceFragment(
                        text = "Measured sentence",
                        leafId = "plain",
                        heightDp = 80,
                        target = target,
                        rowInstance = rowInstance,
                        coverage = segments,
                        expectedCoverage = segments,
                        sentenceLayouts = sentenceLayouts,
                    )
                FollowTargetShape.TallMediaBeforeSentence -> {
                    Box(Modifier.fillMaxWidth().height(500.dp))
                    FollowSentenceFragment(
                        text = "Sentence after tall media",
                        leafId = "plain",
                        heightDp = 48,
                        target = target,
                        rowInstance = rowInstance,
                        coverage = segments,
                        expectedCoverage = segments,
                        sentenceLayouts = sentenceLayouts,
                    )
                    Box(Modifier.fillMaxWidth().height(180.dp))
                }
                FollowTargetShape.SplitMarkdown -> {
                    Box(Modifier.fillMaxWidth().height(460.dp))
                    FollowSentenceFragment(
                        text = "Markdown",
                        leafId = "markdown-a",
                        heightDp = 40,
                        target = target,
                        rowInstance = rowInstance,
                        coverage = setOf(TtsSentenceProjectionSegment("markdown-a", 0, 8)),
                        expectedCoverage = segments,
                        sentenceLayouts = sentenceLayouts,
                    )
                    FollowSentenceFragment(
                        text = "sentence",
                        leafId = "markdown-b",
                        heightDp = 40,
                        target = target,
                        rowInstance = rowInstance,
                        coverage = setOf(TtsSentenceProjectionSegment("markdown-b", 8, 16)),
                        expectedCoverage = segments,
                        sentenceLayouts = sentenceLayouts,
                    )
                    Box(Modifier.fillMaxWidth().height(120.dp))
                }
                FollowTargetShape.ReplyAndFooterAroundBody -> {
                    Box(Modifier.fillMaxWidth().height(180.dp))
                    FollowSentenceFragment(
                        text = "Body sentence",
                        leafId = "plain",
                        heightDp = 48,
                        target = target,
                        rowInstance = rowInstance,
                        coverage = segments,
                        expectedCoverage = segments,
                        sentenceLayouts = sentenceLayouts,
                    )
                    Box(Modifier.fillMaxWidth().height(300.dp))
                }
            }
        }
    }

    @Composable
    private fun FollowSentenceFragment(
        text: String,
        leafId: String,
        heightDp: Int,
        target: ConversationTtsFollowTarget,
        rowInstance: Any,
        coverage: Set<TtsSentenceProjectionSegment>,
        expectedCoverage: Set<TtsSentenceProjectionSegment>,
        sentenceLayouts: ConversationTtsSentenceLayoutRegistry,
    ) {
        DisposableEffect(sentenceLayouts, target, rowInstance, leafId) {
            onDispose { sentenceLayouts.clear(target, rowInstance, leafId) }
        }
        Text(
            text,
            Modifier
                .fillMaxWidth()
                .height(heightDp.dp)
                .onGloballyPositioned { coordinates ->
                    val topLeft = coordinates.localToWindow(Offset.Zero)
                    sentenceLayouts.report(
                        ConversationTtsSentenceLayoutReport(
                            target = target,
                            rowInstance = rowInstance,
                            renderedLeafId = leafId,
                            boundsInWindow =
                                Rect(
                                    left = topLeft.x,
                                    top = topLeft.y,
                                    right = topLeft.x + coordinates.size.width,
                                    bottom = topLeft.y + coordinates.size.height,
                                ),
                            coverage = coverage,
                            expectedCoverage = expectedCoverage,
                        ),
                    )
                },
        )
    }

    private enum class FollowTargetShape {
        Compact,
        TallMediaBeforeSentence,
        SplitMarkdown,
        ReplyAndFooterAroundBody,
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
