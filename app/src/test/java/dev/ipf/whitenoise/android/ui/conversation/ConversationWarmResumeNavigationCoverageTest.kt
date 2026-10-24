package dev.ipf.whitenoise.android.ui.conversation

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** Freshness and IME correction must leave a safe retained navigation frame drawable. */
@OptIn(ExperimentalCoroutinesApi::class)
class ConversationWarmResumeNavigationCoverageTest {
    @Test
    fun pauseFocusLossAndAutomaticResumeFocusPreserveCapturedAnchor() {
        val coordinator = ConversationScrollCoordinator(RecordingWriter())
        val focus = ConversationForegroundFocusIntent()
        focus.pause()
        coordinator.beginForegroundRestore(
            ConversationForegroundSnapshot(
                coordinator.bookmark(ConversationScrollAnchor(4, 12, "retained", "retained")),
                ConversationForegroundGeometry(720, 0, 96),
            ),
        )
        if (focus.supersedesRestore(focused = false, resumed = false)) {
            coordinator.cancelForegroundRestoreForUserIntent()
        }
        assertTrue(coordinator.foregroundRestorePending)
        focus.resume(restoreFocus = true, clearFocus = false)
        // The pause callback can arrive after ON_RESUME and before requestFocus takes effect.
        if (focus.supersedesRestore(focused = false, resumed = true)) {
            coordinator.cancelForegroundRestoreForUserIntent()
        }
        assertTrue(coordinator.foregroundRestorePending)
        assertFalse(focus.supersedesRestore(focused = true, resumed = true))
        assertTrue(coordinator.foregroundRestorePending)
        if (focus.supersedesRestore(focused = false, resumed = true)) {
            coordinator.cancelForegroundRestoreForUserIntent()
        }
        assertFalse(coordinator.foregroundRestorePending)
    }

    @Test
    fun automaticClearFocusIsNotNewUserIntentButNextForegroundFocusIs() {
        val focus = ConversationForegroundFocusIntent()
        focus.resume(restoreFocus = false, clearFocus = true)
        assertFalse(focus.supersedesRestore(focused = true, resumed = true))
        assertFalse(focus.supersedesRestore(focused = false, resumed = true))
        assertTrue(focus.supersedesRestore(focused = true, resumed = true))
    }

    @Test
    fun pauseRetiresAutomaticFocusAndNewOwnerCannotInheritIt() {
        val focus = ConversationForegroundFocusIntent()
        focus.resume(restoreFocus = true, clearFocus = false)
        focus.pause()
        assertFalse(focus.supersedesRestore(focused = false, resumed = false))
        focus.resume(restoreFocus = false, clearFocus = true)
        assertFalse(focus.supersedesRestore(focused = true, resumed = true))
        assertFalse(focus.supersedesRestore(focused = false, resumed = true))
        assertTrue(focus.supersedesRestore(focused = true, resumed = true))
        val replacement = ConversationForegroundFocusIntent()
        assertTrue(replacement.supersedesRestore(focused = false, resumed = true))
    }

    @Test
    fun heldHandoffAndDeniedImeDoNotOwnTheReleasedDrawGate() =
        runTest {
            val coordinator = ConversationScrollCoordinator(RecordingWriter())
            val geometry = ConversationForegroundGeometry(720, 0, 96)
            val token =
                coordinator.beginForegroundRestore(
                    ConversationForegroundSnapshot(
                        coordinator.bookmark(ConversationScrollAnchor(0, 0, null, null)),
                        geometry,
                    ),
                )
            val held = CompletableDeferred<Unit>()
            val signals = Channel<Unit>(Channel.CONFLATED)
            coordinator.releaseForegroundRestoreGate(token)
            val correction =
                async {
                    awaitConversationForegroundPresentation(
                        signals,
                        { ConversationForegroundSettleState(geometry, 0, true) },
                        expectedImeVisible = true,
                        expectedVisibilityTimeoutMillis = 1_500,
                        awaitLocalTimeline = { held.await() },
                    )
                }
            runCurrent()
            assertFalse(correction.isCompleted)
            assertTrue(ConversationForegroundDrawGate({ coordinator.foregroundRestoreInProgress }).onPreDraw())
            assertTrue(coordinator.foregroundRestorePending)
            coordinator.cancelForegroundRestoreForUserIntent() // The production Back/typing paths retire this token.
            assertFalse(
                coordinator.completeForegroundRestore(
                    token,
                    geometry.copy(viewportHeightPx = 400),
                    resolveAnchorIndex = { 0 },
                    resolveTailIndex = { 1 },
                ),
            )
            assertFalse(held.isCompleted)
            correction.cancelAndJoin()
        }

    @Test
    fun productionResumeReleasesOnlyAdmittedRetainedContentBeforeWaiting() {
        val source =
            sequenceOf(File("src/main"), File("app/src/main"))
                .map {
                    File(it, "java/dev/ipf/whitenoise/android/ui/conversation/ConversationForegroundRestoreEffects.kt")
                }.first(File::isFile)
                .readText()
        val resume =
            source.substringAfter("val resumeRestoration:").substringBefore("ConversationComposerLifecycleEffect(")
        val actualResume = source.substringAfter("onResume = { restoreFocus, clearFocus ->")
        assertTrue(actualResume.contains("resumeRestoration(restoreFocus)"))
        assertTrue(actualResume.contains("controller.retryInviteAcceptanceAuthority()"))
        assertTrue(resume.contains("onHandoff = { resumeRestoration(false) { } }"))
        val binding =
            sequenceOf(File("src/main"), File("app/src/main"))
                .map {
                    File(it, "java/dev/ipf/whitenoise/android/ui/conversation/ConversationForegroundRestoreBinding.kt")
                }.first(File::isFile)
                .readText()
        val release = resume.indexOf("restore?.releaseMeasuredFrame(")
        assertTrue(release >= 0)
        assertTrue(release < resume.indexOf("awaitConversationForegroundPresentation("))
        assertTrue(
            resume
                .substringBefore("val pendingLocalTimeline")
                .contains("currentForegroundSettleStateProvider().timelineMeasured"),
        )
        // Admission is captured by the live provider before resume, then rechecked before correction.
        val admissionProvider = source.substringBefore("ConversationForegroundDrawGateEffect(")
        assertTrue(
            admissionProvider.contains("ownsCurrentPresentation = controller.ownsRetainedForegroundPresentation"),
        )
        assertTrue(admissionProvider.contains("lockVisible = controller.appState.appLockScreenVisible"))
        assertTrue(resume.contains("ConversationForegroundRestoreBinding.canPresentRetainedFrame("))
        assertTrue(binding.contains("currentPresentationSafe && (paused == null || paused.owner === currentOwner)"))
        assertTrue(binding.contains("if (!retainedPresentationSafe || !timelineMeasured) return false"))
        assertTrue(binding.contains("owner.releaseForegroundRestoreGate(token)"))
        assertTrue(resume.contains("if (!currentCanPresentRetainedFrame())"))
        assertTrue(resume.contains("awaitConversationForegroundAdmission("))
    }

    private class RecordingWriter : ConversationScrollWriter {
        override val firstVisibleItemIndex = 0

        override suspend fun scrollToItem(
            index: Int,
            scrollOffset: Int,
        ) = Unit

        override suspend fun animateScrollToItem(
            index: Int,
            scrollOffset: Int,
        ) = Unit
    }
}
