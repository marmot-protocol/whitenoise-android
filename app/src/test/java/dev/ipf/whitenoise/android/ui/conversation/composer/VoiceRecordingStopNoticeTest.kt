package dev.ipf.whitenoise.android.ui.conversation.composer

import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.junit4.createComposeRule
import dev.ipf.whitenoise.android.audio.VoiceRecordingStopReason
import dev.ipf.whitenoise.android.state.ConversationNoticeDestination
import dev.ipf.whitenoise.android.ui.common.TopNoticeClaim
import dev.ipf.whitenoise.android.ui.common.TopNoticeHost
import dev.ipf.whitenoise.android.ui.common.topNoticeFor
import dev.ipf.whitenoise.android.ui.share.emptyAppState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Exercises real notice and review ownership without microphone, player or network I/O. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class VoiceRecordingStopNoticeTest {
    @get:Rule val compose = createComposeRule()

    @get:Rule val files = TemporaryFolder()

    /** App Lock's disposal removes private bytes and the actual host notice, without queuing a toast. */
    @Test
    fun disposingTheProtectedConversationRetiresItsStoppedTakeNotice() =
        runTest {
            val state = emptyAppState()
            val account = requireNotNull(state.activeAccountRef)
            val review = review(this)
            val take = files.newFile("interrupted.m4a").apply { writeText("captured audio") }
            val visible = mutableStateOf(true)
            val host = TopNoticeHost(SnackbarHostState(), notice = { state.transientNotice })
            val claim = TopNoticeClaim().also(host::claim)
            val destination = ConversationNoticeDestination(account, "group")
            lateinit var stopNotice: (VoiceRecordingStopReason, VoiceReviewClip?) -> Unit
            compose.setContent {
                if (visible.value) {
                    val onStop = rememberVoiceRecordingStopNotice(review, state, account, "group")
                    SideEffect { stopNotice = onStop }
                    DisposableEffect(review) { onDispose { review.release() } }
                }
            }
            lateinit var completedClip: VoiceReviewClip
            compose.runOnIdle {
                assertTrue(review.offer(take, 1_000L))
                completedClip = requireNotNull(review.clip)
                stopNotice(VoiceRecordingStopReason.Interrupted, completedClip)
            }
            compose.runOnIdle {
                val notice = requireNotNull(state.transientNotice)
                assertSame(notice, topNoticeFor(host.transientNotice, host.owns(claim), destination))
                assertNull("automatic stop must never enter the global snackbar queue", state.toast)
                assertTrue(take.exists())
                visible.value = false
            }
            compose.runOnIdle {
                assertFalse(take.exists())
                assertNull(review.clip)
                assertNull(state.transientNotice)
                assertNull(topNoticeFor(host.transientNotice, host.owns(claim), destination))
                stopNotice(VoiceRecordingStopReason.Interrupted, completedClip)
                assertNull("a retired completion cannot recreate its notice", state.transientNotice)
            }
        }

    /** Losing the take clears its prompt while leaving the live conversation available for recording again. */
    @Test
    fun discardingTheTakeRetiresItsPromptWithoutDisposingTheConversation() =
        runTest {
            val state = emptyAppState()
            val account = requireNotNull(state.activeAccountRef)
            val review = review(this)
            lateinit var stopNotice: (VoiceRecordingStopReason, VoiceReviewClip?) -> Unit
            compose.setContent {
                val onStop = rememberVoiceRecordingStopNotice(review, state, account, "group")
                SideEffect { stopNotice = onStop }
                DisposableEffect(review) { onDispose { review.release() } }
            }
            compose.runOnIdle { assertTrue(review.offer(files.newFile("limit.m4a"), 1_000L)) }
            compose.runOnIdle { stopNotice(VoiceRecordingStopReason.LimitReached, review.clip) }
            compose.runOnIdle {
                assertNotNull(state.transientNotice)
                assertTrue(review.discard(requireNotNull(review.clip)))
            }
            compose.runOnIdle {
                assertNull(state.transientNotice)
                stopNotice(VoiceRecordingStopReason.Interrupted, review.clip)
                assertNull("no playable review means no review instruction", state.transientNotice)
            }
        }

    /** Explicit send acceptance retires the review instruction with the transferred take. */
    @Test
    fun acceptingAnExplicitSendRetiresItsStoppedTakeNotice() =
        runTest {
            val state = emptyAppState()
            val account = requireNotNull(state.activeAccountRef)
            var sends = 0
            val review =
                VoiceRecordingReview(
                    this,
                    { true },
                    { _, _, _, accepted ->
                        sends += 1
                        accepted(true)
                    },
                    playback = NoPlayback,
                )
            lateinit var stopNotice: (VoiceRecordingStopReason, VoiceReviewClip?) -> Unit
            compose.setContent {
                val onStop = rememberVoiceRecordingStopNotice(review, state, account, "group")
                SideEffect { stopNotice = onStop }
                DisposableEffect(review) { onDispose { review.release() } }
            }
            compose.runOnIdle {
                assertTrue(review.offer(files.newFile("send.m4a"), 1_000L))
                stopNotice(VoiceRecordingStopReason.Interrupted, review.clip)
                assertEquals(0, sends)
            }
            compose.runOnIdle {
                assertNotNull(state.transientNotice)
                review.send(requireNotNull(review.clip))
            }
            compose.runOnIdle {
                assertEquals(1, sends)
                assertNull(review.clip)
                assertNull(state.transientNotice)
            }
        }

    /** Retiring an old review cannot clear a newer notice or make its prompt visible in another account. */
    @Test
    fun disposingTheOldReviewPreservesANewerUnrelatedNotice() =
        runTest {
            val state = emptyAppState()
            val account = requireNotNull(state.activeAccountRef)
            val review = review(this)
            val visible = mutableStateOf(true)
            lateinit var stopNotice: (VoiceRecordingStopReason, VoiceReviewClip?) -> Unit
            compose.setContent {
                if (visible.value) {
                    val onStop = rememberVoiceRecordingStopNotice(review, state, account, "group")
                    SideEffect { stopNotice = onStop }
                    DisposableEffect(review) { onDispose { review.release() } }
                }
            }
            compose.runOnIdle {
                assertTrue(review.offer(files.newFile("old.m4a"), 1_000L))
                assertFalse(review.offer(files.newFile("rejected.m4a"), 0L))
                stopNotice(VoiceRecordingStopReason.Interrupted, null)
                assertNull("a rejected completion cannot announce the older take", state.transientNotice)
                stopNotice(VoiceRecordingStopReason.Interrupted, review.clip)
            }
            compose.runOnIdle {
                val owned = requireNotNull(state.transientNotice)
                assertNull(topNoticeFor(owned, owner = true, ConversationNoticeDestination("other", "group")))
                assertNull(topNoticeFor(owned, owner = true, ConversationNoticeDestination(account, "other")))
                state.presentTransient("New unrelated notice")
                visible.value = false
            }
            compose.runOnIdle {
                val replacement = requireNotNull(state.transientNotice)
                stopNotice(VoiceRecordingStopReason.Interrupted, review.clip)
                assertSame(replacement, state.transientNotice)
            }
        }

    /** Uses the real review owner while rejecting any accidental send or native playback. */
    private fun review(scope: CoroutineScope): VoiceRecordingReview =
        VoiceRecordingReview(
            scope,
            { true },
            { _, _, _, _ -> error("automatic interruption must not send") },
            playback = NoPlayback,
        )

    /** This test never asks the native player to acquire audio focus. */
    private object NoPlayback : VoiceReviewPlayback {
        /** Rejects unintended playback in a notice-only test. */
        override suspend fun play(clip: VoiceReviewClip): Boolean = error("unexpected playback")

        /** Rejects unintended playback controls. */
        override fun pause(key: String) = error("unexpected pause")

        /** Release may stop this review's player without touching another owner. */
        override fun stop(key: String) = Unit

        /** No playback starts in these notice tests. */
        override fun isPlaying(key: String): Boolean = false
    }
}
