package dev.ipf.whitenoise.android.ui.conversation

import android.content.Context
import androidx.activity.ComponentActivity
import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.ipf.whitenoise.android.R
import dev.ipf.whitenoise.android.ui.conversation.media.VoiceAttachmentContent
import dev.ipf.whitenoise.android.ui.theme.WhiteNoiseTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Emulator evidence for the real LazyColumn geometry that issue #2475 protects.
 *
 * JVM coverage drives the complete production conversation and cache pipeline;
 * this renderer test complements it with platform text metrics, accessibility
 * bounds, stable lazy keys, and exact history geometry at large font scale/RTL.
 */
@RunWith(AndroidJUnit4::class)
class ConversationVoiceDownloadAnchorAndroidTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    private val context = ApplicationProvider.getApplicationContext<Context>()

    /**
     * Exercises download, failure, retry, playable, and playback states at a
     * narrow 200% font/RTL viewport while preserving every visible row pixel.
     */
    @Test
    fun historyAnchorAndAccessibleActionStayExactAcrossEveryVoiceState() {
        val voiceState = mutableStateOf(VoiceVisualState.Download)
        lateinit var listState: LazyListState
        setVoiceListContent(
            voiceStates = mapOf(HISTORY_VOICE_INDEX to voiceState),
            initialIndex = HISTORY_ANCHOR_INDEX,
            initialOffset = HISTORY_OFFSET_PX,
            layoutDirection = LayoutDirection.Rtl,
            fontScale = 2f,
            onListState = { listState = it },
        )

        val baseline = viewport(listState)
        assertAction(HISTORY_VOICE_INDEX, R.string.media_tap_to_download)
        transitionAndAssert(voiceState, VoiceVisualState.Loading, listState, baseline, R.string.media_downloading)
        transitionAndAssert(voiceState, VoiceVisualState.Failed, listState, baseline, R.string.voice_message_failed)
        transitionAndAssert(voiceState, VoiceVisualState.Download, listState, baseline, R.string.media_tap_to_download)
        transitionAndAssert(voiceState, VoiceVisualState.Playable, listState, baseline, R.string.voice_message_play)
        transitionAndAssert(voiceState, VoiceVisualState.Playing, listState, baseline, R.string.voice_message_pause)
        composeRule.onNodeWithText("0:00 / 59:59").assertExists()
    }

    /** Installs the adaptive real-list fixture and publishes its measured state to the test. */
    private fun setVoiceListContent(
        voiceStates: Map<Int, MutableState<VoiceVisualState>>,
        initialIndex: Int,
        initialOffset: Int,
        layoutDirection: LayoutDirection = LayoutDirection.Ltr,
        fontScale: Float = 1f,
        onListState: (LazyListState) -> Unit,
    ) {
        composeRule.setContent {
            val systemDensity = LocalDensity.current
            CompositionLocalProvider(
                LocalDensity provides Density(systemDensity.density, fontScale),
                LocalLayoutDirection provides layoutDirection,
            ) {
                WhiteNoiseTheme {
                    VoiceList(
                        voiceStates = voiceStates,
                        initialIndex = initialIndex,
                        initialOffset = initialOffset,
                        modifier = Modifier.width(NARROW_WIDTH).height(VIEWPORT_HEIGHT),
                        onListState = onListState,
                    )
                }
            }
        }
        composeRule.waitForIdle()
    }

    /** Renders stable-key rows through the production voice presentation component. */
    @Composable
    private fun VoiceList(
        voiceStates: Map<Int, MutableState<VoiceVisualState>>,
        initialIndex: Int,
        initialOffset: Int,
        modifier: Modifier,
        onListState: (LazyListState) -> Unit,
    ) {
        val listState = rememberLazyListState(initialIndex, initialOffset)
        SideEffect { onListState(listState) }
        LazyColumn(
            state = listState,
            modifier = modifier,
        ) {
            items(
                items = List(ITEM_COUNT) { it },
                key = { index -> "message-$index" },
            ) { index ->
                val state = voiceStates[index]
                if (state == null) {
                    Box(Modifier.fillMaxWidth().height(STANDARD_ROW_HEIGHT))
                } else {
                    VoiceRow(index, state.value)
                }
            }
        }
    }

    /** Maps a deterministic fixture phase to the production row's explicit presentation state. */
    @Composable
    private fun VoiceRow(
        index: Int,
        phase: VoiceVisualState,
    ) {
        VoiceAttachmentContent(
            loading = phase == VoiceVisualState.Loading,
            failed = phase == VoiceVisualState.Failed,
            startDownload = false,
            localFileAvailable = phase == VoiceVisualState.Playable || phase == VoiceVisualState.Playing,
            isPlaying = phase == VoiceVisualState.Playing,
            isPaused = false,
            activePositionMs = 0,
            activeDurationMs = if (phase == VoiceVisualState.Playing) LONG_DURATION_MS else 0,
            totalDurationMs =
                if (phase == VoiceVisualState.Playable || phase == VoiceVisualState.Playing) {
                    LONG_DURATION_MS
                } else {
                    0
                },
            waveform = WAVEFORM,
            progressFraction = 0f,
            playbackSpeed = if (phase == VoiceVisualState.Playing) 1f else null,
            attachedToCaption = false,
            onLongPress = {},
            onActionClick = {},
            onSeek = null,
            onCycleSpeed = {},
            modifier = Modifier.testTag(voiceTag(index)),
        )
    }

    /** Changes one phase and checks localized semantics, touch bounds, and exact list geometry. */
    private fun transitionAndAssert(
        state: MutableState<VoiceVisualState>,
        phase: VoiceVisualState,
        listState: LazyListState,
        expectedViewport: LazyViewport,
        @StringRes actionDescription: Int,
    ) {
        composeRule.runOnIdle { state.value = phase }
        composeRule.waitForIdle()
        assertAction(HISTORY_VOICE_INDEX, actionDescription)
        assertEquals(expectedViewport, viewport(listState))
    }

    /** Requires the localized action semantics to own at least a 48dp square target. */
    private fun assertAction(
        voiceIndex: Int,
        @StringRes description: Int,
    ) {
        val matcher =
            hasContentDescription(context.getString(description)) and
                hasAnyAncestor(hasTestTag(voiceTag(voiceIndex)))
        val node = composeRule.onNode(matcher, useUnmergedTree = true).assertExists().fetchSemanticsNode()
        val minimumTargetPx = composeRule.density.run { 48.dp.toPx() }
        assertTrue("voice action width must be at least 48dp", node.touchBoundsInRoot.width >= minimumTargetPx)
        assertTrue("voice action height must be at least 48dp", node.touchBoundsInRoot.height >= minimumTargetPx)
    }

    /** Captures logical identity plus pixel geometry from the platform-measured LazyColumn. */
    private fun viewport(listState: LazyListState): LazyViewport {
        lateinit var snapshot: LazyViewport
        composeRule.runOnIdle {
            snapshot =
                LazyViewport(
                    firstVisibleItemIndex = listState.firstVisibleItemIndex,
                    firstVisibleItemScrollOffset = listState.firstVisibleItemScrollOffset,
                    canScrollForward = listState.canScrollForward,
                    visibleItems =
                        listState.layoutInfo.visibleItemsInfo.map { item ->
                            VisibleItem(
                                key = item.key.toString(),
                                index = item.index,
                                offset = item.offset,
                                size = item.size,
                            )
                        },
                )
        }
        return snapshot
    }

    /** Presentation phases that can otherwise change a voice row's measured height. */
    private enum class VoiceVisualState {
        Download,
        Loading,
        Failed,
        Playable,
        Playing,
    }

    /** Immutable device-measured viewport used for exact before/after equality. */
    private data class LazyViewport(
        val firstVisibleItemIndex: Int,
        val firstVisibleItemScrollOffset: Int,
        val canScrollForward: Boolean,
        val visibleItems: List<VisibleItem>,
    )

    /** One stable lazy key with its device-measured pixel position and size. */
    private data class VisibleItem(
        val key: String,
        val index: Int,
        val offset: Int,
        val size: Int,
    )

    private companion object {
        const val ITEM_COUNT = 36
        const val HISTORY_VOICE_INDEX = 18
        const val HISTORY_ANCHOR_INDEX = 15
        const val HISTORY_OFFSET_PX = 17
        const val LONG_DURATION_MS = 3_599_000
        val NARROW_WIDTH = 320.dp
        val VIEWPORT_HEIGHT = 480.dp
        val STANDARD_ROW_HEIGHT = 72.dp
        val WAVEFORM = FloatArray(64) { index -> 0.2f + (index % 5) * 0.1f }

        /** Stable semantic owner for one voice row in the device fixture. */
        fun voiceTag(index: Int): String = "device-voice-$index"
    }
}
