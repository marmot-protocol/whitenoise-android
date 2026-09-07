package dev.ipf.whitenoise.android.ui.conversation.media

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.unit.dp
import dev.ipf.marmotkit.EncryptedMediaVersionFfi
import dev.ipf.marmotkit.MediaAttachmentReferenceFfi
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36])
class ConversationMediaViewerLifetimeTest {
    @get:Rule
    val composeRule = createComposeRule()

    /** Keeps one mounted player and its state while the origin row disposes and the viewport resizes. */
    @Test
    fun activeViewerOutlivesItsOriginatingLazyRowAndViewportResize() {
        val fixture = mountLifetimeViewer()
        openViewerAndDisposeOrigin()
        val initialPlayer = fixture.mountedPlayer
        playbackScenarios.forEach { scenario ->
            assertScenarioSurvivesResize(fixture, initialPlayer, scenario)
        }
        composeRule.runOnIdle { assertEquals(1, fixture.playerMounts) }

        assertDismissalPreservesAnchor(fixture)
    }

    /** Mounts the conversation-owned host and captures its player and lazy-list owners. */
    private fun mountLifetimeViewer(): LifetimeViewerFixture {
        val fixture = LifetimeViewerFixture()
        composeRule.setContent {
            val sessionState =
                rememberConversationMediaViewerSessionState(
                    owner = ConversationMediaViewerOwner("personal", "group-a", 1),
                )
            val listState = rememberLazyListState().also { fixture.timelineState = it }
            Box(
                Modifier
                    .width(if (fixture.landscape) 780.dp else 360.dp)
                    .height(if (fixture.landscape) 360.dp else 780.dp),
            ) {
                LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxSize().testTag(TIMELINE_TAG),
                ) {
                    item(key = "video-message") {
                        Text(
                            text = "Open video",
                            modifier =
                                Modifier
                                    .testTag(ORIGIN_TAG)
                                    .clickable { sessionState.open(request()) },
                        )
                    }
                    items(80, key = { "message-$it" }) { Text("Message $it") }
                }
                ConversationMediaViewerSessionHost(sessionState) { active ->
                    val player =
                        remember {
                            FakePlayer(instanceId = ++fixture.playerMounts).also { fixture.mountedPlayer = it }
                        }
                    Text(
                        text = active.selectedAttachment.messageIdHex,
                        modifier =
                            Modifier
                                .testTag(VIEWER_TAG)
                                .clickable { sessionState.dismiss(active.sessionId) }
                                .semantics {
                                    this[SemanticsProperties.StateDescription] = player.instanceId.toString()
                                },
                    )
                }
            }
        }
        return fixture
    }

    /** Opens the viewer and proves its source row has left the lazy composition. */
    private fun openViewerAndDisposeOrigin() {
        composeRule.onNodeWithTag(ORIGIN_TAG).performClick()
        composeRule.onNodeWithTag(VIEWER_TAG).assertIsDisplayed()
        composeRule.onNodeWithTag(TIMELINE_TAG).performScrollToIndex(79)
        composeRule.onNodeWithTag(ORIGIN_TAG).assertDoesNotExist()
    }

    /** Verifies one playback state and position survive a viewport-size recomposition. */
    private fun assertScenarioSurvivesResize(
        fixture: LifetimeViewerFixture,
        initialPlayer: FakePlayer,
        scenario: PlaybackScenario,
    ) {
        composeRule.runOnIdle {
            fixture.mountedPlayer.playWhenReady = scenario.playWhenReady
            fixture.mountedPlayer.playbackState = scenario.playbackState
            fixture.mountedPlayer.positionMs = scenario.positionMs
        }
        composeRule.runOnIdle { fixture.landscape = !fixture.landscape }
        composeRule
            .onNodeWithTag(VIEWER_TAG)
            .assertIsDisplayed()
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "1"))
        composeRule.runOnIdle {
            assertSame(initialPlayer, fixture.mountedPlayer)
            assertEquals(scenario.playWhenReady, fixture.mountedPlayer.playWhenReady)
            assertEquals(scenario.playbackState, fixture.mountedPlayer.playbackState)
            assertEquals(scenario.positionMs, fixture.mountedPlayer.positionMs)
        }
    }

    /** Dismisses the host without changing the lazy conversation anchor. */
    private fun assertDismissalPreservesAnchor(fixture: LifetimeViewerFixture) {
        val anchorBeforeDismiss = composeRule.runOnIdle { fixture.timelineState.firstVisibleItemIndex }
        composeRule.onNodeWithTag(VIEWER_TAG).performClick().assertDoesNotExist()
        composeRule.runOnIdle {
            assertEquals(anchorBeforeDismiss, fixture.timelineState.firstVisibleItemIndex)
        }
    }

    /** Recreates remembered session state and closes the viewer at every production owner boundary. */
    @Test
    fun accountConversationAndRuntimeOwnerTransitionsFailClosedInComposition() {
        var owner by mutableStateOf(ConversationMediaViewerOwner("personal", "group-a", 1))
        lateinit var currentState: ConversationMediaViewerSessionState

        composeRule.setContent {
            val sessionState = rememberConversationMediaViewerSessionState(owner).also { currentState = it }
            Text(
                text = owner.toString(),
                modifier =
                    Modifier
                        .testTag(OWNER_TRANSITION_TAG)
                        .clickable { sessionState.open(request()) },
            )
        }

        val owners =
            listOf(
                owner.copy(accountRef = "work"),
                owner.copy(accountRef = "work", conversationId = "group-b"),
                owner.copy(accountRef = "work", conversationId = "group-b", runtimeGeneration = 2),
            )
        owners.forEach { replacement ->
            composeRule.onNodeWithTag(OWNER_TRANSITION_TAG).performClick()
            val previous = composeRule.runOnIdle { currentState }
            composeRule.runOnIdle { assertEquals(request().openingAttachment, previous.active?.selectedAttachment) }

            composeRule.runOnIdle { owner = replacement }
            composeRule.waitForIdle()

            composeRule.runOnIdle {
                assertNotSame(previous, currentState)
                assertEquals(replacement, currentState.owner)
                assertNull(currentState.active)
            }
        }
    }

    /** Creates the single-video open request used by the lifetime host. */
    private fun request() =
        ConversationMediaViewerOpenRequest(
            messageIdHex = "video-message",
            attachments = listOf(IndexedValue(0, reference())),
            tappedAttachmentIndex = 0,
            sender = "sender",
            recordedAt = 1uL,
            mine = false,
        )

    /** Creates a stable encrypted-video reference whose identity does not change during resize. */
    private fun reference() =
        MediaAttachmentReferenceFfi(
            locators = emptyList(),
            ciphertextSha256 = "aa".repeat(32),
            plaintextSha256 = "bb".repeat(32),
            nonceHex = "cc".repeat(12),
            fileName = "clip.mp4",
            mediaType = "video/mp4",
            version = EncryptedMediaVersionFfi.V1,
            sourceEpoch = 1uL,
            dim = "1920x1080",
            thumbhash = null,
        )

    private data class FakePlayer(
        val instanceId: Int,
        var playWhenReady: Boolean = false,
        var playbackState: FakePlaybackState = FakePlaybackState.Buffering,
        var positionMs: Long = 0L,
    )

    private enum class FakePlaybackState {
        Buffering,
        Ready,
        Ended,
    }

    private data class PlaybackScenario(
        val playWhenReady: Boolean,
        val playbackState: FakePlaybackState,
        val positionMs: Long,
    )

    /** Mutable observations shared by the mounted composition and its resize assertions. */
    private class LifetimeViewerFixture {
        var landscape by mutableStateOf(false)
        var playerMounts = 0
        lateinit var mountedPlayer: FakePlayer
        lateinit var timelineState: LazyListState
    }

    private companion object {
        const val ORIGIN_TAG = "originating-video-row"
        const val OWNER_TRANSITION_TAG = "media-viewer-owner-transition"
        const val TIMELINE_TAG = "conversation-timeline"
        const val VIEWER_TAG = "conversation-media-viewer"

        val playbackScenarios =
            listOf(
                PlaybackScenario(true, FakePlaybackState.Ready, 12_345L),
                PlaybackScenario(false, FakePlaybackState.Ready, 23_456L),
                PlaybackScenario(true, FakePlaybackState.Buffering, 34_567L),
                PlaybackScenario(false, FakePlaybackState.Ended, 45_678L),
                PlaybackScenario(true, FakePlaybackState.Ready, 56_789L),
                PlaybackScenario(false, FakePlaybackState.Ready, 67_890L),
            )
    }
}
